import type { ReactNode } from "react";

const NAV_ITEMS = [
  { label: "概览", milestone: "M0", active: true },
  { label: "实例库", milestone: "M4" },
  { label: "下载", milestone: "M2" },
  { label: "Java 运行时", milestone: "M6" },
  { label: "设置", milestone: "M10" },
];

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-bg-base">
      <div className="mx-auto flex min-h-screen w-full max-w-[1280px] gap-10 px-5 py-6 sm:px-8 lg:px-12 lg:py-10">
        <aside className="hidden w-52 shrink-0 flex-col justify-between border-r border-hairline pr-6 lg:flex">
          <div>
            <div className="flex items-center gap-3">
              <div className="flex size-9 items-center justify-center rounded-[10px] border border-glass-edge bg-glass text-[15px] font-semibold text-accent">
                雾
              </div>
              <div>
                <p className="text-sm font-semibold tracking-tight">Fograin</p>
                <p className="text-[11px] text-text-2">雾雨启动器</p>
              </div>
            </div>

            <nav aria-label="主导航" className="mt-9 flex flex-col gap-1">
              {NAV_ITEMS.map((item) => (
                <div
                  key={item.label}
                  aria-current={item.active ? "page" : undefined}
                  className={
                    item.active
                      ? "flex items-center justify-between rounded-lg border border-glass-edge bg-glass px-3 py-2 text-[13px] text-text-1"
                      : "flex items-center justify-between rounded-lg px-3 py-2 text-[13px] text-text-2"
                  }
                >
                  <span>{item.label}</span>
                  <span className="font-mono text-[10px] text-text-2">{item.milestone}</span>
                </div>
              ))}
            </nav>
          </div>

          <div className="rounded-xl border border-hairline bg-glass-fill p-3">
            <p className="text-[10px] font-medium tracking-[0.16em] text-text-2 uppercase">
              Milestone
            </p>
            <p className="mt-2 text-sm font-semibold">M0 · 工程骨架</p>
            <p className="mt-1 text-[11px] leading-5 text-text-2">
              三平台空窗口、平台皮肤注入与日志链路。
            </p>
          </div>
        </aside>

        <main className="min-w-0 flex-1">{children}</main>
      </div>
    </div>
  );
}
