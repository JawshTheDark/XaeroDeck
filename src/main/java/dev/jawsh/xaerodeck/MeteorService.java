package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Remote control for Meteor modules. All meteor classes are touched only inside
 * the inner Bridge class so this compiles out safely when Meteor is absent.
 */
public class MeteorService {
    private static final java.util.concurrent.atomic.AtomicLong REV = new java.util.concurrent.atomic.AtomicLong();

    /** Bumped whenever a Meteor module toggles; streamed so companions refetch module state. */
    public static long rev() {
        return REV.get();
    }

    public static void bumpRev() {
        REV.incrementAndGet();
    }

    private Boolean present;

    private boolean meteorPresent() {
        if (present == null) present = FabricLoader.getInstance().isModLoaded("meteor-client");
        return present;
    }

    public JsonArray modules() throws Exception {
        if (!meteorPresent()) return new JsonArray();
        return DeckServer.onClientThread(Bridge::modules);
    }

    public JsonObject toggle(JsonObject body) throws Exception {
        JsonObject resp = new JsonObject();
        if (!meteorPresent()) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "meteor not installed");
            return resp;
        }
        return DeckServer.onClientThread(() -> Bridge.toggle(body));
    }

    public JsonArray settings(String moduleName) throws Exception {
        if (!meteorPresent()) return new JsonArray();
        return DeckServer.onClientThread(() -> Bridge.settings(moduleName));
    }

    public JsonObject setSetting(JsonObject body) throws Exception {
        JsonObject resp = new JsonObject();
        if (!meteorPresent()) {
            resp.addProperty("ok", false);
            return resp;
        }
        return DeckServer.onClientThread(() -> Bridge.setSetting(body));
    }

    private static class Bridge {
        static JsonArray modules() {
            JsonArray arr = new JsonArray();
            for (meteordevelopment.meteorclient.systems.modules.Module m :
                    meteordevelopment.meteorclient.systems.modules.Modules.get().getAll()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", m.name);
                o.addProperty("title", m.title);
                o.addProperty("category", m.category.name);
                o.addProperty("active", m.isActive());
                o.addProperty("description", m.description);
                arr.add(o);
            }
            return arr;
        }

        static JsonObject toggle(JsonObject body) {
            JsonObject resp = new JsonObject();
            var module = meteordevelopment.meteorclient.systems.modules.Modules.get()
                    .get(body.get("name").getAsString());
            if (module == null) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "unknown module");
                return resp;
            }
            boolean target = body.has("active") ? body.get("active").getAsBoolean() : !module.isActive();
            if (module.isActive() != target) module.toggle();
            resp.addProperty("ok", true);
            resp.addProperty("active", module.isActive());
            return resp;
        }

        static JsonArray settings(String moduleName) {
            JsonArray arr = new JsonArray();
            var module = meteordevelopment.meteorclient.systems.modules.Modules.get().get(moduleName);
            if (module == null) return arr;

            // built-in module controls, present on every module like in Meteor's GUI
            JsonObject builtins = new JsonObject();
            builtins.addProperty("group", "Module");
            JsonArray builtinSettings = new JsonArray();
            JsonObject fb = new JsonObject();
            fb.addProperty("name", "_chat-feedback");
            fb.addProperty("title", "Chat Feedback");
            fb.addProperty("description", "Send chat messages when this module is toggled.");
            fb.addProperty("type", "bool");
            fb.addProperty("value", String.valueOf(module.chatFeedback));
            builtinSettings.add(fb);
            JsonObject bind = new JsonObject();
            bind.addProperty("name", "_bind");
            bind.addProperty("title", "Bind");
            bind.addProperty("description", "Keybind (change in-game).");
            bind.addProperty("type", "label");
            bind.addProperty("value", String.valueOf(module.keybind));
            builtinSettings.add(bind);
            builtins.add("settings", builtinSettings);
            arr.add(builtins);
            for (meteordevelopment.meteorclient.settings.SettingGroup group : module.settings) {
                JsonObject g = new JsonObject();
                g.addProperty("group", group.name);
                JsonArray settings = new JsonArray();
                for (meteordevelopment.meteorclient.settings.Setting<?> s : group) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", s.name);
                    o.addProperty("title", s.title);
                    o.addProperty("description", s.description);
                    String type = s.getClass().getSimpleName();
                    o.addProperty("type", type.endsWith("Setting")
                            ? type.substring(0, type.length() - 7).toLowerCase()
                            : type.toLowerCase());
                    Object v = s.get();
                    o.addProperty("value", v == null ? "" : String.valueOf(v));
                    if (s instanceof meteordevelopment.meteorclient.settings.IntSetting is) {
                        o.addProperty("sliderMin", is.noSlider ? is.min : is.sliderMin);
                        o.addProperty("sliderMax", is.noSlider ? is.max : is.sliderMax);
                        o.addProperty("decimals", 0);
                    } else if (s instanceof meteordevelopment.meteorclient.settings.DoubleSetting ds) {
                        o.addProperty("sliderMin", ds.noSlider ? ds.min : ds.sliderMin);
                        o.addProperty("sliderMax", ds.noSlider ? ds.max : ds.sliderMax);
                        o.addProperty("decimals", ds.decimalPlaces);
                    }
                    JsonArray options = new JsonArray();
                    try {
                        for (String sug : s.getSuggestions()) options.add(sug);
                    } catch (Throwable ignored) {
                    }
                    if (!options.isEmpty()) o.add("options", options);
                    settings.add(o);
                }
                g.add("settings", settings);
                arr.add(g);
            }
            return arr;
        }

        static JsonObject setSetting(JsonObject body) {
            JsonObject resp = new JsonObject();
            var module = meteordevelopment.meteorclient.systems.modules.Modules.get()
                    .get(body.get("module").getAsString());
            if (module == null) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "unknown module");
                return resp;
            }
            String settingName = body.get("setting").getAsString();
            if (settingName.equals("_chat-feedback")) {
                module.chatFeedback = Boolean.parseBoolean(body.get("value").getAsString());
                resp.addProperty("ok", true);
                resp.addProperty("value", String.valueOf(module.chatFeedback));
                return resp;
            }
            meteordevelopment.meteorclient.settings.Setting<?> setting =
                    module.settings.get(settingName);
            if (setting == null) {
                resp.addProperty("ok", false);
                resp.addProperty("error", "unknown setting");
                return resp;
            }
            boolean ok = setting.parse(body.get("value").getAsString());
            resp.addProperty("ok", ok);
            if (!ok) resp.addProperty("error", "value rejected");
            Object v = setting.get();
            resp.addProperty("value", v == null ? "" : String.valueOf(v));
            return resp;
        }
    }
}
