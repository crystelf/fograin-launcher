//! Threaded bridge from the GTK main loop to the shared launcher service.
//!
//! The Linux client is written in the same language as the core, so it links
//! `fg-ffi`'s [`Service`] directly instead of going through the C ABI (ADR
//! 0001). The service owns a Tokio runtime and every call blocks, so RPCs run on
//! a small worker pool and the GTK loop pumps the completed results and the
//! event queue. This mirrors the macOS client, which dispatches the same RPCs on
//! a dedicated `DispatchQueue` and polls `fg_events_next` every 250 ms.

use std::{
    cell::{Cell, RefCell},
    collections::{HashMap, VecDeque},
    rc::Rc,
    sync::{Arc, Mutex, mpsc},
    thread,
};

use fg_core::Error;
use fg_ffi::Service;
use serde_json::Value;

/// Number of blocking RPC workers. Downloads and version plans can take a few
/// seconds; keeping three slots means a manifest fetch never freezes the status
/// refresh triggered by the event pump.
const WORKERS: usize = 3;

/// One failure returned by the launcher core.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CoreError {
    pub kind: String,
    pub message_key: String,
    pub detail: Option<String>,
}

impl CoreError {
    #[must_use]
    pub fn unavailable() -> Self {
        Self {
            kind: "internal".to_owned(),
            message_key: "error.rpc.unavailable".to_owned(),
            detail: None,
        }
    }

    #[must_use]
    pub fn message(&self) -> String {
        self.detail
            .clone()
            .unwrap_or_else(|| self.message_key.clone())
    }

    /// User-facing Chinese copy for the common core errors. Unknown keys fall
    /// back to the technical detail so diagnostics are never lost. The table is
    /// the same one the macOS client keeps in `CoreErrorPayload.userMessage`.
    #[must_use]
    pub fn user_message(&self) -> String {
        let message = match self.message_key.as_str() {
            "error.launch.not_installed" => "游戏资源尚未安装完成，请先在下载页补全资源。",
            "error.launch.incomplete" => {
                "游戏文件不完整，上次下载可能没有完成。请重新下载缺失文件。"
            }
            "error.launch.arm64_natives" => {
                "这个版本只有 x86_64 原生库，请使用对应的 Java 运行时。"
            }
            "error.java.none_installed" => "未发现 Java 运行时。请安装 Java 后再启动。",
            "error.java.required_missing" => "没有找到匹配的 Java 版本，请安装对应版本的 Java。",
            "error.java.too_new" => "只找到更新的 Java，旧版本游戏可能会启动失败。",
            "error.java.too_old" => "只找到较旧的 Java，新版本游戏可能会启动失败。",
            "error.instance.duplicate" => "同名实例已经存在，请换一个实例名称。",
            "error.instance.delete_running" => "游戏正在运行，退出后才能删除实例。",
            "error.launch.already_running" => "已经有一个游戏在运行了。",
            "error.launch.exit_code" => "游戏异常退出。请查看日志确认原因。",
            "error.task.cancelled" => "任务已取消。",
            "error.task.already_running" => "已有下载任务在进行，请等待完成或取消。",
            "error.network" => "网络请求失败，请检查网络或代理设置。",
            "error.install.failed" => "安装没有完成，请重试。",
            "error.instance.invalid_id" => "实例名称无法转换为安全的目录名。",
            "error.rpc.unknown_method" | "error.rpc.parse" | "error.rpc.params" => {
                "启动器核心拒绝了这次请求。"
            }
            "error.rpc.unavailable" => "无法连接启动器核心。",
            _ => return self.message(),
        };
        message.to_owned()
    }
}

impl From<Error> for CoreError {
    fn from(error: Error) -> Self {
        let kind = serde_json::to_value(error.kind)
            .ok()
            .and_then(|value| value.as_str().map(str::to_owned))
            .unwrap_or_else(|| "internal".to_owned());
        Self {
            kind,
            message_key: error.message_key,
            detail: error.detail,
        }
    }
}

pub type CoreResult = Result<Value, CoreError>;

