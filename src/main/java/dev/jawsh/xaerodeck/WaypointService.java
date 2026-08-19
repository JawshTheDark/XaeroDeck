package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

public class WaypointService {
    private boolean minimapPresent() {
        return FabricLoader.getInstance().isModLoaded("xaerominimap");
    }

    public JsonArray list() throws Exception {
        if (!minimapPresent()) return new JsonArray();
        return DeckServer.onClientThread(() -> {
            JsonArray arr = new JsonArray();
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) return arr;
            MinimapWorld world = session.getWorldManager().getCurrentWorld();
            if (world == null) return arr;
            for (WaypointSet set : world.getIterableWaypointSets()) {
                for (Waypoint w : set.getWaypoints()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", w.getName());
                    o.addProperty("initials", w.getInitials());
                    o.addProperty("x", w.getX());
                    o.addProperty("y", w.getY());
                    o.addProperty("z", w.getZ());
                    o.addProperty("color", w.getWaypointColor().getHex());
                    o.addProperty("set", set.getName());
                    arr.add(o);
                }
            }
            return arr;
        });
    }

    /** Delete the first waypoint matching name+coords in any set. */
    public JsonObject delete(JsonObject body) throws Exception {
        JsonObject resp = new JsonObject();
        if (!minimapPresent()) {
            resp.addProperty("ok", false);
            return resp;
        }
        return DeckServer.onClientThread(() -> {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            MinimapWorld world = session == null ? null : session.getWorldManager().getCurrentWorld();
            if (world == null) {
                resp.addProperty("ok", false);
                return resp;
            }
            String name = body.get("name").getAsString();
            int x = body.get("x").getAsInt();
            int z = body.get("z").getAsInt();
            for (WaypointSet set : world.getIterableWaypointSets()) {
                for (Waypoint w : set.getWaypoints()) {
                    if (w.getName().equals(name) && w.getX() == x && w.getZ() == z) {
                        set.remove(w);
                        try {
                            session.getWorldManagerIO().saveWorld(world);
                        } catch (Exception e) {
                            XaeroDeck.LOG.warn("Waypoint removed but save failed", e);
                        }
                        resp.addProperty("ok", true);
                        return resp;
                    }
                }
            }
            resp.addProperty("ok", false);
            resp.addProperty("error", "not found");
            return resp;
        });
    }

    /** Create a waypoint directly (client thread only). Returns false if minimap/world missing. */
    public static boolean addLocalWaypoint(String name, int x, int y, int z, int colorIndex) {
        try {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            MinimapWorld world = session == null ? null : session.getWorldManager().getCurrentWorld();
            if (world == null) return false;
            String initials = name.isEmpty() ? "H" : name.substring(0, 1).toUpperCase();
            Waypoint wp = new Waypoint(x, y, z, name, initials,
                    WaypointColor.fromIndex(Math.floorMod(colorIndex, 16)));
            world.getCurrentWaypointSet().add(wp);
            try {
                session.getWorldManagerIO().saveWorld(world);
            } catch (Exception e) {
                XaeroDeck.LOG.warn("Waypoint added but save failed", e);
            }
            return true;
        } catch (Throwable t) {
            XaeroDeck.LOG.warn("addLocalWaypoint failed", t);
            return false;
        }
    }

    public JsonObject add(JsonObject body) throws Exception {
        JsonObject resp = new JsonObject();
        if (!minimapPresent()) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "Xaero's Minimap is not installed");
            return resp;
        }
        return DeckServer.onClientThread(() -> {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "not in a world");
                return resp;
            }
            MinimapWorld world = session.getWorldManager().getCurrentWorld();
            if (world == null) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "no minimap world");
                return resp;
            }
            int x = body.get("x").getAsInt();
            int y = body.has("y") ? body.get("y").getAsInt() : 64;
            int z = body.get("z").getAsInt();
            String name = body.has("name") ? body.get("name").getAsString() : "Deck";
            String initials = name.isEmpty() ? "D" : name.substring(0, 1).toUpperCase();
            int colorIndex = body.has("color") ? Math.floorMod(body.get("color").getAsInt(), 16) : 0;
            Waypoint wp = new Waypoint(x, y, z, name, initials, WaypointColor.fromIndex(colorIndex));
            world.getCurrentWaypointSet().add(wp);
            try {
                session.getWorldManagerIO().saveWorld(world);
            } catch (Exception e) {
                XaeroDeck.LOG.warn("Waypoint added but save failed", e);
            }
            resp.addProperty("ok", true);
            return resp;
        });
    }
}
