package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.RegionStructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Chunkbase-in-the-deck: computes seed-derived structure locations for the map.
 * Uses the same seed + newest configured version as the worldgen oracle. All
 * computation is lazy and cached per structure region; biome checks make first
 * queries of an area cost a few ms per structure type.
 */
public class SeedFeatureService {
    private record TypeEntry(String name, String dim, RegionStructure<?, ?> structure) {
    }

    private final List<TypeEntry> types = new ArrayList<>();
    private final Map<String, BiomeSource> biomeSources = new ConcurrentHashMap<>();
    private final Map<String, JsonArray> regionCache = new ConcurrentHashMap<>();
    private String initedFor = null;

    private synchronized boolean ensureInit() {
        DeckConfig c = DeckConfig.get();
        String curSeed = DeckConfig.currentSeed();
        if (curSeed.isBlank()) return false;
        String verName = c.oracleVersions.isEmpty() ? "1.19.2"
                : c.oracleVersions.get(c.oracleVersions.size() - 1);
        String key = curSeed + "|" + verName;
        if (key.equals(initedFor)) return true;

        types.clear();
        biomeSources.clear();
        regionCache.clear();
        MCVersion v = MCVersion.fromString(verName);
        if (v == null) v = MCVersion.v1_19_2;
        long seed = parseSeed(curSeed);

        for (Dimension d : new Dimension[]{Dimension.OVERWORLD, Dimension.NETHER, Dimension.END}) {
            try {
                biomeSources.put(d.getName(), BiomeSource.of(d, v, seed));
            } catch (Throwable t) {
                XaeroDeck.LOG.warn("seed features: no biome source for {}", d.getName());
            }
        }

        // instantiate defensively — skip anything this library build doesn't support
        addType("fortress", "the_nether", v, com.seedfinding.mcfeature.structure.Fortress::new);
        addType("bastion", "the_nether", v, com.seedfinding.mcfeature.structure.BastionRemnant::new);
        addType("monument", "overworld", v, com.seedfinding.mcfeature.structure.Monument::new);
        addType("mansion", "overworld", v, com.seedfinding.mcfeature.structure.Mansion::new);
        addType("village", "overworld", v, com.seedfinding.mcfeature.structure.Village::new);
        addType("outpost", "overworld", v, com.seedfinding.mcfeature.structure.PillagerOutpost::new);
        addType("treasure", "overworld", v, com.seedfinding.mcfeature.structure.BuriedTreasure::new);
        addType("end_city", "the_end", v, com.seedfinding.mcfeature.structure.EndCity::new);
        addType("desert_temple", "overworld", v, com.seedfinding.mcfeature.structure.DesertPyramid::new);
        addType("jungle_temple", "overworld", v, com.seedfinding.mcfeature.structure.JunglePyramid::new);
        addType("witch_hut", "overworld", v, com.seedfinding.mcfeature.structure.SwampHut::new);
        addType("igloo", "overworld", v, com.seedfinding.mcfeature.structure.Igloo::new);
        addType("shipwreck", "overworld", v, com.seedfinding.mcfeature.structure.Shipwreck::new);
        addType("ocean_ruin", "overworld", v, com.seedfinding.mcfeature.structure.OceanRuin::new);
        addType("ruined_portal", "overworld", v,
                ver -> new com.seedfinding.mcfeature.structure.RuinedPortal(Dimension.OVERWORLD, ver));
        addType("ruined_portal_n", "the_nether", v,
                ver -> new com.seedfinding.mcfeature.structure.RuinedPortal(Dimension.NETHER, ver));
        initedFor = key;
        startStrongholdCompute(v, seed);
        return !types.isEmpty();
    }

    // strongholds are ring-based, not region-based — computed once in the background
    private volatile List<int[]> strongholds = null;
    private volatile String strongholdKey = null;

    private void startStrongholdCompute(MCVersion v, long seed) {
        String key = seed + "|" + v.name();
        if (key.equals(strongholdKey)) return;
        strongholdKey = key;
        strongholds = null;
        Thread t = new Thread(() -> {
            try {
                var sh = new com.seedfinding.mcfeature.structure.Stronghold(v);
                BiomeSource bs = biomeSources.get("overworld");
                if (bs == null) return;
                CPos[] starts = sh.getStarts(bs, 128, new com.seedfinding.mcseed.rand.JRand(seed));
                List<int[]> out = new ArrayList<>(starts.length);
                for (CPos c : starts) out.add(new int[]{c.getX() * 16 + 8, c.getZ() * 16 + 8});
                if (key.equals(strongholdKey)) strongholds = out;
                XaeroDeck.LOG.info("seed features: {} strongholds computed", out.size());
            } catch (Throwable e) {
                XaeroDeck.LOG.info("seed features: strongholds unavailable", e);
            }
        }, "xaerodeck-strongholds");
        t.setDaemon(true);
        t.start();
    }

