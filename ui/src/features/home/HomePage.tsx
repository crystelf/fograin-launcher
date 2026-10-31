import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import type { PlatformSkin } from "@/ipc/bindings";
import { fetchAppInfo } from "@/lib/app-info";
import { useAppStore } from "@/store/app";

const SKINS: ReadonlyArray<{ id: PlatformSkin; label: string }> = [
  { id: "macos", label: "macOS" },
  { id: "win11", label: "Windows 11" },
  { id: "gnome", label: "GNOME" },
  { id: "android", label: "Material 3" },
];

const M0_ITEMS: ReadonlyArray<{ title: string; detail: string }> = [
  { title: "Rust workspace", detail: "fg-core + 9 个里程碑 crate + fg-cli" },
  { title: "Tauri 2 外壳", detail: "主窗口、能力配置与首帧皮肤注入" },
  { title: "tauri-specta", detail: "app_info 命令与 bindings.ts 自动生成" },
  { title: "前端基础栈", detail: "React 19、Tailwind v4、Router、Zustand、Query" },
  { title: "可观测性", detail: "tracing 文件日志 + panic 崩溃报告" },
  { title: "CI", detail: "三平台 cargo test、pnpm build、tauri build" },
];

const SWATCHES: ReadonlyArray<{ label: string; className: string }> = [
  { label: "--bg-base", className: "bg-bg-base" },
  { label: "--bg-elevated", className: "bg-bg-elevated" },
  { label: "--accent", className: "bg-accent" },
  { label: "--glass", className: "bg-glass" },
  { label: "--text-1", className: "bg-text-1" },
  { label: "--md-primary", className: "bg-md-primary" },
];

