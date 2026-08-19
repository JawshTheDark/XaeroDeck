package dev.jawsh.xaerodeck;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.WorldMapSession;

public class XaeroDeck implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("xaerodeck");
    private static DeckServer server;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            if (DeckConfig.get().autoStart) startServer();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stopServer());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            String worldId = null;
            WorldMapSession s = WorldMapSession.getCurrentSession();
            if (s != null) worldId = s.getMapProcessor().getCurrentWorldId();
            PositionTracker.tick(mc, worldId);
        });

        // Opt-in /sethome watcher: mirror server homes as Xaero waypoints.
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.COMMAND.register(command -> {
            if (!DeckConfig.get().watchSetHome) return;
            String[] parts = command.trim().split("\\s+");
            if (parts.length == 0 || !parts[0].equalsIgnoreCase("sethome")) return;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return;
            String name = parts.length > 1 ? "Home: " + parts[1] : "Home";
            int x = (int) Math.floor(mc.player.getX());
            int y = (int) Math.floor(mc.player.getY());
            int z = (int) Math.floor(mc.player.getZ());
            if (WaypointService.addLocalWaypoint(name, x, y, z, 10)) {
                LOG.info("Created waypoint '{}' from /sethome at {} {} {}", name, x, y, z);
            }
        });
    }

    public static synchronized boolean startServer() {
        if (server != null) return true;
        try {
            server = new DeckServer(DeckConfig.get().port);
            server.start();
            DiscoveryBeacon.start();
            LOG.info("XaeroDeck server listening on port {}", DeckConfig.get().port);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to start XaeroDeck server", e);
            server = null;
            return false;
        }
    }

    public static synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
            DiscoveryBeacon.stop();
            LOG.info("XaeroDeck server stopped");
        }
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static synchronized void restartServer() {
        stopServer();
        startServer();
    }
}
