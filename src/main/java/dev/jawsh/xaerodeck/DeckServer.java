package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DeckServer {
    private static final int MAX_STREAMS = 4;
    private final HttpServer http;
    // cached pool: long-lived SSE loops each hold a thread, so a fixed pool would let
    // a few unauthenticated /api/stream connections starve all other request handling
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final java.util.concurrent.atomic.AtomicInteger openStreams =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean running = true;
    private final TileService tiles = new TileService();
    private final WaypointService waypoints = new WaypointService();
    private final MeteorService meteor = new MeteorService();
    private final SeedFeatureService seedFeatures = new SeedFeatureService();

    public DeckServer(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        http.setExecutor(pool);
        http.createContext("/", this::handle);
    }

    public void start() {
        http.start();
    }

    public void stop() {
        running = false;
        http.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            // No wildcard CORS: the bundled web UI is same-origin, and the native app
            // doesn't use CORS. A wildcard would let any website the user visits read
            // live coordinates from /api/status cross-origin.
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            // control endpoints require the pairing token
            boolean isControl = path.startsWith("/api/meteor/") || path.startsWith("/api/chat")
                    || path.startsWith("/api/baritone")
                    || (path.equals("/api/waypoints") && !ex.getRequestMethod().equals("GET"))
                    || (path.equals("/api/oracle/config") && !ex.getRequestMethod().equals("GET"));
            if (isControl && !tokenOk(ex)) {
                sendText(ex, 401, "missing or wrong token");
                return;
            }

            if (path.equals("/") || path.equals("/index.html")) {
                serveResource(ex, "/web/index.html", "text/html; charset=utf-8");
            } else if (path.equals("/api/chat")) {
                if (!DeckConfig.get().chatRelay) {
                    sendText(ex, 403, "chat relay is not enabled (opt in via the Meteor chat-relay module)");
                    return;
                }
                if (ex.getRequestMethod().equals("POST")) {
                    JsonObject body = readBody(ex);
                    String text = body.get("text").getAsString().trim();
                    sendJson(ex, 200, sendChat(text));
                } else {
                    long after = parseLongOr(queryParam(ex, "after"), 0);
                    sendJson(ex, 200, ChatBuffer.since(after));
                }
            } else if (path.equals("/api/baritone")) {
                if (!DeckConfig.get().remoteControl) {
                    sendText(ex, 403, "remote control is not enabled (opt in via the Meteor remote-control module)");
                    return;
                }
                sendJson(ex, 200, baritone(readBody(ex)));
            } else if (path.equals("/api/stream")) {
                handleStream(ex);
            } else if (path.equals("/api/status")) {
                sendJson(ex, 200, status());
            } else if (path.equals("/api/regions")) {
                String dim = queryParam(ex, "dim");
                sendJson(ex, 200, tiles.listRegions(dim));
            } else if (path.equals("/api/dimensions")) {
                sendJson(ex, 200, tiles.listDimensions());
            } else if (path.equals("/api/seed/features")) {
                String dim = queryParam(ex, "dim");
                if (dim.isEmpty()) dim = "overworld";
                sendJson(ex, 200, seedFeatures.features(dim,
                        (int) parseLongOr(queryParam(ex, "x0"), 0),
                        (int) parseLongOr(queryParam(ex, "z0"), 0),
                        (int) parseLongOr(queryParam(ex, "x1"), 0),
                        (int) parseLongOr(queryParam(ex, "z1"), 0)));
            } else if (path.startsWith("/api/oracle/tile/")) {
                handleOracleTile(ex, path);
            } else if (path.equals("/api/oracle/legend")) {
                sendJson(ex, 200, OracleService.get().legend());
            } else if (path.equals("/api/oracle/config")) {
                if (ex.getRequestMethod().equals("POST")) {
                    JsonObject body = readBody(ex);
                    DeckConfig c = DeckConfig.get();
                    if (body.has("seed")) DeckConfig.setSeedForCurrentWorld(body.get("seed").getAsString());
                    if (body.has("versions")) {
                        c.oracleVersions = new java.util.ArrayList<>();
                        for (var v : body.get("versions").getAsJsonArray()) {
                            c.oracleVersions.add(v.getAsString());
                        }
                    }
                    c.save();
                    OracleService.get().configChanged();
                }
                JsonObject o = new JsonObject();
                o.addProperty("seed", DeckConfig.currentSeed());
                String wid = PositionTracker.get().worldId();
                if (wid != null) o.addProperty("worldId", wid);
                JsonArray vs = new JsonArray();
                for (String v : DeckConfig.get().oracleVersions) vs.add(v);
                o.add("versions", vs);
                sendJson(ex, 200, o);
            } else if (path.startsWith("/api/tile/")) {
                handleTile(ex, path, false);
            } else if (path.startsWith("/api/overview/")) {
                handleTile(ex, path, true);
            } else if (path.equals("/api/waypoints")) {
                switch (ex.getRequestMethod()) {
                    case "POST" -> {
                        JsonObject body = JsonParser.parseString(
                                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        sendJson(ex, 200, waypoints.add(body));
                    }
                    case "DELETE" -> {
                        JsonObject body = JsonParser.parseString(
                                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        sendJson(ex, 200, waypoints.delete(body));
                    }
                    default -> sendJson(ex, 200, waypoints.list());
                }
            } else if (path.equals("/api/notifications")) {
                long after = 0;
                try {
                    after = Long.parseLong(queryParam(ex, "after"));
                } catch (NumberFormatException ignored) {
                }
                sendJson(ex, 200, Notifications.since(after));
            } else if (path.startsWith("/api/meteor/") && !DeckConfig.get().remoteControl) {
                sendText(ex, 403, "remote control is not enabled (opt in via the Meteor remote-control module)");
            } else if (path.equals("/api/meteor/modules")) {
                sendJson(ex, 200, meteor.modules());
            } else if (path.equals("/api/meteor/toggle")) {
                sendJson(ex, 200, meteor.toggle(readBody(ex)));
            } else if (path.equals("/api/meteor/settings")) {
                sendJson(ex, 200, meteor.settings(queryParam(ex, "module")));
            } else if (path.equals("/api/meteor/setting")) {
                sendJson(ex, 200, meteor.setSetting(readBody(ex)));
            } else {
                sendText(ex, 404, "not found");
            }
        } catch (Exception e) {
            // log the path only — the query string can carry ?token=, which must never hit the log
            XaeroDeck.LOG.error("XaeroDeck request failed: {}", ex.getRequestURI().getPath(), e);
            try {
                sendText(ex, 500, "error: " + e.getMessage());
            } catch (IOException ignored) {
            }
        }
    }

    // /api/tile/[{dim}/]{rx}/{rz}.png or /api/overview/[{dim}/]{ox}/{oz}.png
    private void handleTile(HttpExchange ex, String path, boolean overview) throws Exception {
        String prefix = overview ? "/api/overview/" : "/api/tile/";
        String[] parts = path.substring(prefix.length()).split("/");
        String dim = "";
        if (parts.length == 3) {
            dim = parts[0];
            parts = new String[]{parts[1], parts[2]};
        }
        if (parts.length != 2 || !parts[1].endsWith(".png")) {
            sendText(ex, 400, "expected " + prefix + "[{dim}/]{x}/{z}.png");
            return;
        }
        int rx, rz;
        try {
            rx = Integer.parseInt(parts[0]);
            rz = Integer.parseInt(parts[1].substring(0, parts[1].length() - 4));
        } catch (NumberFormatException nfe) {
            sendText(ex, 400, "bad region coords");
            return;
        }

        TileService.TileResult result = overview ? tiles.renderOverview(dim, rx, rz)
                : tiles.render(dim, rx, rz);
        if (result == null) {
            sendText(ex, 404, "no data for region");
            return;
        }
        String etag = "\"" + result.version() + "\"";
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(inm)) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "image/png");
        ex.getResponseHeaders().add("ETag", etag);
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, result.png().length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(result.png());
        }
    }

    // /api/oracle/tile/[{dim}/]{rx}/{rz}.png — 404 while disabled, off-overworld, or still computing
    private void handleOracleTile(HttpExchange ex, String path) throws Exception {
        String[] parts = path.substring("/api/oracle/tile/".length()).split("/");
        String dim = "";
        if (parts.length == 3) {
            dim = parts[0];
            parts = new String[]{parts[1], parts[2]};
        }
        if (parts.length != 2 || !parts[1].endsWith(".png")) {
            sendText(ex, 400, "expected /api/oracle/tile/[{dim}/]{x}/{z}.png");
            return;
        }
        int rx, rz;
        try {
            rx = Integer.parseInt(parts[0]);
            rz = Integer.parseInt(parts[1].substring(0, parts[1].length() - 4));
        } catch (NumberFormatException nfe) {
            sendText(ex, 400, "bad region coords");
            return;
        }

        OracleService.OracleTile result = OracleService.get().getTile(dim, rx, rz);
        if (result == null) {
            sendText(ex, 404, "no oracle data");
            return;
        }
        String etag = "\"" + result.version() + "\"";
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(inm)) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "image/png");
        ex.getResponseHeaders().add("ETag", etag);
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, result.png().length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(result.png());
        }
    }

    private JsonObject status() {
        PositionTracker.Snapshot s = PositionTracker.get();
        JsonObject o = new JsonObject();
        o.addProperty("app", "xaerodeck");
        o.addProperty("inGame", s.inGame());
        if (s.inGame()) {
            JsonObject p = new JsonObject();
            p.addProperty("x", s.x());
            p.addProperty("y", s.y());
            p.addProperty("z", s.z());
            p.addProperty("yaw", s.yaw());
            o.add("player", p);
            o.addProperty("dimension", s.dimension());
            o.addProperty("worldId", s.worldId());
            JsonObject stats = new JsonObject();
            stats.addProperty("bps", s.bps());
            stats.addProperty("ping", s.ping());
            stats.addProperty("tps", s.tps());
            stats.addProperty("totems", s.totems());
            stats.addProperty("elytra", s.elytraPct());
            stats.addProperty("hp", Math.round(s.health() * 10.0) / 10.0);
            o.add("stats", stats);
            double[] ap = Autopilot.getTarget();
            if (ap != null) {
                JsonObject apo = new JsonObject();
                apo.addProperty("x", ap[0]);
                apo.addProperty("z", ap[1]);
                o.add("autopilot", apo);
            }
            if (!s.effects().isEmpty()) {
                JsonArray fx = new JsonArray();
                for (PositionTracker.Effect e : s.effects()) {
                    JsonObject eo = new JsonObject();
                    eo.addProperty("n", e.name());
                    eo.addProperty("s", e.seconds());
                    eo.addProperty("c", e.color());
                    fx.add(eo);
                }
                o.add("effects", fx);
            }
            JsonArray ents = new JsonArray();
            for (PositionTracker.EntityDot d : s.entities()) {
                JsonObject e = new JsonObject();
                if (d.name() != null) e.addProperty("n", d.name());
                e.addProperty("x", Math.round(d.x() * 10.0) / 10.0);
                e.addProperty("z", Math.round(d.z() * 10.0) / 10.0);
                e.addProperty("t", String.valueOf(d.type()));
                ents.add(e);
            }
            o.add("entities", ents);
        }
        o.addProperty("meteorRev", MeteorService.rev());
        return o;
    }

    /** Server-sent events: pushes the position snapshot at the configured rate. */
    private void handleStream(HttpExchange ex) throws IOException {
        if (openStreams.incrementAndGet() > MAX_STREAMS) {
            openStreams.decrementAndGet();
            sendText(ex, 503, "too many streams");
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        long intervalMs = 1000L / DeckConfig.get().streamHz;
        try (OutputStream os = ex.getResponseBody()) {
            long lastTime = -1;
            long lastNotif = Notifications.latestId(); // don't replay history on connect
            long lastChat = ChatBuffer.latestId();
            long lastMeteorRev = MeteorService.rev();
            long lastDirty = DirtyRegions.latestId();
            while (running) {
                PositionTracker.Snapshot s = PositionTracker.get();
                boolean hasNotifs = Notifications.latestId() > lastNotif;
                boolean hasChat = DeckConfig.get().chatRelay && ChatBuffer.latestId() > lastChat;
                boolean meteorChanged = MeteorService.rev() != lastMeteorRev;
                boolean hasDirty = DirtyRegions.latestId() > lastDirty;
                if (s.time() != lastTime || hasNotifs || hasChat || meteorChanged || hasDirty) {
                    lastTime = s.time();
                    lastMeteorRev = MeteorService.rev();
                    JsonObject payload = status();
                    if (hasNotifs) {
                        payload.add("notifications", Notifications.since(lastNotif));
                        lastNotif = Notifications.latestId();
                    }
                    if (hasChat) {
                        payload.add("chat", ChatBuffer.since(lastChat));
                        lastChat = ChatBuffer.latestId();
                    }
                    if (hasDirty) {
                        // per-client cursor: every stream sees each change, not just the first to wake
                        payload.add("dirty", DirtyRegions.since(lastDirty));
                        lastDirty = DirtyRegions.latestId();
                    }
                    os.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                Thread.sleep(intervalMs);
            }
        } catch (Exception disconnected) {
            // client went away — normal
        } finally {
            openStreams.decrementAndGet();
        }
    }

    static <T> T onClientThread(java.util.concurrent.Callable<T> job) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return job.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                f.complete(job.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(15, TimeUnit.SECONDS);
    }

    private void serveResource(HttpExchange ex, String resource, String contentType) throws IOException {
        try (InputStream in = DeckServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendText(ex, 404, "missing resource");
                return;
            }
            byte[] data = in.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private void sendJson(HttpExchange ex, int code, Object json) throws IOException {
        byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private void sendText(HttpExchange ex, int code, String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private boolean tokenOk(HttpExchange ex) {
        String want = DeckConfig.get().token;
        if (want == null || want.isEmpty()) return true;
        String got = ex.getRequestHeaders().getFirst("X-Deck-Token");
        if (got == null) got = queryParam(ex, "token");
        if (got == null) return false;
        // constant-time compare so a hostile LAN client can't time the token out char by char
        return java.security.MessageDigest.isEqual(
                want.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8));
    }

    /** Meteor's remote-control module also gates module toggling. */
    private JsonObject sendChat(String text) throws Exception {
        JsonObject resp = new JsonObject();
        if (text.isEmpty() || text.length() > 256) {
            resp.addProperty("ok", false);
            return resp;
        }
        return onClientThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "not in a world");
                return resp;
            }
            if (text.startsWith("/")) mc.getConnection().sendCommand(text.substring(1));
            else mc.getConnection().sendChat(text);
            resp.addProperty("ok", true);
            return resp;
        });
    }

    private JsonObject baritone(JsonObject body) throws Exception {
        JsonObject resp = new JsonObject();
        return onClientThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "not in a world");
                return resp;
            }
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            if (!loader.isModLoaded("baritone") && !loader.isModLoaded("baritone-meteor")) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "baritone not installed");
                return resp;
            }
            String action = body.get("action").getAsString();
            if (action.equals("flyto")) {
                if (!Autopilot.enabled) {
                    resp.addProperty("ok", false);
                    resp.addProperty("error", "enable the deck-autopilot module in Meteor first");
                    return resp;
                }
                Autopilot.setTarget(body.get("x").getAsInt(), body.get("z").getAsInt());
                resp.addProperty("ok", true);
                return resp;
            }
            if (action.equals("cancel")) Autopilot.clear();
            String cmd = switch (action) {
                case "goto" -> "goto " + body.get("x").getAsInt() + " " + body.get("z").getAsInt();
                case "cancel" -> "cancel";
                default -> null;
            };
            if (cmd == null) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "unknown action");
                return resp;
            }
            // drive Baritone through its API directly — never touches server chat
            try {
                boolean ok = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone()
                        .getCommandManager().execute(cmd);
                resp.addProperty("ok", ok);
                if (!ok) resp.addProperty("error", "baritone rejected: " + cmd);
            } catch (Throwable t) {
                XaeroDeck.LOG.warn("Baritone command failed", t);
                resp.addProperty("ok", false);
                resp.addProperty("error", String.valueOf(t));
            }
            return resp;
        });
    }

    private static long parseLongOr(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static JsonObject readBody(HttpExchange ex) throws IOException {
        return JsonParser.parseString(
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return "";
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static JsonArray jsonArray() {
        return new JsonArray();
    }
}
