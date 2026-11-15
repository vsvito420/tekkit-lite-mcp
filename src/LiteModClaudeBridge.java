
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import com.mumfrey.liteloader.ChatListener;
import com.mumfrey.liteloader.Tickable;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.eq2online.macros.compatibility.AbstractionLayer;
import net.eq2online.macros.core.MacroModCore;
import net.eq2online.macros.core.MacroTemplate;
import net.eq2online.macros.core.Macros;
import net.eq2online.macros.gui.helpers.HelperContainerSlots;
import net.eq2online.macros.scripting.ScriptCore;
import net.eq2online.macros.scripting.api.IVariableListener;
import net.minecraft.client.Minecraft;

/**
 * Claude Bridge for Minecraft 1.4.7 (LiteLoader).
 *
 * Opens an HTTP server on 127.0.0.1 so an outside program can read the game state and drive the player.
 * Actions are run through the Macro / Keybind Mod's script engine (must be installed), everything else
 * talks to the (obfuscated) 1.4.7 client classes directly:
 *   ays = EntityClientPlayerMP, ayp = WorldClient, ur = ItemStack, qw = InventoryPlayer,
 *   ast = GameSettings, arv = KeyBinding, avf = GuiContainer, rq = Container, sr = Slot.
 *
 * Every request needs the token from <minecraft dir>/claudebridge.token (header X-Token or ?token=).
 */
public class LiteModClaudeBridge implements Tickable, ChatListener {
    public static final int PORT = 25599;
    private static final int CHAT_KEEP = 300;

    private final ConcurrentLinkedQueue<FutureTask<?>> mainThreadTasks = new ConcurrentLinkedQueue<FutureTask<?>>();
    private final List<String> chat = new ArrayList<String>();
    private long chatSeq = 0;
    private String token = "";
    private String startError = null;

    // walker state (main thread only)
    private boolean walking = false;
    private double goalX, goalZ, tolerance;
    private int walkTicks, stuckTicks, maxWalkTicks;
    private double lastX, lastZ;
    private String walkResult = "idle";

    // pending ComputerCraft input (main thread only): String = text to type, int[]{char, keycode} = key press
    private final List<Object> ccQueue = new ArrayList<Object>();
    private String ccError = null;

    // world actions (main thread only): queued block placements {x,y,z,itemId,meta,tries} and one dig at a time
    private final List<int[]> placeQueue = new ArrayList<int[]>();
    private int placeDelay = 2, cooldown = 0, placedCount = 0, skippedCount = 0;
    private final List<String> failures = new ArrayList<String>();
    private int[] digTarget = null; // x,y,z,side
    private int digTicks = 0;
    private String digResult = "idle";

    public String getName() { return "Claude Bridge"; }
    public String getVersion() { return "0.3"; }

