package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcterrain.TerrainGenerator;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Worldgen oracle: fingerprints which MC version generated each chunk and flags
 * columns that don't match natural generation, by comparing Xaero's recorded
 * surface heights against seedfinding terrain simulation for the configured seed.
 * Overworld surface layer only. Simulation runs on a single worker thread; results
 * are served as transparent 512px overlay PNGs aligned with the map region tiles.
 */
public class OracleService {
    public record OracleTile(byte[] png, long version, long computedMs) {
    }

    /** Palette by configured-version index; alpha applied at paint time. */
    private static final int[] ERA_COLORS = {
            0xFFE0A030, 0xFF3080E0, 0xFF40C060, 0xFFB060E0, 0xFF30C0C0, 0xFFE05090,
    };
    private static final int MODIFIED_COLOR = 0xFFFF3333;
    private static final int ERA_ALPHA = 0x55;
    private static final int MODIFIED_ALPHA = 0xB4;
    private static final int SAMPLES_PER_CHUNK = 8;
    // sample offsets inside a chunk, spread to avoid decorator hotspots
    private static final int[] SX = {2, 6, 10, 14, 4, 12, 8, 0};
    private static final int[] SZ = {3, 13, 5, 11, 8, 2, 15, 7};
    private static final int ERA_MAX_AVG_ERR = 6;
    private static final int MODIFIED_ERR = 4;
    private static final int MODIFIED_MIN_SAMPLES = 3;
    private static final long RECOMPUTE_DEBOUNCE_MS = 30_000;
    private static final int SEA_LEVEL = 63;

    /** Surface blocks that never generate naturally on the overworld surface. */
    private static final Set<String> ARTIFICIAL_BLOCKS = Set.of(
            "obsidian", "crying_obsidian", "netherrack", "soul_sand", "soul_soil", "glowstone",
            "crafting_table", "furnace", "chest", "ender_chest", "shulker_box", "tnt",
            "glass", "white_stained_glass", "black_stained_glass", "slime_block", "honey_block",
            "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks",
            "dark_oak_planks", "crimson_planks", "warped_planks", "mangrove_planks",
            "cherry_planks", "bamboo_planks",
            "stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks", "bricks",
            "quartz_block", "purpur_block", "beacon", "enchanting_table", "anvil",
            "white_wool", "black_wool", "red_wool", "blue_wool", "lime_wool", "yellow_wool",
            "white_concrete", "black_concrete", "red_concrete", "blue_concrete",
            "scaffolding", "iron_block", "gold_block", "diamond_block", "netherite_block",
            "emerald_block", "hay_block", "dried_kelp_block", "bone_block", "lodestone",
            "respawn_anchor", "lectern", "loom", "smithing_table", "cartography_table",
            "barrel", "smoker", "blast_furnace", "composter", "stonecutter", "grindstone",
            "bell", "campfire", "soul_campfire", "ladder", "torch", "soul_torch",
            "redstone_block", "piston", "sticky_piston", "observer", "dispenser", "dropper",
            "hopper", "note_block", "jukebox", "bookshelf", "cobweb");

