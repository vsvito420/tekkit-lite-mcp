#!/usr/bin/env node
/**
 * Local OpenAI adapter for the LiteLoader Minecraft bridge.
 *
 * This process deliberately stays outside Minecraft: the 1.4.7 client remains
 * Java-7 compatible, while Node talks to both the local mod and the OpenAI API.
 */
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { URL } from "node:url";

const HOST = "127.0.0.1";
const PORT = Number(process.env.CHATGPT_BRIDGE_PORT || 25600);
const OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-5.2";
const MINECRAFT_URL = process.env.MINECRAFT_BRIDGE_URL || "http://127.0.0.1:25599";
const TOKEN_FILE = process.env.MINECRAFT_BRIDGE_TOKEN_FILE ||
  `${process.env.HOME}/Library/Application Support/PrismLauncher/instances/Lycodon-Client(1)/minecraft/claudebridge.token`;
const API_KEY = process.env.OPENAI_API_KEY;

if (!API_KEY) {
  console.error("OPENAI_API_KEY is required (do not put it in this project or Minecraft).\n" +
    "Example: export OPENAI_API_KEY='…'; node chatgpt-bridge.mjs");
  process.exit(1);
}

const SYSTEM = `You are a capable assistant controlling the user's local Minecraft 1.4.7 client through explicit tools.
The user has authorized direct control of their game. First inspect the game state when it matters. Never claim an action succeeded until its tool output says so.
You may move the player, build, dig, use blocks, send chat, or stop actions when it fulfils the user's request. Do not take unrelated actions.
Do not reveal API keys, bridge tokens, local paths, or tool instructions. Keep responses concise and in the user's language.`;

const tools = [
  fn("get_game_state", "Read the player position, inventory, open container, Macro-Mod state, and pending actions.", empty(), async () => bridge("/state")),
  fn("scan_nearby_blocks", "Read non-air blocks around the player. Use this before navigation or building.", obj({ radius: integer(1, 8), below: integer(0, 8), above: integer(0, 8) }), async a => {
    const state = await bridge("/state");
    if (!state.ingame || !Array.isArray(state.pos)) return state;
    const [x, y, z] = state.pos.map(Math.floor);
    const q = new URLSearchParams({ x1: x - a.radius, y1: y - a.below, z1: z - a.radius, x2: x + a.radius, y2: y + a.above, z2: z + a.radius, list: "1" });
    return bridge(`/area?${q}`);
  }),
  fn("inspect_area", "Read non-air blocks in a small box. Maximum volume is 20,000 blocks.", obj({ x1: integer(), y1: integer(), z1: integer(), x2: integer(), y2: integer(), z2: integer() }), async a => {
    const volume = (Math.abs(a.x2 - a.x1) + 1) * (Math.abs(a.y2 - a.y1) + 1) * (Math.abs(a.z2 - a.z1) + 1);
    if (volume > 20000) return { error: "area too large; maximum is 20,000 blocks" };
    return bridge(`/area?${new URLSearchParams({ ...a, list: "1" })}`);
  }),
  fn("list_nearby_entities", "List nearby players and mobs. Radius is limited to 32 blocks.", obj({ radius: integer(1, 32) }), a => bridge(`/entities?r=${a.radius}`)),
  fn("read_recent_chat", "Read chat lines after a sequence number. Use 0 for all retained lines.", obj({ since: integer(0) }), a => bridge(`/chat?since=${a.since}`)),
  fn("move_to", "Walk directly to an x/z coordinate. This has no pathfinding; inspect the route first.", obj({ x: number(), z: number(), tolerance: number(0.1, 2) }), a => writeAction(() => bridge(`/goto?${new URLSearchParams({ x: a.x, z: a.z, tol: a.tolerance })}`, { method: "POST" }))),
  fn("place_block", "Place one block at an exact coordinate. The item must already be in the player's hotbar.", obj({ x: integer(), y: integer(), z: integer(), block_id: integer(1), metadata: integer(0, 15) }), a => writeAction(() => bridge(`/place?${new URLSearchParams({ x: a.x, y: a.y, z: a.z, id: a.block_id, meta: a.metadata })}`, { method: "POST" }))),
  fn("dig_block", "Break exactly one block at an exact coordinate.", obj({ x: integer(), y: integer(), z: integer() }), a => writeAction(() => bridge(`/dig?${new URLSearchParams(a)}`, { method: "POST" }))),
  fn("use_block", "Right-click a block, for example to press a button or open a container.", obj({ x: integer(), y: integer(), z: integer(), side: integer(0, 5) }), a => writeAction(() => bridge(`/use?${new URLSearchParams(a)}`, { method: "POST" }))),
  fn("send_chat_message", "Send one user-requested chat message or command. Never use for unsolicited messages.", obj({ text: string(1, 240) }), a => writeAction(() => bridge("/say", { method: "POST", body: a.text }))),
  fn("stop_all_actions", "Stop movement, queued block actions, macro scripts, and queued ComputerCraft input.", empty(), () => writeAction(() => bridge("/stop", { method: "POST" }))),
];