/// One progress or session event pushed by the service.
#[derive(Clone, Debug, PartialEq)]
pub enum Event {
    TaskProgress {
        task_id: String,
        name: String,
        fraction: f64,
        speed: f64,
    },
    TaskFinished {
        task_id: String,
        name: String,
        state: String,
        detail: Vec<String>,
    },
    GameStarted {
        instance_id: String,
        pid: Option<u32>,
    },
    GameWindowReady {
        instance_id: String,
    },
    GameLine {
        instance_id: String,
        text: String,
    },
    GameExited {
        instance_id: String,
        exit_code: Option<i32>,
        play_seconds: u64,
    },
}

impl Event {
    /// Parses one camelCase event object. Unknown or malformed entries are
    /// skipped so a future event type cannot break the pump.
    #[must_use]
    pub fn parse(text: &str) -> Option<Self> {
        let value: Value = serde_json::from_str(text).ok()?;
        let object = value.as_object()?;
        let string = |key: &str| object.get(key).and_then(Value::as_str).map(str::to_owned);
        let number = |key: &str| object.get(key).and_then(Value::as_f64);

        match object.get("type")?.as_str()? {
            "task.progress" => Some(Self::TaskProgress {
                task_id: string("taskId")?,
                name: string("name")?,
                fraction: number("fraction").unwrap_or(0.0).clamp(0.0, 1.0),
                speed: number("speed").unwrap_or(0.0).max(0.0),
            }),
            "task.finished" => Some(Self::TaskFinished {
                task_id: string("taskId")?,
                name: string("name")?,
                state: string("state").unwrap_or_else(|| "succeeded".to_owned()),
                detail: object
                    .get("detail")
                    .and_then(Value::as_array)
                    .map(|items| {
                        items
                            .iter()
                            .filter_map(Value::as_str)
                            .map(str::to_owned)
                            .collect()
                    })
                    .unwrap_or_default(),
            }),
            "game.started" => Some(Self::GameStarted {
                instance_id: string("instanceId").unwrap_or_default(),
                pid: object
                    .get("pid")
                    .and_then(Value::as_u64)
                    .and_then(|pid| u32::try_from(pid).ok()),
            }),
            "game.windowReady" => Some(Self::GameWindowReady {
                instance_id: string("instanceId").unwrap_or_default(),
            }),
            "game.line" => Some(Self::GameLine {
                instance_id: string("instanceId").unwrap_or_default(),
                text: string("text").unwrap_or_default(),
            }),
            "game.exited" => Some(Self::GameExited {
                instance_id: string("instanceId").unwrap_or_default(),
                exit_code: object
                    .get("exitCode")
                    .and_then(Value::as_i64)
                    .and_then(|code| i32::try_from(code).ok()),
                play_seconds: object
                    .get("playSeconds")
                    .and_then(Value::as_u64)
                    .unwrap_or(0),
            }),
            _ => None,
        }
    }
}

struct Job {
    id: u64,
    method: String,
    params: Value,
}

struct Completion {
    id: u64,
    result: CoreResult,
}

type Callback = Box<dyn FnOnce(CoreResult)>;

/// Main-thread handle to the launcher service.
pub struct Core {
    service: Option<Arc<Service>>,
    senders: Vec<mpsc::Sender<Job>>,
    pending: RefCell<HashMap<u64, Callback>>,
    completed: Arc<Mutex<VecDeque<Completion>>>,
    next_id: Cell<u64>,
    unavailable: Option<CoreError>,
}