    private static final int MAX_CACHE = 128;
    private final Map<String, OracleTile> cache = new LinkedHashMap<>(32, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, OracleTile> e) {
            return size() > MAX_CACHE;
        }
    };
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> dirtyAt = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "xaerodeck-oracle");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, TerrainGenerator> generators = new ConcurrentHashMap<>();
    private volatile long configEpoch = 1;

    private static OracleService instance;

    public static synchronized OracleService get() {
        if (instance == null) instance = new OracleService();
        return instance;
    }

    public boolean enabled() {
        return !DeckConfig.get().oracleSeed.isBlank();
    }

    /** Called from DirtyRegions when Xaero rebuilds a region so we recompute eventually. */
    public static void markDirty(String dim, int rx, int rz) {
        OracleService o = instance;
        if (o != null && o.enabled()) o.dirtyAt.put(rx + "_" + rz, System.currentTimeMillis());
    }

    public void configChanged() {
        synchronized (cache) {
            cache.clear();
        }
        generators.clear();
        dirtyAt.clear();
        configEpoch++;
    }

    public JsonObject legend() {
        DeckConfig c = DeckConfig.get();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled());
        JsonArray entries = new JsonArray();
        for (int i = 0; i < c.oracleVersions.size(); i++) {
            JsonObject e = new JsonObject();
            e.addProperty("label", c.oracleVersions.get(i));
            e.addProperty("color", String.format("#%06X", ERA_COLORS[i % ERA_COLORS.length] & 0xFFFFFF));
            entries.add(e);
        }
        o.add("entries", entries);
        o.addProperty("modified", String.format("#%06X", MODIFIED_COLOR & 0xFFFFFF));
        return o;
    }

    /** Returns the overlay tile, or null while there is no data yet (serve 404). */
    public OracleTile getTile(String dimKey, int rx, int rz) {
        if (!enabled() || !isOverworld(dimKey)) return null;
        String key = rx + "_" + rz;
        OracleTile cached;
        synchronized (cache) {
            cached = cache.get(key);
        }
        Long dirty = dirtyAt.get(key);
        boolean stale = cached != null && dirty != null && dirty > cached.computedMs()
                && System.currentTimeMillis() - cached.computedMs() > RECOMPUTE_DEBOUNCE_MS;
        if ((cached == null || stale) && inFlight.add(key)) {
            long epoch = configEpoch;
            worker.submit(() -> {
                try {
                    compute(key, rx, rz, epoch);
                } catch (Throwable t) {
                    XaeroDeck.LOG.warn("oracle compute failed for region {},{}", rx, rz, t);
                } finally {
                    inFlight.remove(key);
                }
            });
        }
        return cached;
    }

    private boolean isOverworld(String dimKey) {
        if (dimKey == null || dimKey.isEmpty() || dimKey.equals("current")) {
            try {
                return DeckServer.onClientThread(() -> {
                    WorldMapSession s = WorldMapSession.getCurrentSession();
                    if (s == null || s.getMapProcessor().getMapWorld() == null) return false;
                    return s.getMapProcessor().getMapWorld().getCurrentDimension()
                            .getDimId().identifier().getPath().equals("overworld");
                });
            } catch (Exception e) {
                return false;
            }
        }
        return dimKey.equals("overworld") || dimKey.equals("minecraft:overworld");
    }

    /** Observed surface snapshot; heights[i] = Short.MIN_VALUE where unknown. */
    private record Observed(short[] heights, boolean[] artificial) {
    }

    private void compute(String key, int rx, int rz, long epoch) throws Exception {
        Observed obs = DeckServer.onClientThread(() -> snapshot(rx, rz));
        if (obs == null || epoch != configEpoch) return;

        DeckConfig c = DeckConfig.get();
        long seed = parseSeed(c.oracleSeed);
        TerrainGenerator[] gens = new TerrainGenerator[c.oracleVersions.size()];
        for (int i = 0; i < gens.length; i++) {
            gens[i] = generatorFor(c.oracleVersions.get(i), seed);
        }

        int[] pixels = new int[512 * 512];
        int baseX = rx << 9, baseZ = rz << 9;
        for (int ccx = 0; ccx < 32; ccx++) {
            for (int ccz = 0; ccz < 32; ccz++) {
                classifyChunk(obs, pixels, gens, baseX + (ccx << 4), baseZ + (ccz << 4), ccx << 4, ccz << 4);
            }
        }

        BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 512, 512, pixels, 0, 512);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        ImageIO.write(img, "png", out);
        long now = System.currentTimeMillis();
        OracleTile tile = new OracleTile(out.toByteArray(), now ^ ((long) rx << 40) ^ ((long) rz << 20), now);
        synchronized (cache) {
            cache.put(key, tile);
        }
        // nudge companions to refetch this region's overlay; drop the dirty stamp our
        // own mark() just re-added so we don't recompute in a loop
        DirtyRegions.mark("overworld", rx, rz);
        dirtyAt.remove(key);
    }

    private void classifyChunk(Observed obs, int[] pixels, TerrainGenerator[] gens,
                               int worldX, int worldZ, int px, int pz) {
        short[] obsH = new short[SAMPLES_PER_CHUNK];
        int valid = 0;
        for (int s = 0; s < SAMPLES_PER_CHUNK; s++) {
            short h = obs.heights()[(pz + SZ[s]) * 512 + px + SX[s]];
            obsH[s] = h;
            if (h != Short.MIN_VALUE) valid++;
        }
        if (valid < 4) return; // not enough mapped columns

        int bestVer = -1;
        long bestErr = Long.MAX_VALUE;
        long[] errs = new long[gens.length];
        for (int v = 0; v < gens.length; v++) {
            if (gens[v] == null) {
                errs[v] = Long.MAX_VALUE;
                continue;
            }
            long err = 0;
            for (int s = 0; s < SAMPLES_PER_CHUNK; s++) {
                if (obsH[s] == Short.MIN_VALUE) continue;
                err += Math.min(columnError(gens[v], worldX + SX[s], worldZ + SZ[s], obsH[s]), 16);
            }
            errs[v] = err;
            if (err < bestErr) {
                bestErr = err;
                bestVer = v;
            }
        }
        if (bestVer < 0) return;

        long avgErr = bestErr / valid;
        boolean known = avgErr <= ERA_MAX_AVG_ERR;
        int deviant = 0;
        if (known) {
            for (int s = 0; s < SAMPLES_PER_CHUNK; s++) {
                if (obsH[s] == Short.MIN_VALUE) continue;
                if (columnError(gens[bestVer], worldX + SX[s], worldZ + SZ[s], obsH[s]) > MODIFIED_ERR) deviant++;
            }
        }
        boolean modified = known && deviant >= MODIFIED_MIN_SAMPLES;

        int eraArgb = known ? (ERA_ALPHA << 24) | (ERA_COLORS[bestVer % ERA_COLORS.length] & 0xFFFFFF) : 0;
        int modArgb = (MODIFIED_ALPHA << 24) | (MODIFIED_COLOR & 0xFFFFFF);
        for (int z = 0; z < 16; z++) {
            int row = (pz + z) * 512 + px;
            for (int x = 0; x < 16; x++) {
                int idx = row + x;
                int argb = eraArgb;
                if (obs.artificial()[idx]) argb = modArgb;
                else if (modified && ((x + z) & 3) == 0) argb = modArgb; // hatch pattern
                pixels[idx] = argb;
            }
        }
    }

    /**
     * |observed - predicted| with a water carve-out: where the sim says sea level
     * and Xaero recorded a seabed below it, that's water, not a player hole.
     */
    private long columnError(TerrainGenerator gen, int x, int z, int obsHeight) {
        int pred = gen.getFirstHeightInColumn(x, z, TerrainGenerator.WORLD_SURFACE_WG);
        if (pred >= SEA_LEVEL - 1 && pred <= SEA_LEVEL + 1 && obsHeight < SEA_LEVEL) return 0;
        return Math.abs(obsHeight - pred);
    }

    private TerrainGenerator generatorFor(String versionName, long seed) {
        return generators.computeIfAbsent(versionName + "|" + seed, k -> {
            MCVersion v = MCVersion.fromString(versionName);
            if (v == null) {
                XaeroDeck.LOG.warn("oracle: unknown version {}", versionName);
                return null;
            }
            BiomeSource bs = BiomeSource.of(Dimension.OVERWORLD, v, seed);
            return TerrainGenerator.of(Dimension.OVERWORLD, bs);
        });
    }

    static long parseSeed(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return s.trim().hashCode(); // vanilla string-seed behavior
        }
    }

    /** Runs on the client thread: copy heights + artificial-surface flags for a live region. */
    private Observed snapshot(int rx, int rz) {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) return null;
        MapProcessor mp = session.getMapProcessor();
        if (mp.getMapWorld() == null) return null;
        if (!mp.getMapWorld().getCurrentDimension().getDimId().identifier().getPath().equals("overworld")) {
            return null;
        }
        MapRegion region = mp.getLeafMapRegion(Integer.MAX_VALUE, rx, rz, false);
        if (region == null) return null;

        short[] heights = new short[512 * 512];
        java.util.Arrays.fill(heights, Short.MIN_VALUE);
        boolean[] artificial = new boolean[512 * 512];
        boolean any = false;
        for (int cx = 0; cx < 8; cx++) {
            for (int cz = 0; cz < 8; cz++) {
                MapTileChunk tc = region.getChunk(cx, cz);
                if (tc == null || !tc.hasHadTerrain()) continue;
                for (int tx = 0; tx < 4; tx++) {
                    for (int tz = 0; tz < 4; tz++) {
                        MapTile tile = tc.getTile(tx, tz);
                        if (tile == null || !tile.isLoaded()) continue;
                        int bx = cx * 64 + tx * 16, bz = cz * 64 + tz * 16;
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                MapBlock b = tile.getBlock(x, z);
                                if (b == null) continue;
                                int idx = (bz + z) * 512 + bx + x;
                                heights[idx] = (short) b.getHeight();
                                any = true;
                                var state = b.getState();
                                if (state != null && ARTIFICIAL_BLOCKS.contains(
                                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                                .getKey(state.getBlock()).getPath())) {
                                    artificial[idx] = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return any ? new Observed(heights, artificial) : null;
    }
}
