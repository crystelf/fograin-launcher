import { commands, type AppInfo, type PlatformSkin } from "@/ipc/bindings";

const PLATFORM_LABELS: Record<PlatformSkin, string> = {
  macos: "macOS",
  win11: "Windows 11",
  gnome: "GNOME",
  android: "Material 3",
};

function isTauriRuntime(): boolean {
  return "__TAURI_INTERNALS__" in window;
}

function detectBrowserPlatform(): PlatformSkin {
  const userAgent = navigator.userAgent;

  if (/Android/i.test(userAgent)) {
    return "android";
  }
  if (/Windows/i.test(userAgent)) {
    return "win11";
  }
  if (/Macintosh|Mac OS X/i.test(userAgent)) {
    return "macos";
  }
  return "gnome";
}

function previewAppInfo(): AppInfo {
  const platform = detectBrowserPlatform();

  return {
    name: "Fograin",
    version: "0.1.0-preview",
    tauriVersion: "—",
    platform,
    platformLabel: PLATFORM_LABELS[platform],
    os: "browser",
    arch: "web",
    dataRoot: "—",
  };
}

/**
 * Reads launcher info from Rust. `pnpm dev` can also be opened in a plain
 * browser, so that case returns a preview object instead of throwing.
 */
export async function fetchAppInfo(): Promise<AppInfo> {
  if (!isTauriRuntime()) {
    return previewAppInfo();
  }

  return commands.appInfo();
}