export function HomePage() {
  const { data, error, isError, isPending } = useQuery({
    queryKey: ["app-info"],
    queryFn: fetchAppInfo,
  });
  const platform = useAppStore((state) => state.platform);
  const setPlatform = useAppStore((state) => state.setPlatform);

  useEffect(() => {
    if (!data) {
      return;
    }

    setPlatform(data.platform);
    document.documentElement.dataset.platform = data.platform;
  }, [data, setPlatform]);

  const activeId = data?.platform ?? platform;
  const activeLabel =
    data?.platformLabel ?? SKINS.find((skin) => skin.id === platform)?.label ?? "识别中";
  const errorMessage = isError
    ? error instanceof Error
      ? error.message
      : String(error)
    : null;

  return (
    <div className="pb-4">
      <header className="flex flex-wrap items-end justify-between gap-5 border-b border-hairline pb-6">
        <div>
          <SectionLabel>M0 / Engineering skeleton</SectionLabel>
          <h1 className="mt-2 text-[32px] leading-tight font-semibold tracking-[-0.03em] sm:text-4xl">
            工程骨架已就绪
          </h1>
          <p className="mt-3 max-w-xl text-[13px] leading-6 text-text-2">
            Rust 工作区、Tauri 外壳、tauri-specta 类型绑定与 React 基础栈已经接通。当前窗口只验证三平台外壳与平台皮肤。
          </p>
        </div>

        <span
          className={
            isError
              ? "inline-flex items-center gap-2 rounded-full border border-md-error/40 bg-md-error/10 px-3 py-1.5 text-[11px] text-md-error"
              : "inline-flex items-center gap-2 rounded-full border border-glass-edge bg-glass px-3 py-1.5 text-[11px] text-text-2"
          }
        >
          <span
            className={
              isError
                ? "size-1.5 rounded-full bg-md-error"
                : isPending
                  ? "size-1.5 animate-pulse rounded-full bg-text-3"
                  : "size-1.5 rounded-full bg-accent"
            }
          />
          {isError ? "app_info 未连接" : isPending ? "正在读取…" : "M0 已打通"}
        </span>
      </header>

      {errorMessage ? (
        <div className="mt-4 rounded-xl border border-md-error/40 bg-md-error/10 px-4 py-3 text-[12px] text-md-error">
          无法调用 app_info：{errorMessage}
        </div>
      ) : null}

      <div className="mt-5 grid grid-cols-1 gap-4 lg:grid-cols-6">
        <section className="rounded-2xl border border-sep bg-bg-elevated p-6 lg:col-span-4">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <SectionLabel>当前平台皮肤</SectionLabel>
              <h2 className="mt-3 text-4xl font-semibold tracking-[-0.04em]">{activeLabel}</h2>
              <p className="mt-2 font-mono text-[11px] text-text-2">
                data-platform=&quot;{activeId ?? "…"}&quot;
              </p>
            </div>
            <span className="inline-flex items-center gap-2 rounded-full border border-glass-edge bg-glass px-3 py-1.5 text-[11px] text-text-2">
              <span className="size-1.5 rounded-full bg-accent" />
              Rust 首帧注入
            </span>
          </div>

          <div className="mt-6 grid grid-cols-2 gap-2 sm:grid-cols-4">
            {SKINS.map((skin) => {
              const active = skin.id === activeId;

              return (
                <div
                  key={skin.id}
                  className={
                    active
                      ? "rounded-xl border border-glass-edge bg-glass p-3"
                      : "rounded-xl border border-hairline p-3"
                  }
                >
                  <p
                    className={
                      active
                        ? "text-[12px] font-medium text-text-1"
                        : "text-[12px] text-text-2"
                    }
                  >
                    {skin.label}
                  </p>
                  <p className="mt-1 font-mono text-[10px] text-text-2">{skin.id}</p>
                </div>
              );
            })}
          </div>

          <p className="mt-5 text-[12px] leading-5 text-text-2">
            窗口创建前通过 Tauri initialization script 写入
            <code className="mx-1 rounded bg-glass-fill px-1.5 py-0.5 font-mono text-[10px] text-text-1">
              &lt;html data-platform&gt;
            </code>
            ，首帧即使用对应皮肤的令牌。
          </p>
        </section>

        <section className="rounded-2xl border border-sep bg-bg-elevated p-6 lg:col-span-2">
          <SectionLabel>运行环境</SectionLabel>
          <dl className="mt-4 space-y-3">
            <InfoRow label="应用版本" value={data?.version ?? "—"} />
            <InfoRow label="Tauri" value={data?.tauriVersion ?? "—"} />
            <InfoRow
              label="系统"
              value={data ? `${data.os} · ${data.arch}` : "—"}
            />
            <InfoRow label="应用名" value={data?.name ?? "—"} />
          </dl>
          <dl className="mt-5 border-t border-hairline pt-4">
            <dt className="text-[11px] text-text-2">数据目录</dt>
            <dd
              className="mt-2 break-all font-mono text-[10px] leading-5 text-text-1"
              title={data?.dataRoot}
            >
              {data?.dataRoot ?? "—"}
            </dd>
          </dl>
        </section>

        <section className="rounded-2xl border border-sep bg-bg-elevated p-6 lg:col-span-3">
          <div className="flex items-center justify-between gap-3">
            <SectionLabel>M0 清单</SectionLabel>
            <span className="font-mono text-[10px] text-text-2">6 / 6</span>
          </div>
          <ul className="mt-4 space-y-3">
            {M0_ITEMS.map((item) => (
              <li key={item.title} className="flex gap-3">
                <span className="mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full bg-accent/15 text-[10px] text-accent">
                  ✓
                </span>
                <div>
                  <p className="text-[12px] text-text-1">{item.title}</p>
                  <p className="mt-0.5 text-[11px] leading-5 text-text-2">{item.detail}</p>
                </div>
              </li>
            ))}
          </ul>
        </section>

        <section className="rounded-2xl border border-sep bg-bg-elevated p-6 lg:col-span-3">
          <SectionLabel>设计令牌</SectionLabel>
          <p className="mt-2 text-[12px] leading-5 text-text-2">
            暗色与 Android 取自 <InlineCode>fograin.pen</InlineCode>，浅色为 §4.1.1 初稿。
          </p>
          <div className="mt-4 grid grid-cols-3 gap-2">
            {SWATCHES.map((swatch) => (
              <div key={swatch.label}>
                <div className={`h-9 rounded-lg border border-hairline ${swatch.className}`} />
                <p className="mt-1.5 truncate font-mono text-[10px] text-text-2">{swatch.label}</p>
              </div>
            ))}
          </div>
          <p className="mt-4 border-t border-hairline pt-4 text-[11px] leading-5 text-text-2">
            四套皮肤共享语义令牌：业务组件只引用 <InlineCode>--bg-*</InlineCode>、
            <InlineCode>--text-*</InlineCode>，平台差异由 <InlineCode>data-platform</InlineCode>{" "}
            切换。
          </p>
        </section>

        <section className="rounded-2xl border border-dashed border-hairline bg-glass-fill p-6 lg:col-span-6">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <SectionLabel>下一步</SectionLabel>
              <h2 className="mt-2 text-lg font-semibold">M1 · 外壳与设计系统</h2>
              <p className="mt-1 text-[12px] leading-5 text-text-2">
                三套桌面外壳、深浅色切换、基础组件与静态页面还原。
              </p>
            </div>
            <span className="font-mono text-[11px] text-text-2">docs/design.md §14</span>
          </div>
        </section>
      </div>

      <footer className="mt-6 flex flex-wrap items-center justify-between gap-2 border-t border-hairline pt-4 text-[10px] text-text-2">
        <span>Fograin Launcher · GPL-3.0</span>
        <span className="font-mono">tauri-specta → ui/src/ipc/bindings.ts</span>
      </footer>
    </div>
  );
}

function SectionLabel({ children }: { children: string }) {
  return (
    <p className="text-[10px] font-medium tracking-[0.16em] text-text-2 uppercase">{children}</p>
  );
}

function InlineCode({ children }: { children: string }) {
  return (
    <code className="mx-0.5 rounded bg-glass-fill px-1 py-0.5 font-mono text-[10px] text-text-1">
      {children}
    </code>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="shrink-0 text-[11px] text-text-2">{label}</dt>
      <dd className="min-w-0 truncate text-right text-[12px] text-text-1" title={value}>
        {value}
      </dd>
    </div>
  );
}
