package dev.jawsh.xaerodeck;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches client-side chat for SeedcrackerX's crack announcements
 * ("Found world seed <N>." / "... from database.") and stores the seed
 * for the current world so the oracle + structure overlays configure
 * themselves the moment a crack lands.
 */
public class SeedCapture {
    private static final Pattern FOUND = Pattern.compile("Found world seed \\[?(-?\\d+)");

    public static void check(String text) {
        if (text == null) return;
        Matcher m = FOUND.matcher(text);
        if (!m.find()) return;
        String seed = m.group(1);
        if (seed.equals(DeckConfig.currentSeed())) return;
        String worldId = DeckConfig.setSeedForCurrentWorld(seed);
        if (worldId != null) {
            try {
                OracleService.get().configChanged();
            } catch (Throwable ignored) {
            }
            Notifications.add("🌱 SEED CAPTURED — " + worldId + " → " + seed);
            XaeroDeck.LOG.info("Captured world seed for {} from SeedcrackerX", worldId);
        }
    }
}
