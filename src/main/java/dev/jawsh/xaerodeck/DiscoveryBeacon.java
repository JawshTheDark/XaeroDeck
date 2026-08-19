package dev.jawsh.xaerodeck;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Broadcasts a small UDP beacon so companion apps can find us without typing an IP. */
public class DiscoveryBeacon {
    public static final int BEACON_PORT = 8398;
    private static Thread thread;
    private static volatile boolean running;

    public static synchronized void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                while (running) {
                    String worldId = PositionTracker.get().worldId();
                    String msg = "XAERODECK " + DeckConfig.get().port + " " + (worldId == null ? "-" : worldId);
                    byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                    try {
                        socket.send(new DatagramPacket(data, data.length, broadcast, BEACON_PORT));
                    } catch (Exception ignored) {
                    }
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                XaeroDeck.LOG.warn("Discovery beacon stopped", e);
            }
        }, "xaerodeck-beacon");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
