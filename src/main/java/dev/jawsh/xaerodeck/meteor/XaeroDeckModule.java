package dev.jawsh.xaerodeck.meteor;

import dev.jawsh.xaerodeck.DeckConfig;
import dev.jawsh.xaerodeck.XaeroDeck;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Meteor integration: toggle the XaeroDeck LAN server from the click GUI.
 * The module's on/off state starts/stops the HTTP server.
 */
public class XaeroDeckModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> port = sgGeneral.add(new IntSetting.Builder()
            .name("port")
            .description("HTTP port the map server listens on. Applies on re-toggle.")
            .defaultValue(8399)
            .min(1024)
            .max(65535)
            .sliderRange(8000, 9000)
            .onChanged(v -> {
                DeckConfig.get().port = v;
                DeckConfig.get().save();
                if (isActive()) XaeroDeck.restartServer();
            })
            .build());

    private final Setting<Integer> streamHz = sgGeneral.add(new IntSetting.Builder()
            .name("stream-rate")
            .description("Position updates per second sent to companion devices.")
            .defaultValue(10)
            .min(1)
            .max(20)
            .sliderRange(1, 20)
            .onChanged(v -> {
                DeckConfig.get().streamHz = v;
                DeckConfig.get().save();
            })
            .build());

    private final Setting<Boolean> sethomeWaypoints = sgGeneral.add(new BoolSetting.Builder()
            .name("sethome-waypoints")
            .description("Create a Xaero waypoint automatically when you run /sethome.")
            .defaultValue(false)
            .onChanged(v -> {
                DeckConfig.get().watchSetHome = v;
                DeckConfig.get().save();
            })
            .build());

    private final Setting<Boolean> autoStart = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-start")
            .description("Start the server automatically when the game launches.")
            .defaultValue(true)
            .onChanged(v -> {
                DeckConfig.get().autoStart = v;
                DeckConfig.get().save();
            })
            .build());

    public XaeroDeckModule() {
        super(XaeroDeckAddon.CATEGORY, "map-server", "Streams your Xaero map + position to companion devices (tablet/phone) over LAN.");
    }

    @Override
    public void onActivate() {
        if (!XaeroDeck.isRunning() && !XaeroDeck.startServer()) {
            error("Could not start XaeroDeck server on port " + DeckConfig.get().port);
        } else {
            info("XaeroDeck serving on port " + DeckConfig.get().port);
        }
    }

    @Override
    public void onDeactivate() {
        XaeroDeck.stopServer();
    }

    @Override
    public String getInfoString() {
        return XaeroDeck.isRunning() ? ":" + DeckConfig.get().port : null;
    }
}
