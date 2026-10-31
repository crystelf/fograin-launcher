import type { PlatformSkin } from "@/ipc/bindings";
import { create } from "zustand";

interface AppState {
  /** Skin injected by Rust before the first paint, then confirmed by app_info. */
  platform: PlatformSkin | null;
  setPlatform: (platform: PlatformSkin) => void;
}

const KNOWN_PLATFORMS: readonly string[] = ["macos", "win11", "gnome", "android"];

function injectedPlatform(): PlatformSkin | null {
  const value = document.documentElement.dataset.platform;
  return value && KNOWN_PLATFORMS.includes(value) ? (value as PlatformSkin) : null;
}

export const useAppStore = create<AppState>((set) => ({
  platform: injectedPlatform(),
  setPlatform: (platform) => set({ platform }),
}));
