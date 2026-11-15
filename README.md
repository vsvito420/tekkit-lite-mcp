# tekkit-lite-mcp

Lets AI coding agents (**Claude Code** and **OpenAI Codex**) see and control a Minecraft 1.4.7
client (Tekkit-Lite-style modpack, e.g. Lycodon client 3.1, LiteLoader + Forge 6.6.2) – including
ComputerCraft computers and turtles.

## Parts

| File | What it does |
|---|---|
| `src/LiteModClaudeBridge.java` | LiteLoader mod: token-protected HTTP API on `127.0.0.1:25599` (state, blocks, chat, screenshots, walking, placing/digging, containers, ComputerCraft screen/keys) |
| `build.sh` | Builds `mod-claudebridge-0.1.litemod` (`./build.sh install` copies it into the instance) |
| `mc` | Shell CLI for the API – run `./mc` for the command list |
| `cb.lua`, `cc_transfer.py` | Checksum-verified file transfer to/from ComputerCraft computers |
| `chatgpt-bridge.mjs` | Optional local web chat using the OpenAI Responses API ([README](README-chatgpt-bridge.md)) |
| `skills/minecraft-bridge/SKILL.md` | Agent skill/instructions |

## Setup

1. Adjust the paths in `build.sh` / `mc` (`MC_INSTANCE`) to your Prism Launcher instance, then `./build.sh install`.
2. Restart the whole game. The mod writes a fresh `claudebridge.token` into the instance folder on every start.
3. Test: `./mc state`

### Claude Code
```bash
mkdir -p ~/.claude/skills && cp -R skills/minecraft-bridge ~/.claude/skills/
```

### Codex
Codex reads `AGENTS.md` in this repo. Start Codex in this folder, or copy `skills/minecraft-bridge/SKILL.md`
into your global `~/.codex/AGENTS.md`.

## Security
The API listens only on localhost and requires the per-start token (`X-Token` header). No API keys are stored in this repo.