    public void init() {
        try {
            File dir = AbstractionLayer.GetMinecraftDir();
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            token = sb.toString();
            FileWriter fw = new FileWriter(new File(dir, "claudebridge.token"));
            fw.write(token);
            fw.close();

            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 0);
            server.createContext("/", new HttpHandler() {
                public void handle(HttpExchange ex) { serve(ex); }
            });
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            System.out.println("[ClaudeBridge] listening on 127.0.0.1:" + PORT);
        } catch (Throwable t) {
            startError = t.toString();
            System.out.println("[ClaudeBridge] failed to start: " + t);
        }
    }

    // ------------------------------------------------------------------ game thread

    public void onTick(Minecraft mc, float partialTicks, boolean inGame, boolean clock) {
        FutureTask<?> task;
        while ((task = mainThreadTasks.poll()) != null) task.run();
        if (clock && walking) {
            try { walkTick(); } catch (Throwable t) { stopWalking("error: " + t); }
        }
        if (clock && (digTarget != null || !placeQueue.isEmpty())) {
            try { actionTick(); } catch (Throwable t) { placeQueue.clear(); digTarget = null; fail("error: " + t); }
        }
        if (clock && !ccQueue.isEmpty()) {
            try {
                // two inputs per tick keeps the computer's event queue from overflowing
                for (int i = 0; i < 2 && !ccQueue.isEmpty(); i++) CC.send(ccQueue.remove(0));
            } catch (Throwable t) {
                ccQueue.clear();
                ccError = String.valueOf(t);
            }
        }
    }

    public void onChat(String message) {
        synchronized (chat) {
            chat.add(message.replaceAll("§.", ""));
            chatSeq++;
            while (chat.size() > CHAT_KEEP) chat.remove(0);
        }
    }

    private void walkTick() {
        ays p = AbstractionLayer.GetPlayer();
        if (p == null) { stopWalking("no player"); return; }
        double dx = goalX - p.t, dz = goalZ - p.v;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= tolerance) { stopWalking("arrived"); return; }
        if (++walkTicks > maxWalkTicks) { stopWalking("timeout"); return; }

        p.z = (float) Math.toDegrees(Math.atan2(-dx, dz));
        ast gs = AbstractionLayer.GetGameSettings();
        arv.a(gs.E.d, true);
        arv.a(gs.M.d, dist < 1.2); // sneak for the last block so momentum does not carry past the goal

        boolean jump = false;
        if (walkTicks % 5 == 0) {
            double moved = Math.abs(p.t - lastX) + Math.abs(p.v - lastZ);
            lastX = p.t; lastZ = p.v;
            if (moved < 0.15) {
                stuckTicks += 5;
                jump = true;
                if (stuckTicks > 100) { stopWalking("stuck"); return; }
            } else {
                stuckTicks = 0;
            }
        } else if (stuckTicks > 0) {
            jump = true;
        }
        arv.a(gs.I.d, jump);
    }

    private void stopWalking(String result) {
        walking = false;
        walkResult = result;
        ast gs = AbstractionLayer.GetGameSettings();
        arv.a(gs.E.d, false);
        arv.a(gs.I.d, false);
        arv.a(gs.M.d, false);
    }

    // ------------------------------------------------------------------ world actions (game thread)

    // side n: 0=down 1=up 2=north(-z) 3=south(+z) 4=west(-x) 5=east(+x)
    private static final int[][] SIDE = { {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}, {-1,0,0}, {1,0,0} };
    private static final int[] SUPPORT_ORDER = { 1, 2, 3, 4, 5, 0 };
    private static final double REACH = 4.5;

    private static boolean replaceable(int id) { return id == 0 || (id >= 8 && id <= 11) || id == 31 || id == 32 || id == 78 || id == 106; }

    private void fail(String msg) {
        failures.add(msg);
        while (failures.size() > 50) failures.remove(0);
    }

    private static double eyeDist(ays p, double x, double y, double z) {
        double dx = x - p.t, dy = y - (p.D.b + 1.62), dz = z - p.v;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void lookAt(ays p, double x, double y, double z) {
        double dx = x - p.t, dy = y - (p.D.b + 1.62), dz = z - p.v;
        p.z = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.A = (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
    }

    /** Hotbar slot holding the item, or -1. id -1 = keep current. meta -1 = any. */
    private static int findHotbar(ays p, int id, int meta) {
        if (id < 0) return p.bJ.c;
        for (int i = 0; i < 9; i++) {
            ur s = p.bJ.a[i];
            if (s != null && s.c == id && (meta < 0 || s.j() == meta)) return i;
        }
        return -1;
    }

    /** Side of block (x,y,z) facing the player's eye. */
    private static int faceToward(ays p, int x, int y, int z) {
        double dx = p.t - (x + 0.5), dy = p.D.b + 1.62 - (y + 0.5), dz = p.v - (z + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? 1 : 0;
        if (ax >= az) return dx > 0 ? 5 : 4;
        return dz > 0 ? 3 : 2;
    }

    /** Right-clicks face `side` of block (x,y,z) with the selected item. */
    private boolean rightClick(ays p, int x, int y, int z, int side) {
        Minecraft mc = AbstractionLayer.GetMinecraftInstance();
        int[] o = SIDE[side];
        double hx = x + 0.5 + o[0] * 0.5, hy = y + 0.5 + o[1] * 0.5, hz = z + 0.5 + o[2] * 0.5;
        lookAt(p, hx, hy, hz);
        return mc.b.a(p, mc.e, p.bJ.g(), x, y, z, side, aoj.a(hx, hy, hz));
    }

    private void actionTick() {
        ays p = AbstractionLayer.GetPlayer();
        ayp w = AbstractionLayer.GetWorld();
        if (p == null || w == null) { placeQueue.clear(); digTarget = null; return; }
        Minecraft mc = AbstractionLayer.GetMinecraftInstance();

        if (digTarget != null) {
            int[] d = digTarget;
            if (w.a(d[0], d[1], d[2]) == 0) { digTarget = null; digResult = "done"; mc.b.c(); return; }
            if (++digTicks > 600) { digTarget = null; digResult = "timeout"; mc.b.c(); fail("dig timeout at " + d[0] + " " + d[1] + " " + d[2]); return; }
            lookAt(p, d[0] + 0.5, d[1] + 0.5, d[2] + 0.5);
            if (digTicks == 1) mc.b.b(d[0], d[1], d[2], d[3]);
            else mc.b.c(d[0], d[1], d[2], d[3]);
            return;
        }

        if (cooldown > 0) { cooldown--; return; }
        int[] e = placeQueue.remove(0);
        int x = e[0], y = e[1], z = e[2];
        String at = x + " " + y + " " + z;
        if (!replaceable(w.a(x, y, z))) { skippedCount++; return; }
        // the player's own body
        int fx = (int) Math.floor(p.t), fz = (int) Math.floor(p.v), fy = (int) Math.floor(p.D.b);
        if (x == fx && z == fz && (y == fy || y == fy + 1)) { fail("player in the way at " + at); return; }
        int slot = findHotbar(p, e[3], e[4]);
        if (slot < 0) { fail("item " + e[3] + " not in hotbar for " + at); return; }
        for (int s : SUPPORT_ORDER) {
            int[] o = SIDE[s];
            int nx = x - o[0], ny = y - o[1], nz = z - o[2];
            if (replaceable(w.a(nx, ny, nz))) continue;
            if (eyeDist(p, nx + 0.5 + o[0] * 0.5, ny + 0.5 + o[1] * 0.5, nz + 0.5 + o[2] * 0.5) > REACH) continue;
            p.bJ.c = slot;
            rightClick(p, nx, ny, nz, s);
            if (!replaceable(w.a(x, y, z))) placedCount++;
            else fail("place did nothing at " + at);
            cooldown = placeDelay;
            return;
        }
        // no support yet: retry after the rest of the queue, give up after a full pass without progress
        if (eyeDist(p, x + 0.5, y + 0.5, z + 0.5) > REACH + 1) { fail("out of reach: " + at); return; }
        if (++e[5] > placeQueue.size() + 1) { fail("nothing to place against at " + at); return; }
        placeQueue.add(e);
    }

    private String queuePlace(String body, int id, int meta) {
        int n = 0;
        for (String line : body.split("[\\n;]")) {
            String[] f = line.trim().split("[\\s,]+");
            if (f.length < 3 || f[0].length() == 0) continue;
            int bid = id, bmeta = meta;
            if (f.length >= 4) {
                String[] im = f[3].split(":");
                bid = Integer.parseInt(im[0]);
                bmeta = im.length > 1 ? Integer.parseInt(im[1]) : -1;
            }
            placeQueue.add(new int[] { Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), bid, bmeta, 0 });
            n++;
        }
        // bottom-up so every block has something below it
        java.util.Collections.sort(placeQueue, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[1] - b[1]; }
        });
        return "{\"ok\":true,\"queued\":" + n + ",\"pending\":" + placeQueue.size() + "}";
    }

    private String dig(int x, int y, int z) {
        ays p = AbstractionLayer.GetPlayer();
        ayp w = AbstractionLayer.GetWorld();
        if (p == null) return "{\"error\":\"not in game\"}";
        if (w.a(x, y, z) == 0) return "{\"ok\":true,\"note\":\"already air\"}";
        if (eyeDist(p, x + 0.5, y + 0.5, z + 0.5) > REACH + 0.5) return "{\"error\":\"out of reach\"}";
        digTarget = new int[] { x, y, z, faceToward(p, x, y, z) };
        digTicks = 0;
        digResult = "digging";
        return "{\"ok\":true}";
    }

    private String use(int x, int y, int z, int side) {
        ays p = AbstractionLayer.GetPlayer();
        if (p == null) return "{\"error\":\"not in game\"}";
        if (side < 0) side = faceToward(p, x, y, z);
        if (eyeDist(p, x + 0.5, y + 0.5, z + 0.5) > REACH + 0.5) return "{\"error\":\"out of reach\"}";
        return "{\"ok\":true,\"result\":" + rightClick(p, x, y, z, side) + "}";
    }

    private String select(int slot, int id, int meta) {
        ays p = AbstractionLayer.GetPlayer();
        if (p == null) return "{\"error\":\"not in game\"}";
        if (id >= 0) slot = findHotbar(p, id, meta);
        if (slot < 0 || slot > 8) return "{\"error\":\"not in hotbar\"}";
        p.bJ.c = slot;
        return "{\"ok\":true,\"slot\":" + slot + "}";
    }

    private String area(int x1, int y1, int z1, int x2, int y2, int z2, boolean list) {
        ayp w = AbstractionLayer.GetWorld();
        if (w == null) return "{\"error\":\"not in game\"}";
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        long vol = (long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1);
        if (vol > 140000) return "{\"error\":\"area too big (max 140000 blocks)\"}";
        StringBuilder j = new StringBuilder("{\"from\":[" + ax + "," + ay + "," + az + "],\"to\":[" + bx + "," + by + "," + bz + "]");
        if (list) {
            j.append(",\"note\":\"non-air blocks as x y z id[:meta]\",\"blocks\":[");
            boolean first = true;
            for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) for (int x = ax; x <= bx; x++) {
                int id = w.a(x, y, z);
                if (id == 0) continue;
                int meta = w.h(x, y, z);
                if (!first) j.append(',');
                first = false;
                j.append('"').append(x).append(' ').append(y).append(' ').append(z).append(' ').append(id);
                if (meta != 0) j.append(':').append(meta);
                j.append('"');
            }
            return j.append("]}").toString();
        }
        j.append(",\"note\":\"layers keyed by y (top first); rows z north->south, cells x west->east; id or id:meta\",\"layers\":{");
        for (int y = by; y >= ay; y--) {
            j.append('"').append(y).append("\":[");
            for (int z = az; z <= bz; z++) {
                j.append('"');
                for (int x = ax; x <= bx; x++) {
                    int id = w.a(x, y, z);
                    j.append(id);
                    if (id != 0) { int m = w.h(x, y, z); if (m != 0) j.append(':').append(m); }
                    if (x < bx) j.append(' ');
                }
                j.append('"');
                if (z < bz) j.append(',');
            }
            j.append(']');
            if (y > ay) j.append(',');
        }
        return j.append("}}").toString();
    }

    private String entities(double r) {
        ays p = AbstractionLayer.GetPlayer();
        ayp w = AbstractionLayer.GetWorld();
        if (p == null || w == null) return "{\"error\":\"not in game\"}";
        StringBuilder j = new StringBuilder("{\"entities\":[");
        boolean first = true;
        for (Object o : new ArrayList<Object>(w.e)) {
            lq e = (lq) o;
            if (e == p) continue;
            double dx = e.t - p.t, dy = e.u - p.u, dz = e.v - p.v;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > r) continue;
            String name = "";
            try { name = e.an(); } catch (Throwable t) { }
            if (!first) j.append(',');
            first = false;
            j.append("{\"name\":").append(q(name)).append(",\"player\":").append(e instanceof qx)
             .append(",\"pos\":[").append(r2(e.t)).append(',').append(r2(e.u)).append(',').append(r2(e.v)).append("],\"dist\":").append(r2(d)).append('}');
        }
        return j.append("]}").toString();
    }

    private String look(Map<String, String> q) {
        ays p = AbstractionLayer.GetPlayer();
        if (p == null) return "{\"error\":\"not in game\"}";
        if (q.containsKey("x")) lookAt(p, Double.parseDouble(q.get("x")), Double.parseDouble(q.get("y")), Double.parseDouble(q.get("z")));
        else { p.z = Float.parseFloat(q.get("yaw")) + 180f; p.A = Float.parseFloat(q.get("pitch")); }
        return "{\"ok\":true}";
    }

    private void releaseAllKeys() {
        ast gs = AbstractionLayer.GetGameSettings();
        arv[] keys = { gs.E, gs.F, gs.G, gs.H, gs.I, gs.M, gs.N, gs.O };
        for (arv k : keys) arv.a(k.d, false);
    }

    // ------------------------------------------------------------------ endpoints (run on game thread)

    private String state() {
        StringBuilder j = new StringBuilder("{");
        ays p = AbstractionLayer.GetPlayer();
        j.append("\"ingame\":").append(p != null);
        if (p != null) {
            j.append(",\"pos\":[").append(r2(p.t)).append(',').append(r2(p.D.b)).append(',').append(r2(p.v)).append(']');
            float yaw = p.z % 360f;
            if (yaw < 0) yaw += 360f;
            j.append(",\"yaw\":").append(r2(yaw)).append(",\"pitch\":").append(r2(p.A));
            j.append(",\"selectedSlot\":").append(p.bJ.c);
            j.append(",\"inventory\":[");
            boolean first = true;
            for (int i = 0; i < p.bJ.a.length; i++) {
                if (p.bJ.a[i] == null) continue;
                if (!first) j.append(',');
                first = false;
                j.append(stack(i, p.bJ.a[i]));
            }
            j.append(']');
            try {
                Minecraft mc = AbstractionLayer.GetMinecraftInstance();
                if (HelperContainerSlots.CurrentScreenIsContainer(mc)) {
                    avf gui = HelperContainerSlots.GetGuiContainer(mc);
                    List slots = gui.d.c;
                    j.append(",\"container\":[");
                    first = true;
                    for (int i = 0; i < slots.size(); i++) {
                        ur s = ((sr) slots.get(i)).c();
                        if (s == null) continue;
                        if (!first) j.append(',');
                        first = false;
                        j.append(stack(i, s));
                    }
                    j.append("],\"containerSize\":").append(slots.size());
                }
            } catch (Throwable t) {
                j.append(",\"containerError\":").append(q(t.toString()));
            }
        }
        final Map<String, Object> vars = new TreeMap<String, Object>();
        try {
            ScriptCore.GetScriptActionProvider().ProvideVariables(new IVariableListener() {
                public void SetVariable(String n, boolean v) { vars.put(n, v); }
                public void SetVariable(String n, int v) { vars.put(n, v); }
                public void SetVariable(String n, String v) { vars.put(n, v); }
                public void SetVariables(Map m) { vars.putAll(m); }
            });
        } catch (Throwable t) {
            vars.put("_error", t.toString());
        }
        j.append(",\"vars\":{");
        boolean first = true;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            if (e.getKey().startsWith("KEY_")) continue; // one entry per keyboard key, pure noise
            if (!first) j.append(',');
            first = false;
            Object v = e.getValue();
            j.append(q(e.getKey())).append(':');
            if (v instanceof Integer || v instanceof Boolean) j.append(v); else j.append(q(String.valueOf(v)));
        }
        j.append('}');
        int running = 0;
        try { running = MacroModCore.GetMacroManager().GetExecutingMacroCount(); } catch (Throwable t) { }
        j.append(",\"macrosRunning\":").append(running);
        j.append(",\"walk\":").append(q(walking ? "walking" : walkResult));
        j.append(",\"ccPending\":").append(ccQueue.size());
        j.append(",\"actPending\":").append(placeQueue.size() + (digTarget != null ? 1 : 0));
        j.append(",\"placed\":").append(placedCount).append(",\"skipped\":").append(skippedCount).append(",\"dig\":").append(q(digResult));
        j.append(",\"failures\":[");
        for (int i = 0; i < failures.size(); i++) { if (i > 0) j.append(','); j.append(q(failures.get(i))); }
        j.append(']');
        synchronized (chat) { j.append(",\"chatSeq\":").append(chatSeq); }
        return j.append('}').toString();
    }

    private static String stack(int slot, ur s) {
        String name = "";
        try { name = s.r(); } catch (Throwable t) { }
        return "{\"slot\":" + slot + ",\"id\":" + s.c + ",\"meta\":" + s.j() + ",\"count\":" + s.a + ",\"name\":" + q(name) + "}";
    }

    private String blocks(int r, int ry) {
        ays p = AbstractionLayer.GetPlayer();
        ayp w = AbstractionLayer.GetWorld();
        if (p == null || w == null) return "{\"error\":\"not in game\"}";
        int px = (int) Math.floor(p.t), py = (int) Math.floor(p.D.b), pz = (int) Math.floor(p.v);
        StringBuilder j = new StringBuilder();
        j.append("{\"feet\":[").append(px).append(',').append(py).append(',').append(pz).append("],\"r\":").append(r);
        j.append(",\"note\":\"layers keyed by y; rows run z from -r to +r (north to south), cells x from -r to +r (west to east); id or id:meta\"");
        j.append(",\"layers\":{");
        for (int y = py + ry; y >= py - ry; y--) {
            j.append('"').append(y).append("\":[");
            for (int z = pz - r; z <= pz + r; z++) {
                j.append('"');
                for (int x = px - r; x <= px + r; x++) {
                    int id = w.a(x, y, z);
                    int meta = id == 0 ? 0 : w.h(x, y, z);
                    j.append(id);
                    if (meta != 0) j.append(':').append(meta);
                    if (x < px + r) j.append(' ');
                }
                j.append('"');
                if (z < pz + r) j.append(',');
            }
            j.append(']');
            if (y > py - ry) j.append(',');
        }
        return j.append("}}").toString();
    }

    private String run(String script) {
        Macros m = MacroModCore.GetMacroManager();
        MacroTemplate t = m.CreateLooseTemplate("$${" + script + "}$$", "claudebridge");
        if (t == null) return "{\"error\":\"no free macro slot\"}";
        m.PlayMacro(t, false, null);
        return "{\"ok\":true,\"macrosRunning\":" + m.GetExecutingMacroCount() + "}";
    }

    private String stop() {
        MacroModCore.GetMacroManager().TerminateActiveMacros();
        if (walking) stopWalking("stopped");
        ccQueue.clear();
        placeQueue.clear();
        if (digTarget != null) { digTarget = null; digResult = "stopped"; try { AbstractionLayer.GetMinecraftInstance().b.c(); } catch (Throwable t) { } }
        releaseAllKeys();
        return "{\"ok\":true}";
    }

    /**
     * Closes the current GUI directly. Unlike the Macro Mod's gui()/Escape key,
     * this cannot open the pause screen when the target GUI has already closed.
     */
    private String closeScreen() {
        Minecraft mc = AbstractionLayer.GetMinecraftInstance();
        if (mc == null) return "{\"error\":\"minecraft unavailable\"}";
        if (mc.r == null) return "{\"ok\":true,\"closed\":false}";
        mc.a((aul) null);
        ccQueue.clear();
        return "{\"ok\":true,\"closed\":true}";
    }

    private String walkTo(double x, double z, double tol, int timeoutTicks) {
        ays p = AbstractionLayer.GetPlayer();
        if (p == null) return "{\"error\":\"not in game\"}";
        goalX = x; goalZ = z; tolerance = tol; maxWalkTicks = timeoutTicks;
        walkTicks = 0; stuckTicks = 0; lastX = p.t; lastZ = p.v;
        walking = true;
        return "{\"ok\":true}";
    }

    /**
     * ComputerCraft 1.5 access. Works on whatever computer or turtle screen is currently open in the game.
     * Kept in its own class so the bridge still loads when ComputerCraft is not installed.
     */
    static class CC {
        static dan200.computer.shared.IComputerEntity open() throws Exception {
            Object gui = AbstractionLayer.GetMinecraftInstance().r;
            if (gui == null) return null;
            for (Class c = gui.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("m_terminal");
                    f.setAccessible(true);
                    Object v = f.get(gui);
                    if (v instanceof dan200.computer.shared.IComputerEntity) return (dan200.computer.shared.IComputerEntity) v;
                } catch (NoSuchFieldException e) { }
            }
            return null;
        }

        static void send(Object input) throws Exception {
            dan200.computer.shared.IComputerEntity c = open();
            if (c == null) throw new IllegalStateException("computer screen was closed");
            if (input instanceof String) c.typeString((String) input);
            else { int[] k = (int[]) input; c.pressKey((char) k[0], k[1]); }
        }

        static String screen() throws Exception {
            dan200.computer.shared.IComputerEntity c = open();
            if (c == null) return "{\"open\":false}";
            dan200.computer.core.Terminal t = c.getTerminal();
            StringBuilder j = new StringBuilder("{\"open\":true");
            j.append(",\"id\":").append(c.getComputerID()).append(",\"on\":").append(c.isOn()).append(",\"colour\":").append(c.isColour());
            j.append(",\"size\":[").append(t.getWidth()).append(',').append(t.getHeight()).append(']');
            j.append(",\"cursor\":[").append(t.getCursorX()).append(',').append(t.getCursorY()).append(']');
            j.append(",\"lines\":[");
            for (int y = 0; y < t.getHeight(); y++) {
                String line = t.getLine(y);
                int end = line.length();
                while (end > 0 && line.charAt(end - 1) == ' ') end--;
                if (y > 0) j.append(',');
                j.append(q(line.substring(0, end)));
            }
            return j.append("]}").toString();
        }

        static String power(String what) throws Exception {
            dan200.computer.shared.IComputerEntity c = open();
            if (c == null) return "{\"error\":\"no computer screen open\"}";
            if (what.equals("terminate")) c.terminate();
            else if (what.equals("reboot")) c.reboot();
            else if (what.equals("shutdown")) c.shutdown();
            else if (what.equals("on")) c.turnOn();
            else return "{\"error\":\"unknown action\"}";
            return "{\"ok\":true}";
        }
    }

    /** Queues text for the open computer: each line is typed, then Enter is pressed (unless enter is false). */
    private String ccType(String text, boolean enter) throws Exception {
        if (CC.open() == null) return "{\"error\":\"no computer screen open\"}";
        ccError = null;
        String[] lines = text.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (int o = 0; o < line.length(); o += 100) ccQueue.add(line.substring(o, Math.min(line.length(), o + 100)));
            if (enter || i < lines.length - 1) ccQueue.add(new int[] { 13, 28 });
        }
        return "{\"ok\":true,\"ccPending\":" + ccQueue.size() + "}";
    }

    private String ccKey(int ch, int code, int times) throws Exception {
        if (CC.open() == null) return "{\"error\":\"no computer screen open\"}";
        ccError = null;
        for (int i = 0; i < times; i++) ccQueue.add(new int[] { ch, code });
        return "{\"ok\":true,\"ccPending\":" + ccQueue.size() + "}";
    }

    /** Raw RGB frame, bottom row first. */
    private Object[] grabFrame() {
        int w = Display.getWidth(), h = Display.getHeight();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 3);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
        byte[] data = new byte[w * h * 3];
        buf.get(data);
        return new Object[] { w, h, data };
    }

    // ------------------------------------------------------------------ http thread

    private <T> T onGameThread(Callable<T> c) throws Exception {
        FutureTask<T> f = new FutureTask<T>(c);
        mainThreadTasks.add(f);
        return f.get(5, TimeUnit.SECONDS);
    }

    private void serve(HttpExchange ex) {
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String path = ex.getRequestURI().getPath();
            String given = ex.getRequestHeaders().getFirst("X-Token");
            if (given == null) given = q.get("token");
            if (!token.equals(given)) { send(ex, 403, "application/json", "{\"error\":\"bad token\"}".getBytes("UTF-8")); return; }

            final String body = readBody(ex);
            String out;
            if (path.equals("/state")) {
                out = onGameThread(new Callable<String>() { public String call() { return state(); } });
            } else if (path.equals("/run")) {
                out = onGameThread(new Callable<String>() { public String call() { return run(body); } });
            } else if (path.equals("/stop")) {
                out = onGameThread(new Callable<String>() { public String call() { return stop(); } });
            } else if (path.equals("/close")) {
                out = onGameThread(new Callable<String>() { public String call() { return closeScreen(); } });
            } else if (path.equals("/say")) {
                out = onGameThread(new Callable<String>() { public String call() {
                    MacroModCore.GetMacroManager().DispatchChatMessage(body.trim());
                    return "{\"ok\":true}";
                } });
            } else if (path.equals("/goto")) {
                final double x = Double.parseDouble(q.get("x")), z = Double.parseDouble(q.get("z"));
                final double tol = q.containsKey("tol") ? Double.parseDouble(q.get("tol")) : 0.4;
                final int timeout = q.containsKey("timeout") ? Integer.parseInt(q.get("timeout")) : 1200;
                out = onGameThread(new Callable<String>() { public String call() { return walkTo(x, z, tol, timeout); } });
            } else if (path.equals("/blocks")) {
                final int r = Math.min(16, q.containsKey("r") ? Integer.parseInt(q.get("r")) : 4);
                final int ry = Math.min(8, q.containsKey("ry") ? Integer.parseInt(q.get("ry")) : 2);
                out = onGameThread(new Callable<String>() { public String call() { return blocks(r, ry); } });
            } else if (path.equals("/area")) {
                final int x1 = Integer.parseInt(q.get("x1")), y1 = Integer.parseInt(q.get("y1")), z1 = Integer.parseInt(q.get("z1"));
                final int x2 = Integer.parseInt(q.get("x2")), y2 = Integer.parseInt(q.get("y2")), z2 = Integer.parseInt(q.get("z2"));
                final boolean list = "1".equals(q.get("list"));
                out = onGameThread(new Callable<String>() { public String call() { return area(x1, y1, z1, x2, y2, z2, list); } });
            } else if (path.equals("/place")) {
                final int id = q.containsKey("id") ? Integer.parseInt(q.get("id")) : -1;
                final int meta = q.containsKey("meta") ? Integer.parseInt(q.get("meta")) : -1;
                if (q.containsKey("delay")) placeDelay = Math.max(0, Integer.parseInt(q.get("delay")));
                final String spec = q.containsKey("x") ? q.get("x") + " " + q.get("y") + " " + q.get("z") : body;
                out = onGameThread(new Callable<String>() { public String call() {
                    if (placeQueue.isEmpty()) { placedCount = 0; skippedCount = 0; failures.clear(); }
                    return queuePlace(spec, id, meta);
                } });
            } else if (path.equals("/dig")) {
                final int x = Integer.parseInt(q.get("x")), y = Integer.parseInt(q.get("y")), z = Integer.parseInt(q.get("z"));
                out = onGameThread(new Callable<String>() { public String call() { return dig(x, y, z); } });
            } else if (path.equals("/use")) {
                final int x = Integer.parseInt(q.get("x")), y = Integer.parseInt(q.get("y")), z = Integer.parseInt(q.get("z"));
                final int side = q.containsKey("side") ? Integer.parseInt(q.get("side")) : -1;
                out = onGameThread(new Callable<String>() { public String call() { return use(x, y, z, side); } });
            } else if (path.equals("/select")) {
                final int slot = q.containsKey("slot") ? Integer.parseInt(q.get("slot")) : -1;
                final int id = q.containsKey("id") ? Integer.parseInt(q.get("id")) : -1;
                final int meta = q.containsKey("meta") ? Integer.parseInt(q.get("meta")) : -1;
                out = onGameThread(new Callable<String>() { public String call() { return select(slot, id, meta); } });
            } else if (path.equals("/look")) {
                final Map<String, String> qq = q;
                out = onGameThread(new Callable<String>() { public String call() { return look(qq); } });
            } else if (path.equals("/entities")) {
                final double r = q.containsKey("r") ? Double.parseDouble(q.get("r")) : 16;
                out = onGameThread(new Callable<String>() { public String call() { return entities(r); } });
            } else if (path.equals("/cc")) {
                out = onGameThread(new Callable<String>() { public String call() throws Exception {
                    String scr = CC.screen();
                    return ccError == null ? scr : scr.substring(0, scr.length() - 1) + ",\"inputError\":" + q(ccError) + "}";
                } });
            } else if (path.equals("/cc/type")) {
                final boolean enter = !"0".equals(q.get("enter"));
                out = onGameThread(new Callable<String>() { public String call() throws Exception { return ccType(body, enter); } });
            } else if (path.equals("/cc/key")) {
                final int code = Integer.parseInt(q.get("code"));
                final int ch = q.containsKey("char") ? Integer.parseInt(q.get("char")) : 0;
                final int times = q.containsKey("times") ? Math.min(200, Integer.parseInt(q.get("times"))) : 1;
                out = onGameThread(new Callable<String>() { public String call() throws Exception { return ccKey(ch, code, times); } });
            } else if (path.startsWith("/cc/")) {
                final String what = path.substring(4);
                out = onGameThread(new Callable<String>() { public String call() throws Exception { return CC.power(what); } });
            } else if (path.equals("/chat")) {
                long since = q.containsKey("since") ? Long.parseLong(q.get("since")) : 0;
                StringBuilder j = new StringBuilder();
                synchronized (chat) {
                    long firstSeq = chatSeq - chat.size();
                    j.append("{\"seq\":").append(chatSeq).append(",\"lines\":[");
                    boolean first = true;
                    for (int i = 0; i < chat.size(); i++) {
                        if (firstSeq + i < since) continue;
                        if (!first) j.append(',');
                        first = false;
                        j.append(q(chat.get(i)));
                    }
                    j.append("]}");
                }
                out = j.toString();
            } else if (path.equals("/screenshot")) {
                Object[] f = onGameThread(new Callable<Object[]>() { public Object[] call() { return grabFrame(); } });
                int maxW = q.containsKey("w") ? Integer.parseInt(q.get("w")) : 960;
                send(ex, 200, "image/jpeg", encode((Integer) f[0], (Integer) f[1], (byte[]) f[2], maxW));
                return;
            } else {
                out = "{\"name\":\"claudebridge\",\"version\":\"0.3\",\"endpoints\":[\"/area\",\"/place\",\"/dig\",\"/use\",\"/select\",\"/look\",\"/entities\",\"/state\",\"/run\",\"/stop\",\"/close\",\"/say\",\"/goto\",\"/blocks\",\"/chat\",\"/screenshot\",\"/cc\",\"/cc/type\",\"/cc/key\",\"/cc/terminate\",\"/cc/reboot\",\"/cc/shutdown\",\"/cc/on\"]}";
            }
            send(ex, 200, "application/json", out.getBytes("UTF-8"));
        } catch (Throwable t) {
            try { send(ex, 500, "application/json", ("{\"error\":" + q(String.valueOf(t)) + "}").getBytes("UTF-8")); } catch (Throwable t2) { }
        } finally {
            ex.close();
        }
    }

    private static byte[] encode(int w, int h, byte[] rgb, int maxW) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int o = (h - 1 - y) * w * 3;
            for (int x = 0; x < w; x++, o += 3) row[x] = ((rgb[o] & 255) << 16) | ((rgb[o + 1] & 255) << 8) | (rgb[o + 2] & 255);
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        if (w > maxW) {
            int nh = Math.max(1, h * maxW / w);
            BufferedImage small = new BufferedImage(maxW, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, maxW, nh, null);
            g.dispose();
            img = small;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bos);
        return bos.toByteArray();
    }

    private static String readBody(HttpExchange ex) throws Exception {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void send(HttpExchange ex, int code, String type, byte[] data) throws Exception {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length);
        OutputStream os = ex.getResponseBody();
        os.write(data);
        os.close();
    }

    private static Map<String, String> query(String raw) throws Exception {
        Map<String, String> m = new HashMap<String, String>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(part.substring(0, i), "UTF-8"), URLDecoder.decode(part.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    private static String r2(double d) { return String.valueOf(Math.round(d * 100.0) / 100.0); }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
