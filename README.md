# tekkit-lite-mcp

Lets AI coding agents (**Claude Code** and **OpenAI Codex**) see and control a Minecraft 1.4.7
client (Tekkit-Lite-style modpack, e.g. Lycodon client 3.1, LiteLoader + Forge 6.6.2) – including
ComputerCraft computers and turtles.

## Parts

| File | What it does |
|---|---|
| `src/LiteModClaudeBridge.java` | LiteLoader mod: token-protected HTTP API on `127.0.0.1:25599` (state, blocks, chat, screenshots, walking, placing/digging, containers, ComputerCraft screen/keys) |
| `build.sh` | Builds `mod-claudebridge-0.1.litemod` (`./build.sh install` copies it into the instance) |
| `mcp-server.mjs` | **MCP server** (stdio, Node 18+, no dependencies) – 26 tools: `mc_state`, `mc_screenshot`, `mc_goto`, `mc_place`, `cc_screen`, `cc_type`, … |
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
claude mcp add --scope user tekkit-lite -- node /path/to/tekkit-lite-mcp/mcp-server.mjs
```
Optional: `cp -R skills/minecraft-bridge ~/.claude/skills/` for extra workflow tips.

### Codex
```bash
codex mcp add tekkit-lite -- node /path/to/tekkit-lite-mcp/mcp-server.mjs
```
or in `~/.codex/config.toml`:
```toml
[mcp_servers.tekkit-lite]
command = "node"
args = ["/path/to/tekkit-lite-mcp/mcp-server.mjs"]
```

### Claude Desktop
In `claude_desktop_config.json`:
```json
{ "mcpServers": { "tekkit-lite": { "command": "node", "args": ["/path/to/tekkit-lite-mcp/mcp-server.mjs"] } } }
```

Set `MC_INSTANCE` (env) if your instance folder is not the default Prism `Lycodon-Client(1)` path.

## Security
The API listens only on localhost and requires the per-start token (`X-Token` header). No API keys are stored in this repo.
