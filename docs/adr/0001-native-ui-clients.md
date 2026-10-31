# ADR 0001: Four native UI clients on one Rust core

- Status: accepted
- Date: 2026-10-31
- Supersedes: the Tauri + single React UI assumption in design v0.1

## Context

The M1 prototype used Tauri + React and reproduced the pen layouts with CSS.
Native window controls (traffic lights) were easy to add, but the web layer
still:

- could not expose real native controls, focus, menus or accessibility;
- required a two-way bridge as soon as native chrome was introduced;
- made every platform-specific control a CSS imitation.

The design is a launcher, not a website. Four native clients are acceptable;
the shared logic is the Rust core.

## Decision

Use one Rust core and four independent native UI clients:

```text
crates/                 shared Rust business logic
bindings/fg-ffi/        versioned FFI boundary
apps/macos/             Swift + SwiftUI + AppKit bridges
apps/windows/           C# + WinUI 3 (Windows App SDK)
apps/linux/             Rust + GTK4 + libadwaita
apps/android/           Kotlin + Jetpack Compose + GameActivity
design/                 tokens and exported assets
```

The pen file remains the layout and information-architecture reference. It is
not a CSS specification, and pixel parity is not a goal across platforms.

## Why not a hybrid native shell + WebView

That option keeps one React page tree, but every native control (toolbar,
sidebar, selection, search, menus) needs a bridge and two-way state sync. It is
the highest-maintenance option and does not fully deliver native behavior. If
native is the requirement, own the whole UI.

## Why not keep Tauri with CSS

It is the cheapest cross-platform option, but it cannot meet the native-control
requirement. The UI would remain a web imitation and the native portions would
be limited to window chrome.

## Consequences

- No shared UI code; design tokens and contract are shared instead.
- Four build toolchains and four release pipelines.
- Rust core stays UI-framework-free; `fg-ffi` is versioned and tested per client.
- The macOS client can ship first; Windows/Linux/Android follow without
  blocking backend milestones M2–M7.
- The old `ui/`, `src-tauri/` and Tauri plugins are deleted; they are not kept
  as a compatibility layer.

## Revisit criteria

Revisit only if one of these becomes true:

1. Native controls are no longer a product requirement.
2. The cost of four UI codebases exceeds the product's schedule.
3. A cross-platform toolkit can deliver genuinely native window controls and
   accessibility on all four targets (currently not the case).
