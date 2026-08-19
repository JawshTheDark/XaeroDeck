package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;

/** Regions whose pixels changed since last stream push, marked from Xaero's update path. */
public class DirtyRegions {
    private static final LinkedHashSet<String> dirty = new LinkedHashSet<>();

    /** key: dimPath|rx|rz */
    public static synchronized void mark(String dimPath, int rx, int rz) {
        dirty.add(dimPath + "|" + rx + "|" + rz);
        if (dirty.size() > 512) dirty.iterator().remove();
    }

    public static synchronized JsonArray drain() {
        if (dirty.isEmpty()) return null;
        JsonArray arr = new JsonArray();
        for (String key : dirty) {
            String[] p = key.split("\\|");
            JsonObject o = new JsonObject();
            o.addProperty("d", p[0]);
            o.addProperty("x", Integer.parseInt(p[1]));
            o.addProperty("z", Integer.parseInt(p[2]));
            arr.add(o);
        }
        dirty.clear();
        return arr;
    }
}
