# GNUClient

Ghost client for Minecraft 1.8.9 Forge

## Requirements
- Linux (this build will NOT run on Windows/Mac — the native agent is a Linux .so)
- Java 8 (matches this Forge/MCP target)
- Minecraft 1.8.9 with Forge installed and already working
- Your user must be in the `input` group, so the client can read raw mouse/keyboard devices:
      sudo usermod -aG input $USER
  Then **fully log out and back in** (or reboot) — group changes don't apply to already-open sessions. Verify with `groups` after logging back in; `input` should be listed.

## Running it
1. clone this repostiry and cd into /build and run make gnu-all.
2. Launch Minecraft 1.8.9 Forge as normal, then ./install/bin/gnu-inject  . 
3. In-game, press **INSERT** to open the ClickGUI menu. **ESC** or INSERT again to close the ClickGUI.
