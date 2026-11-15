#!/usr/bin/env node
// MCP server (stdio, no dependencies) for the Claude Bridge mod in Minecraft 1.4.7.
// Wraps the mod's token-protected HTTP API on 127.0.0.1:25599. Works with Claude Code, Claude Desktop and Codex.
import { readFile } from "node:fs/promises";
import { execFile } from "node:child_process";
import { createInterface } from "node:readline";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { homedir } from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
const INSTANCE = process.env.MC_INSTANCE ||
  join(homedir(), "Library/Application Support/PrismLauncher/instances/Lycodon-Client(1)/minecraft");
const URL_BASE = process.env.MC_BRIDGE_URL || "http://127.0.0.1:25599";
const KEYS = { enter: 28, up: 200, down: 208, left: 203, right: 205, tab: 15, backspace: 14, delete: 211,
  home: 199, end: 207, ctrl: 29, pageup: 201, pagedown: 209 };

async function req(path, { method, body, raw } = {}) {
  let token;
  try { token = (await readFile(join(INSTANCE, "claudebridge.token"), "utf8")).trim(); }
  catch { throw new Error("No claudebridge.token - is Minecraft running with the bridge mod? (set MC_INSTANCE if the path differs)"); }
  let res;
  try {
    res = await fetch(URL_BASE + path, {
      method: method || (body !== undefined ? "POST" : "GET"),
      headers: { "X-Token": token }, body, signal: AbortSignal.timeout(15000),
    });
  } catch { throw new Error(`Bridge not reachable at ${URL_BASE} - is Minecraft (with the bridge mod) running?`); }
  if (raw) return res;
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text}`);
  return text;
}
const q = (o) => new URLSearchParams(Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== "")
  .map(([k, v]) => [k, String(v)])).toString();
const idMeta = (s) => { if (!s) return {}; const [id, meta] = String(s).split(":"); return { id, meta }; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const py = (...args) => new Promise((resolve, reject) =>
  execFile("python3", [join(HERE, "cc_transfer.py"), ...args], { env: { ...process.env, MC_INSTANCE: INSTANCE }, timeout: 300000 },
    (err, out, errOut) => err ? reject(new Error((errOut || err.message).trim())) : resolve((out + errOut).trim())));

const num = { type: "number" }, str = { type: "string" }, int = { type: "integer" };
const xyz = { x: int, y: int, z: int };
const tool = (name, description, properties, required, run) => ({ name, description,
  inputSchema: { type: "object", properties, required: required || [] }, run });

const TOOLS = [
  tool("mc_state", "Player position, look, health, inventory, open container, running actions and macro-mod variables.", {}, [],
    async () => (await req("/state")).replace(/"KEY_[A-Z0-9_]*":(true|false),?/g, "")),
  tool("mc_screenshot", "Screenshot of the game window as an image.", { width: { ...int, description: "default 960" } }, [],
    async ({ width }) => {
      const res = await req(`/screenshot?${q({ w: width || 960 })}`, { raw: true });
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
      return { image: Buffer.from(await res.arrayBuffer()).toString("base64"), mimeType: "image/jpeg" };
    }),
  tool("mc_chat", "Recent chat lines. Pass the last seen 'seq' as since to get only new ones.", { since: int }, [],
    ({ since }) => req(`/chat?${q({ since: since || 0 })}`)),
  tool("mc_say", "Send a chat message or /command.", { text: str }, ["text"], ({ text }) => req("/say", { body: text })),
  tool("mc_run", "Run a Macro-Mod script, e.g. look(90,0); keydown(forward); wait(20t); keyup(forward). Script look yaw: 0=north 90=east. Keys: forward back left right jump sneak attack use inventory drop chat pick. gui() closes screen, slot(1-9), slotclick(n,l|r,shift).",
    { script: str }, ["script"], ({ script }) => req("/run", { body: script })),
  tool("mc_stop", "Abort all scripts/walking and release all keys.", {}, [], () => req("/stop", { method: "POST" })),
  tool("mc_close", "Safely close an open game window (never presses Escape).", {}, [], () => req("/close", { method: "POST" })),
  tool("mc_wait", "Wait until scripts, walking and computer typing have finished (max ~150 s).", {}, [], async () => {
    for (let i = 0; i < 600; i++) {
      const s = await req("/state");
      if (s.includes('"macrosRunning":0') && !s.includes('"walk":"walking"') && !/"ccPending":[1-9]/.test(s) && !/"actPending":[1-9]/.test(s))
        return "idle " + (s.match(/"walk":"[^"]*"/)?.[0] || "");
      await sleep(250);
    }
    return "still busy";
  }),
  tool("mc_goto", "Walk straight to x/z (auto-jumps one-block steps, no pathfinding). Use mc_wait afterwards.",
    { x: num, z: num, tolerance: { ...num, description: "default 0.4" } }, ["x", "z"],
    ({ x, z, tolerance }) => req(`/goto?${q({ x, z, tol: tolerance ?? 0.4 })}`, { method: "POST" })),
  tool("mc_look", "Look at block x y z, or set yaw/pitch (yaw 0=north 90=east).",
    { ...xyz, yaw: num, pitch: num }, [],
    ({ x, y, z, yaw, pitch }) => req(`/look?${yaw !== undefined ? q({ yaw, pitch: pitch ?? 0 }) : q({ x, y, z })}`, { method: "POST" })),
  tool("mc_blocks", "Block ids around the player as layers.", { r: { ...int, description: "default 4" }, ry: { ...int, description: "default 2" } }, [],
    ({ r, ry }) => req(`/blocks?${q({ r: r ?? 4, ry: ry ?? 2 })}`)),
  tool("mc_area", "Blocks in a box from the client's loaded chunks. list=true gives only non-air 'x y z id:meta'.",
    { x1: int, y1: int, z1: int, x2: int, y2: int, z2: int, list: { type: "boolean" } }, ["x1", "y1", "z1", "x2", "y2", "z2"],
    (a) => req(`/area?${q({ ...a, list: a.list ? 1 : "" })}`)),
  tool("mc_scan", "Non-air blocks in a box around the player (default r=8, 8 down, 8 up).", { r: int, down: int, up: int }, [],
    async ({ r = 8, down = 8, up = 8 }) => {
      const [px, py_, pz] = JSON.parse(await req("/state")).pos.map(Math.floor);
      return req(`/area?${q({ x1: px - r, y1: py_ - down, z1: pz - r, x2: px + r, y2: py_ + up, z2: pz + r, list: 1 })}`);
    }),
  tool("mc_entities", "Players and mobs nearby.", { r: { ...int, description: "default 16" } }, [], ({ r }) => req(`/entities?${q({ r: r ?? 16 })}`)),
  tool("mc_place", "Place a block (item from the hotbar; default the held item). Reach about 4.5 blocks.",
    { ...xyz, block: { ...str, description: "id or id:meta" } }, ["x", "y", "z"],
    ({ x, y, z, block }) => req(`/place?${q({ x, y, z, ...idMeta(block) })}`, { method: "POST" })),
  tool("mc_build", "Place many blocks: one 'x y z [id[:meta]]' per line, bottom-up, retries unsupported ones. Then mc_wait and mc_state (placed/skipped/failures).",
    { lines: str, block: { ...str, description: "default id[:meta] for lines without one" } }, ["lines"],
    ({ lines, block }) => req(`/place?${q(idMeta(block))}`, { body: lines })),
  tool("mc_dig", "Break a block.", xyz, ["x", "y", "z"], ({ x, y, z }) => req(`/dig?${q({ x, y, z })}`, { method: "POST" })),
  tool("mc_use", "Right-click a block (open chest, press button, open computer...).", { ...xyz, side: int }, ["x", "y", "z"],
    ({ x, y, z, side }) => req(`/use?${q({ x, y, z, side })}`, { method: "POST" })),
  tool("mc_select", "Choose a hotbar slot (0-8) or the slot holding an item id[:meta].", { slot: int, item: str }, [],
    ({ slot, item }) => req(`/select?${q(item ? idMeta(item) : { slot })}`, { method: "POST" })),

  tool("cc_screen", "ComputerCraft: text of the open computer/turtle screen plus id, on/off, cursor.", {}, [], async () => {
    const d = JSON.parse(await req("/cc")); const lines = d.lines || []; delete d.lines;
    return JSON.stringify(d) + "\n" + lines.map((l) => "|" + l).join("\n");
  }),
  tool("cc_type", "ComputerCraft: type text; enter=true (default) presses Enter after every line.",
    { text: str, enter: { type: "boolean" } }, ["text"],
    ({ text, enter }) => req(`/cc/type${enter === false ? "?enter=0" : ""}`, { body: text })),
  tool("cc_key", "ComputerCraft: press a key n times (enter up down left right tab backspace delete home end ctrl pageup pagedown, or a keycode).",
    { key: str, times: int }, ["key"],
    ({ key, times }) => req(`/cc/key?${q({ code: KEYS[key] ?? key, times: times || 1 })}`, { method: "POST" })),
  tool("cc_power", "ComputerCraft: terminate (Ctrl+T), reboot, shutdown or on.",
    { action: { type: "string", enum: ["terminate", "reboot", "shutdown", "on"] } }, ["action"],
    ({ action }) => req(`/cc/${action}`, { method: "POST" })),
  tool("cc_install", "ComputerCraft: one-time per computer, type the transfer helper `cb` into it (needs the shell prompt).", {}, [],
    () => py("install")),
  tool("cc_get", "ComputerCraft: download a file from the open computer (checksum-verified). Returns its content, or saves to localPath.",
    { name: str, localPath: str }, ["name"], ({ name, localPath }) => py("get", name, ...(localPath ? [localPath] : []))),
  tool("cc_put", "ComputerCraft: upload a local file to the open computer as name (checksum-verified, replaces it).",
    { localPath: str, name: str }, ["localPath", "name"], ({ localPath, name }) => py("put", localPath, name)),
];

// --- minimal MCP over stdio (newline-delimited JSON-RPC) ---
const send = (m) => process.stdout.write(JSON.stringify(m) + "\n");
createInterface({ input: process.stdin }).on("line", async (line) => {
  if (!line.trim()) return;
  let m; try { m = JSON.parse(line); } catch { return; }
  const { id, method, params } = m;
  if (id === undefined) return; // notification
  const ok = (result) => send({ jsonrpc: "2.0", id, result });
  try {
    if (method === "initialize") return ok({ protocolVersion: params?.protocolVersion || "2025-06-18",
      capabilities: { tools: {} }, serverInfo: { name: "tekkit-lite-mcp", version: "1.0.0" },
      instructions: "Controls the user's running Minecraft 1.4.7 client. Start with mc_state. After mc_goto/mc_run/mc_build/cc_type call mc_wait. ComputerCraft tools act on the computer screen currently open in game (open it with mc_use on the computer)." });
    if (method === "ping") return ok({});
    if (method === "tools/list") return ok({ tools: TOOLS.map(({ run, ...t }) => t) });
    if (method === "tools/call") {
      const t = TOOLS.find((t) => t.name === params.name);
      if (!t) return send({ jsonrpc: "2.0", id, error: { code: -32602, message: "unknown tool " + params.name } });
      try {
        const r = await t.run(params.arguments || {});
        return ok({ content: [typeof r === "object" ? { type: "image", data: r.image, mimeType: r.mimeType } : { type: "text", text: String(r) || "ok" }] });
      } catch (e) { return ok({ content: [{ type: "text", text: "Error: " + e.message }], isError: true }); }
    }
    send({ jsonrpc: "2.0", id, error: { code: -32601, message: "method not found" } });
  } catch (e) { send({ jsonrpc: "2.0", id, error: { code: -32603, message: e.message } }); }
});
