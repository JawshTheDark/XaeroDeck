package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;

/** Ring buffer of client-side (mod-generated) chat notifications. */
public class Notifications {
    public record Notif(long id, long time, String text, String spansJson) {
    }

    private static final ArrayDeque<Notif> ring = new ArrayDeque<>();
    private static long nextId = 1;

    public static synchronized void add(String text) {
        add(text, null);
    }

    public static synchronized void add(String text, com.google.gson.JsonArray spans) {
        if (text == null || text.isBlank()) return;
        ring.addLast(new Notif(nextId++, System.currentTimeMillis(), text,
                spans == null ? null : spans.toString()));
        while (ring.size() > 100) ring.removeFirst();
    }

    public static synchronized long latestId() {
        return nextId - 1;
    }

    public static synchronized JsonArray since(long afterId) {
        JsonArray arr = new JsonArray();
        for (Notif n : ring) {
            if (n.id() <= afterId) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id());
            o.addProperty("t", n.time());
            o.addProperty("text", n.text());
            if (n.spansJson() != null) {
                o.add("spans", com.google.gson.JsonParser.parseString(n.spansJson()));
            }
            arr.add(o);
        }
        return arr;
    }
}