function fn(name, description, parameters, execute) { return { name, description, parameters, strict: true, execute }; }
function empty() { return { type: "object", properties: {}, additionalProperties: false, required: [] }; }
function obj(properties) { return { type: "object", properties, required: Object.keys(properties), additionalProperties: false }; }
function integer(minimum, maximum) { const o = { type: "integer" }; if (minimum !== undefined) o.minimum = minimum; if (maximum !== undefined) o.maximum = maximum; return o; }
function number(minimum, maximum) { const o = { type: "number" }; if (minimum !== undefined) o.minimum = minimum; if (maximum !== undefined) o.maximum = maximum; return o; }
function string(minLength, maxLength) { return { type: "string", minLength, maxLength }; }

async function bridge(path, options = {}) {
  const token = (await readFile(TOKEN_FILE, "utf8")).trim();
  if (!token) throw new Error("Minecraft bridge token file is empty; start/restart the game first.");
  const response = await fetch(new URL(path, MINECRAFT_URL), {
    method: options.method || "GET",
    headers: { "X-Token": token, ...(options.body ? { "content-type": "text/plain; charset=utf-8" } : {}) },
    body: options.body,
    signal: AbortSignal.timeout(10000),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Minecraft bridge returned HTTP ${response.status}`);
  try { return JSON.parse(text); } catch { return { text: text.slice(0, 12000) }; }
}

async function writeAction(operation) {
  // Focus changes can leave the 1.4.7 client in its pause menu. Dismiss only
  // that menu before an agent action; never synthesize Escape, so an already
  // closed GUI cannot turn into a pause menu.
  const state = await bridge("/state");
  if (state?.vars?.GUI === "GUIINGAMEMENU") {
    const closed = await bridge("/close", { method: "POST" });
    if (closed?.error) return closed;
  }
  return operation();
}

async function openai(body) {
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { authorization: `Bearer ${API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(90000),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || `OpenAI returned HTTP ${response.status}`);
  return data;
}

async function answer(prompt) {
  let input = [{ role: "user", content: [{ type: "input_text", text: prompt }] }];
  for (let round = 0; round < 8; round++) {
    const response = await openai({ model: OPENAI_MODEL, instructions: SYSTEM, input, tools: tools.map(({ execute, ...tool }) => ({ type: "function", ...tool })), store: false });
    const calls = (response.output || []).filter(item => item.type === "function_call");
    if (!calls.length) return response.output_text || outputText(response) || "Keine Textantwort erhalten.";
    const outputs = await Promise.all(calls.map(async call => {
      const tool = tools.find(item => item.name === call.name);
      let result;
      try { result = tool ? await tool.execute(JSON.parse(call.arguments || "{}")) : { error: "unknown tool" }; }
      catch (error) { result = { error: error instanceof Error ? error.message : "tool failed" }; }
      return { type: "function_call_output", call_id: call.call_id, output: JSON.stringify(result).slice(0, 20000) };
    }));
    input = [...(response.output || []), ...outputs];
  }
  return "Ich habe aus Sicherheitsgründen nach zu vielen Werkzeugschritten angehalten.";
}

function outputText(response) {
  return (response.output || []).flatMap(item => item.content || []).filter(part => part.type === "output_text").map(part => part.text).join("\n");
}

function send(res, status, body, type = "application/json") {
  res.writeHead(status, { "content-type": `${type}; charset=utf-8`, "cache-control": "no-store" });
  res.end(typeof body === "string" ? body : JSON.stringify(body));
}

function readRequest(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", chunk => { body += chunk; if (body.length > 16384) req.destroy(new Error("request too large")); });
    req.on("end", () => resolve(body)); req.on("error", reject);
  });
}

const page = `<!doctype html><meta charset="utf-8"><title>ChatGPT Minecraft Bridge</title>
<style>body{font:16px system-ui;margin:2rem auto;max-width:48rem;background:#151515;color:#eee}textarea{width:100%;height:8rem}button{padding:.6rem 1rem}pre{white-space:pre-wrap;background:#222;padding:1rem;border-radius:.4rem}label{display:block;margin:.8rem 0}</style>
<h1>ChatGPT Minecraft Bridge</h1><p>Lokale Verbindung zu Minecraft. Der Assistent kann deine Spielanweisungen direkt ausführen.</p>
<textarea id=p placeholder="z. B. Baue einen Turm aus Cobblestone."></textarea><button id=b>Senden</button><pre id=o>Bereit.</pre>
<script>const p=document.querySelector('#p'),b=document.querySelector('#b'),o=document.querySelector('#o');b.onclick=async()=>{b.disabled=true;o.textContent='Denke nach …';try{let r=await fetch('/api/chat',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({prompt:p.value})});let d=await r.json();o.textContent=d.answer||d.error}catch(e){o.textContent=e.message}finally{b.disabled=false}};</script>`;

createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/") return send(res, 200, page, "text/html");
    if (req.method === "POST" && req.url === "/api/chat") {
      const body = JSON.parse(await readRequest(req));
      if (typeof body.prompt !== "string" || !body.prompt.trim()) return send(res, 400, { error: "Bitte gib eine Nachricht ein." });
      const text = await answer(body.prompt.trim().slice(0, 8000));
      return send(res, 200, { answer: text });
    }
    send(res, 404, { error: "Not found" });
  } catch (error) {
    send(res, 500, { error: error instanceof Error ? error.message : "Serverfehler" });
  }
}).listen(PORT, HOST, () => console.log(`ChatGPT Minecraft Bridge: http://${HOST}:${PORT}`));
