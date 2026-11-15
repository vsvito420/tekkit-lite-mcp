# ChatGPT / OpenAI adapter

This adds a small local Node service; it does **not** change the LiteLoader mod.
That preserves the existing Minecraft 1.4.7 / Forge 6.6.2 / Java-7 setup and
uses its current token-protected `127.0.0.1:25599` HTTP interface. The bridge
also dismisses the 1.4.7 pause menu before a requested game action without
pressing Escape, so switching back from ChatGPT cannot accidentally reopen it.

## Start

1. Start the Lycodon instance and enter a world. The mod creates a new
   `claudebridge.token` on every game start.
2. In a terminal:

   ```bash
   cd tekkit-lite-mcp
   export OPENAI_API_KEY='your-api-key'
   node chatgpt-bridge.mjs
   ```

3. Open <http://127.0.0.1:25600>. Ask a read-only question first, such as
   “Was habe ich im Inventar?”

The service uses the Responses API with function calling. Read tools include
game state, blocks, nearby entities, and chat. It can also move the player,
place/dig/use blocks, send chat, and stop actions directly when requested.

## Test checklist

1. With Minecraft running, open the page and ask for the player position or
   inventory. It should use `get_game_state` and return the real data.
2. Ask for blocks around the player. It should use `scan_nearby_blocks`.
3. Ask it to place a block that is already in the hotbar. Afterwards ask for
   the game state and check `placed`,
   `failures`, or `walk`.
4. Restart Minecraft, then repeat step 1. No service restart should be needed:
   the adapter reads the rotating bridge token from disk for every tool call.

## Security notes

- `OPENAI_API_KEY` is read only from the environment and is never written to
  disk or sent to Minecraft.
- The Minecraft bridge token is read from its existing file at call time and
  is only sent to `127.0.0.1:25599` in the `X-Token` header.
- The web UI listens only on `127.0.0.1`; do not expose port 25600 to the
  network.
- The pause-menu fix is in the LiteLoader bridge. After updating it with
  `./build.sh install`, restart Minecraft once. The ChatGPT adapter itself
  does not need a restart when Minecraft restarts.

Implementation references: [OpenAI JavaScript libraries](https://developers.openai.com/api/docs/libraries)
and [Responses function calling](https://developers.openai.com/api/docs/guides/function-calling).
