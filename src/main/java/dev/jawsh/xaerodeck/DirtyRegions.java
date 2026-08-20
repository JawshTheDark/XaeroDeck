package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Regions whose pixels changed, marked from Xaero's update path. Id-cursored (like
 * {@link Notifications}) so every connected stream sees each change instead of one
 * client destructively draining it out from under the others. Also keeps a per-region
 * change counter that {@link TileService} uses to version live regions, so tiles are
 * only re-rendered when the region actually changed rather than on a wall-clock bucket.
 */
public class DirtyRegions {
    public record Dirty(long id, String dimPath, int rx, int rz) {
    }

    private static final ArrayDeque<Dirty> ring = new ArrayDeque<>();
    private static final Map<String, Long> versions = new HashMap<>();
    private static long nextId = 1;

    /** key: dimPath|rx|rz */
    public static synchronized void mark(String dimPath, int rx, int rz) {
        ring.addLast(new Dirty(nextId++, dimPath, rx, rz));
        while (ring.size() > 512) ring.removeFirst();
        versions.merge(dimPath + "|" + rx + "|" + rz, 1L, Long::sum);
        if (dimPath.equals("overworld")) OracleService.markDirty(dimPath, rx, rz);
    }

    /** Monotonic change counter for a region; 0 until it is first marked. */
    public static synchronized long version(String dimPath, int rx, int rz) {
        return versions.getOrDefault(dimPath + "|" + rx + "|" + rz, 0L);
    }

    public static synchronized long latestId() {
        return nextId - 1;
    }

    public static synchronized JsonArray since(long afterId) {
        JsonArray arr = new JsonArray();
        for (Dirty d : ring) {
            if (d.id() <= afterId) continue;
            JsonObject o = new JsonObject();
            o.addProperty("d", d.dimPath());
            o.addProperty("x", d.rx());
            o.addProperty("z", d.rz());
            arr.add(o);
        }
        return arr;
    }
}