    private void addType(String name, String dim, MCVersion v,
                         Function<MCVersion, RegionStructure<?, ?>> ctor) {
        try {
            RegionStructure<?, ?> s = ctor.apply(v);
            if (s != null) types.add(new TypeEntry(name, dim, s));
        } catch (Throwable t) {
            XaeroDeck.LOG.info("seed features: {} unsupported at this version", name);
        }
    }

    private static long parseSeed(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return s.trim().hashCode();
        }
    }

    /** Structures inside the block box for a dimension path ("overworld"/"the_nether"/"the_end"). */
    public JsonObject features(String dim, int x0, int z0, int x1, int z1) {
        JsonObject resp = new JsonObject();
        JsonArray arr = new JsonArray();
        resp.add("features", arr);
        if (!ensureInit()) {
            resp.addProperty("enabled", false);
            return resp;
        }
        resp.addProperty("enabled", true);
        // clamp the queried area to something sane (in blocks)
        long w = (long) x1 - x0, h = (long) z1 - z0;
        if (w <= 0 || h <= 0 || w > 65536 || h > 65536) return resp;

        long seed = parseSeed(DeckConfig.currentSeed());
        for (TypeEntry t : types) {
            if (!t.dim().equals(dim)) continue;
            try {
                collect(t, seed, x0, z0, x1, z1, arr);
            } catch (Throwable e) {
                XaeroDeck.LOG.debug("seed features: {} failed", t.name(), e);
            }
        }
        if (dim.equals("overworld")) {
            List<int[]> shs = strongholds;
            if (shs != null) {
                for (int[] p : shs) {
                    if (p[0] >= x0 && p[0] <= x1 && p[1] >= z0 && p[1] <= z1) {
                        JsonObject o = new JsonObject();
                        o.addProperty("t", "stronghold");
                        o.addProperty("x", p[0]);
                        o.addProperty("z", p[1]);
                        arr.add(o);
                    }
                }
            }
        }
        if (dim.equals("the_end")) {
            // 20 end gateways on a fixed ring at radius ~96, every 18 degrees
            for (int k = 0; k < 20; k++) {
                double a = 2 * Math.PI * k / 20;
                int gx = (int) Math.round(96 * Math.cos(a));
                int gz = (int) Math.round(96 * Math.sin(a));
                if (gx >= x0 && gx <= x1 && gz >= z0 && gz <= z1) {
                    JsonObject o = new JsonObject();
                    o.addProperty("t", "gateway");
                    o.addProperty("x", gx);
                    o.addProperty("z", gz);
                    arr.add(o);
                }
            }
        }
        return resp;
    }

    private void collect(TypeEntry t, long seed, int x0, int z0, int x1, int z1, JsonArray out) {
        RegionStructure<?, ?> s = t.structure();
        BiomeSource bs = biomeSources.get(t.dim());
        int spacing = s.getSpacing(); // structure-region size in chunks
        int rx0 = Math.floorDiv(x0 >> 4, spacing), rx1 = Math.floorDiv(x1 >> 4, spacing);
        int rz0 = Math.floorDiv(z0 >> 4, spacing), rz1 = Math.floorDiv(z1 >> 4, spacing);
        ChunkRand rand = new ChunkRand();
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                String key = t.name() + "|" + rx + "|" + rz;
                JsonArray cached = regionCache.get(key);
                if (cached == null) {
                    cached = new JsonArray();
                    CPos c = s.getInRegion(seed, rx, rz, rand);
                    if (c != null && (bs == null || canSpawnSafe(s, c, bs))) {
                        JsonObject o = new JsonObject();
                        o.addProperty("t", t.name());
                        o.addProperty("x", c.getX() * 16 + 8);
                        o.addProperty("z", c.getZ() * 16 + 8);
                        cached.add(o);
                    }
                    if (regionCache.size() > 20000) regionCache.clear();
                    regionCache.put(key, cached);
                }
                for (var e : cached) {
                    JsonObject o = e.getAsJsonObject();
                    int fx = o.get("x").getAsInt(), fz = o.get("z").getAsInt();
                    if (fx >= x0 && fx <= x1 && fz >= z0 && fz <= z1) out.add(o);
                }
            }
        }
    }

    private boolean canSpawnSafe(RegionStructure<?, ?> s, CPos c, BiomeSource bs) {
        try {
            return s.canSpawn(c.getX(), c.getZ(), bs);
        } catch (Throwable t) {
            return true; // biome check unsupported — show the candidate anyway
        }
    }
}
