package dev.jawsh.xaerodeck;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class DeckConfig {
    public int port = 8399;
    public boolean autoStart = true;
    /** Position stream rate in updates per second (1-20). */
    public int streamHz = 10;
    /** Opt-in: auto-create a Xaero waypoint when the player runs /sethome. */
    public boolean watchSetHome = false;
    /** Opt-in: relay in-game chat to companion devices and allow sending from them. */
    public boolean chatRelay = false;
    /** Opt-in: allow companion devices to control Baritone and Meteor modules. */
    public boolean remoteControl = false;
    /** Shared secret required for control endpoints. Generated on first run. */
    public String token = "";
    /** World seed for the worldgen oracle overlay. Empty disables the oracle. */
    public String oracleSeed = "";
    /** Candidate versions the oracle fingerprints chunks against, in fixed palette order. */
    public java.util.List<String> oracleVersions = new java.util.ArrayList<>(
            java.util.List.of("1.12.2", "1.18.2", "1.19.2"));

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static DeckConfig instance;

    public static DeckConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("xaerodeck.json");
    }

    private static DeckConfig load() {
        try {
            if (Files.exists(file())) {
                DeckConfig c = GSON.fromJson(Files.readString(file()), DeckConfig.class);
                if (c != null) return c.clamp();
            }
        } catch (Exception e) {
            XaeroDeck.LOG.warn("Bad xaerodeck.json, using defaults", e);
        }
        DeckConfig c = new DeckConfig();
        c.save();
        return c;
    }

    public DeckConfig clamp() {
        port = Math.max(1024, Math.min(65535, port));
        streamHz = Math.max(1, Math.min(20, streamHz));
        if (oracleSeed == null) oracleSeed = "";
        if (oracleVersions == null || oracleVersions.isEmpty()) {
            oracleVersions = new java.util.ArrayList<>(java.util.List.of("1.12.2", "1.18.2", "1.19.2"));
        }
        if (token == null || token.isBlank()) {
            // ~128 bits over a 31-char alphabet (26 chars); existing shorter tokens are kept as-is
            java.security.SecureRandom r = new java.security.SecureRandom();
            StringBuilder sb = new StringBuilder(26);
            String chars = "abcdefghjkmnpqrstuvwxyz23456789";
            for (int i = 0; i < 26; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
            token = sb.toString();
        }
        return this;
    }

    public void save() {
        try {
            Files.writeString(file(), GSON.toJson(clamp()));
        } catch (Exception e) {
            XaeroDeck.LOG.warn("Could not save xaerodeck.json", e);
        }
    }
}