impl Core {
    /// Builds the service and starts the RPC workers. When the service cannot be
    /// created the handle still works and reports the failure for every call, so
    /// the window can start and explain the problem.
    #[must_use]
    pub fn connect() -> (Rc<Self>, Option<CoreError>) {
        let root = fg_core::paths::data_root();
        let service = match Service::new(root) {
            Ok(service) => service,
            Err(error) => {
                let error = CoreError::from(error);
                return (
                    Rc::new(Self {
                        service: None,
                        senders: Vec::new(),
                        pending: RefCell::new(HashMap::new()),
                        completed: Arc::new(Mutex::new(VecDeque::new())),
                        next_id: Cell::new(0),
                        unavailable: Some(error.clone()),
                    }),
                    Some(error),
                );
            }
        };

        let completed = Arc::new(Mutex::new(VecDeque::new()));
        let mut senders = Vec::with_capacity(WORKERS);
        for index in 0..WORKERS {
            let (sender, receiver) = mpsc::channel::<Job>();
            senders.push(sender);
            let service = Arc::clone(&service);
            let completed = Arc::clone(&completed);
            let spawned = thread::Builder::new()
                .name(format!("fograin-rpc-{index}"))
                .spawn(move || {
                    while let Ok(job) = receiver.recv() {
                        let Job { id, method, params } = job;
                        let result = service
                            .block_on(service.rpc(&method, params))
                            .map_err(CoreError::from);
                        if let Ok(mut queue) = completed.lock() {
                            queue.push_back(Completion { id, result });
                        }
                    }
                });
            if let Err(error) = spawned {
                eprintln!("fograin: cannot start RPC worker: {error}");
            }
        }

        (
            Rc::new(Self {
                service: Some(service),
                senders,
                pending: RefCell::new(HashMap::new()),
                completed,
                next_id: Cell::new(0),
                unavailable: None,
            }),
            None,
        )
    }

    /// Queues one JSON command. The callback always runs on the GTK main loop
    /// from [`Core::pump`].
    pub fn rpc<F>(&self, method: &str, params: Value, callback: F)
    where
        F: FnOnce(CoreResult) + 'static,
    {
        if let Some(error) = &self.unavailable {
            callback(Err(error.clone()));
            return;
        }
        if self.senders.is_empty() {
            callback(Err(CoreError::unavailable()));
            return;
        }

        let id = self.next_id.get();
        self.next_id.set(id.wrapping_add(1));
        self.pending.borrow_mut().insert(id, Box::new(callback));

        let worker = (id as usize) % self.senders.len();
        let job = Job {
            id,
            method: method.to_owned(),
            params,
        };
        if self.senders[worker].send(job).is_err()
            && let Some(callback) = self.pending.borrow_mut().remove(&id)
        {
            callback(Err(CoreError::unavailable()));
        }
    }

    /// Dispatches every finished RPC. Call this from a GTK timer.
    pub fn pump(&self) {
        loop {
            let completion = self
                .completed
                .lock()
                .ok()
                .and_then(|mut queue| queue.pop_front());
            let Some(completion) = completion else {
                break;
            };
            let callback = self.pending.borrow_mut().remove(&completion.id);
            if let Some(callback) = callback {
                callback(completion.result);
            }
        }
    }

    /// Drains pending progress and session events.
    #[must_use]
    pub fn events(&self) -> Vec<Event> {
        let Some(service) = &self.service else {
            return Vec::new();
        };
        service
            .events()
            .next_with_limit(64)
            .iter()
            .filter_map(|text| Event::parse(text))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_task_and_game_events() {
        let progress = Event::parse(
            r#"{"type":"task.progress","taskId":"task-1","name":"install:1.21.4","fraction":0.42,"speed":1024.0}"#,
        )
        .expect("progress event");
        assert_eq!(
            progress,
            Event::TaskProgress {
                task_id: "task-1".to_owned(),
                name: "install:1.21.4".to_owned(),
                fraction: 0.42,
                speed: 1024.0,
            }
        );

        let exited = Event::parse(
            r#"{"type":"game.exited","instanceId":"26.3","exitCode":0,"playSeconds":44}"#,
        )
        .expect("exit event");
        assert_eq!(
            exited,
            Event::GameExited {
                instance_id: "26.3".to_owned(),
                exit_code: Some(0),
                play_seconds: 44,
            }
        );

        assert!(Event::parse("not json").is_none());
        assert!(Event::parse(r#"{"type":"future.event"}"#).is_none());
    }

    #[test]
    fn error_copy_matches_the_shared_contract() {
        let error = CoreError {
            kind: "conflict".to_owned(),
            message_key: "error.instance.duplicate".to_owned(),
            detail: Some("already exists".to_owned()),
        };
        assert_eq!(error.user_message(), "同名实例已经存在，请换一个实例名称。");

        let unknown = CoreError {
            kind: "internal".to_owned(),
            message_key: "error.custom".to_owned(),
            detail: Some("technical detail".to_owned()),
        };
        assert_eq!(unknown.user_message(), "technical detail");
    }
}
