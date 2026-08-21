package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.MapRegionInfo;
import xaero.map.file.RegionDetection;
import xaero.map.region.ExportMapRegion;
import xaero.map.region.ExportMapTileChunk;
import xaero.map.region.MapLayer;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;
import xaero.map.region.texture.ExportLeafRegionTexture;
import xaero.map.world.MapDimension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;

public class TileService {
    public record TileResult(byte[] png, long version) {
    }

    private static final int MAX_CACHE = 256;
    private final Map<String, TileResult> cache = new LinkedHashMap<>(64, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, TileResult> e) {
            return size() > MAX_CACHE;
        }
    };

    public String currentWorldId() {
        WorldMapSession s = WorldMapSession.getCurrentSession();
        return s == null ? null : s.getMapProcessor().getCurrentWorldId();
    }

    /** Resolve a dimension by key ("the_nether", "minecraft:overworld", or "" for current). */
    private MapDimension resolveDim(MapProcessor mp, String key) {
        MapDimension current = mp.getMapWorld().getCurrentDimension();
        if (key == null || key.isEmpty() || key.equals("current")) return current;
        for (MapDimension d : mp.getMapWorld().getDimensionsList()) {
            String id = d.getDimId().identifier().toString();
            String path = d.getDimId().identifier().getPath();
            if (key.equals(id) || key.equals(path)) return d;
        }
        return null;
    }

    private int layerFor(MapProcessor mp, MapDimension dim) {
        // Follow the in-game view for the current dimension; surface for others.
        if (dim == mp.getMapWorld().getCurrentDimension()) return mp.getCurrentCaveLayer();
        return Integer.MAX_VALUE;
    }

    public JsonArray listDimensions() throws Exception {
        return DeckServer.onClientThread(() -> {
            JsonArray arr = new JsonArray();
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session == null) return arr;
            MapProcessor mp = session.getMapProcessor();
            if (mp.getMapWorld() == null) return arr;
            MapDimension current = mp.getMapWorld().getCurrentDimension();
            for (MapDimension d : mp.getMapWorld().getDimensionsList()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", d.getDimId().identifier().toString());
                o.addProperty("key", d.getDimId().identifier().getPath());
                o.addProperty("current", d == current);
                arr.add(o);
            }
            return arr;
        });
    }

    public JsonArray listRegions(String dimKey) throws Exception {
        return DeckServer.onClientThread(() -> {
            JsonArray arr = new JsonArray();
            MapProcessor mp = processor();
            if (mp == null) return arr;
            MapDimension dim = resolveDim(mp, dimKey);
            if (dim == null) return arr;
            MapLayer mapLayer = dim.getLayeredMapRegions().getLayer(layerFor(mp, dim));
            if (mapLayer == null) return arr;
            for (Hashtable<Integer, RegionDetection> col : mapLayer.getDetectedRegions().values()) {
                for (RegionDetection rd : col.values()) {
                    if (!rd.isHasHadTerrain()) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("x", rd.getRegionX());
                    o.addProperty("z", rd.getRegionZ());
                    arr.add(o);
                }
            }
            return arr;
        });
    }

    /** Newest rendered cache for a region on disk, searching surface then nether cave layers. */
    private File[] diskCacheFile(MapProcessor mp, MapDimension dim, int rx, int rz) {
        try {
            String worldId = mp.getCurrentWorldId();
            if (worldId == null) return null;
            String dimFolder = switch (dim.getDimId().identifier().getPath()) {
                case "overworld" -> "null";
                case "the_nether" -> "DIM-1";
                case "the_end" -> "DIM1";
                default -> "dim%" + dim.getDimId().identifier().getPath();
            };
            java.nio.file.Path base = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .resolve("xaero").resolve("world-map").resolve(worldId)
                    .resolve(dimFolder).resolve("mw$default");
            File best = null;
            File surface = base.resolve("cache_1").resolve(rx + "_" + rz + ".xwmc").toFile();
            if (surface.isFile()) best = surface;
            File[] layers = base.resolve("caves").toFile().listFiles(File::isDirectory);
            if (layers != null) {
                for (File l : layers) {
                    File f = new File(l, "cache_1/" + rx + "_" + rz + ".xwmc");
                    if (f.isFile() && (best == null || f.lastModified() > best.lastModified())) best = f;
                }
            }
            return best == null ? null : new File[]{best};
        } catch (Throwable t) {
            return null;
        }
    }

    private int diskLayerOf(File f) {
        File cacheDir = f.getParentFile();
        File layerDir = cacheDir == null ? null : cacheDir.getParentFile();
        if (layerDir != null && layerDir.getParentFile() != null
                && layerDir.getParentFile().getName().equals("caves")) {
            try {
                return Integer.parseInt(layerDir.getName());
            } catch (NumberFormatException ignored) {
            }
        }
        return Integer.MAX_VALUE;
    }

    private MapProcessor processor() {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) return null;
        MapProcessor mp = session.getMapProcessor();
        return mp.getMapWorld() == null ? null : mp;
    }

    private record RawTile(int[] pixels, long version) {
    }

    /**
     * Overview tile: 4x4 regions composited into one 512px image (1px = 4 blocks).
     * ox/oz are region coords divided by 4 (floor).
     */
    public TileResult renderOverview(String dimKey, int ox, int oz) throws Exception {
        long versionAcc = 0;
        TileResult[][] tiles = new TileResult[4][4];
        boolean any = false;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                TileResult t = render(dimKey, ox * 4 + dx, oz * 4 + dz);
                tiles[dx][dz] = t;
                if (t != null) {
                    any = true;
                    versionAcc = versionAcc * 31 ^ t.version();
                }
            }
        }
        if (!any) return null;
        String key = "ov_" + dimKey + "_" + ox + "_" + oz;
        synchronized (cache) {
            TileResult cached = cache.get(key);
            if (cached != null && cached.version() == versionAcc) return cached;
        }
        BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                TileResult t = tiles[dx][dz];
                if (t == null) continue;
                BufferedImage src = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(t.png()));
                g.drawImage(src, dx * 128, dz * 128, 128, 128, null);
            }
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        ImageIO.write(img, "png", out);
        TileResult result = new TileResult(out.toByteArray(), versionAcc);
        synchronized (cache) {
            cache.put(key, result);
        }
        return result;
    }

    /** Level-2 overview: 2x2 level-1 tiles (8x8 regions, 4096 blocks) in one 512px image. */
    public TileResult renderOverview2(String dimKey, int ox, int oz) throws Exception {
        long versionAcc = 0;
        TileResult[][] tiles2 = new TileResult[2][2];
        boolean any = false;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                TileResult t = renderOverview(dimKey, ox * 2 + dx, oz * 2 + dz);
                tiles2[dx][dz] = t;
                if (t != null) {
                    any = true;
                    versionAcc = versionAcc * 31 ^ t.version();
                }
            }
        }
        if (!any) return null;
        String key = "ov2_" + dimKey + "_" + ox + "_" + oz;
        synchronized (cache) {
            TileResult cached = cache.get(key);
            if (cached != null && cached.version() == versionAcc) return cached;
        }
        BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                TileResult t = tiles2[dx][dz];
                if (t == null) continue;
                BufferedImage srcImg = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(t.png()));
                g.drawImage(srcImg, dx * 256, dz * 256, 256, 256, null);
            }
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        ImageIO.write(img, "png", out);
        TileResult result = new TileResult(out.toByteArray(), versionAcc);
        synchronized (cache) {
            cache.put(key, result);
        }
        return result;
    }

    public TileResult render(String dimKey, int rx, int rz) throws Exception {
        String cacheKey = dimKey + "_" + rx + "_" + rz;
        RawTile raw = renderRaw(dimKey, rx, rz, cacheKey);
        if (raw == null) {
            synchronized (cache) {
                return cache.get(cacheKey); // may hold the still-valid previous render
            }
        }
        if (raw.pixels() == null) {
            synchronized (cache) {
                return cache.get(cacheKey);
            }
        }
        // encode PNG off the game thread
        BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 512, 512, raw.pixels(), 0, 512);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(img, "png", out);
        TileResult result = new TileResult(out.toByteArray(), raw.version());
        synchronized (cache) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    /** Runs on the client thread; returns pixels+version, or pixels=null if cache is current. */
    private RawTile renderRaw(String dimKey, int rx, int rz, String cacheKey) throws Exception {
        return DeckServer.onClientThread(() -> {
            MapProcessor mp = processor();
            if (mp == null) return null;
            MapDimension dim = resolveDim(mp, dimKey);
            if (dim == null) return null;
            int layer = layerFor(mp, dim);
            MapLayer mapLayer = dim.getLayeredMapRegions().getLayer(layer);
            if (mapLayer == null) return null;

            boolean isCurrentDim = dim == mp.getMapWorld().getCurrentDimension();
            MapRegion originalRegion = isCurrentDim ? mp.getLeafMapRegion(layer, rx, rz, false) : null;
            MapRegionInfo regionInfo = originalRegion;
            if (regionInfo == null && mapLayer.regionDetectionExists(rx, rz)) {
                regionInfo = mapLayer.getRegionDetection(rx, rz);
            }

            boolean loadingFromCache = originalRegion == null
                    || !originalRegion.isBeingWritten()
                    || originalRegion.getLoadState() != 2;
            File cacheFile = null;
            if (regionInfo != null && loadingFromCache) {
                cacheFile = regionInfo.getCacheFile();
                if (cacheFile == null && !regionInfo.hasLookedForCache()) {
                    try {
                        cacheFile = mp.getMapSaveLoad().getCacheFile(regionInfo, layer, true, false);
                    } catch (Exception ignored) {
                    }
                }
            }
            // No detection (non-current dims never run it): resolve the rendered
            // cache straight from Xaero's disk layout, including nether cave layers.
            if (cacheFile == null && originalRegion == null) {
                File[] found = diskCacheFile(mp, dim, rx, rz);
                if (found != null) {
                    cacheFile = found[0];
                    layer = diskLayerOf(found[0]);
                }
                loadingFromCache = cacheFile != null;
            }
            if (regionInfo == null && cacheFile == null) return null;
            if (cacheFile == null) loadingFromCache = false;

            // Live regions are versioned off their actual change counter (bumped by the
            // MapTileChunk mixin via DirtyRegions), so a tile is only re-rendered when its
            // region really changed rather than every 3s on the client thread. Disk-cache
            // regions still roll with the file mtime plus a 30s highlight bucket.
            long version;
            if (originalRegion != null) {
                version = DirtyRegions.version(dim.getDimId().identifier().getPath(), rx, rz);
            } else {
                version = (cacheFile != null ? cacheFile.lastModified() : 0L)
                        ^ (System.currentTimeMillis() / 30000L << 20);
            }
            synchronized (cache) {
                TileResult cached = cache.get(cacheKey);
                if (cached != null && cached.version() == version) return new RawTile(null, version);
            }

            ExportMapRegion region = new ExportMapRegion(dim, rx, rz, layer, mp.worldBiomeRegistry);
            if (loadingFromCache) {
                region.setShouldCache(true, "xaerodeck");
                region.setHasHadTerrain();
                region.setCacheFile(cacheFile);
                region.loadCacheTextures(mp, mp.worldBiomeRegistry, false, null, 0, null,
                        new boolean[1], 1, mp.getMapSaveLoad().getOldFormatSupport());
            } else if (originalRegion != null) {
                for (int o = 0; o < 8; ++o) {
                    for (int p = 0; p < 8; ++p) {
                        MapTileChunk originalTileChunk = originalRegion.getChunk(o, p);
                        if (originalTileChunk == null || !originalTileChunk.hasHadTerrain()) continue;
                        MapTileChunk tileChunk = region.createTexture(o, p).getTileChunk();
                        for (int tx = 0; tx < 4; ++tx) {
                            for (int tz = 0; tz < 4; ++tz) {
                                tileChunk.setTile(tx, tz, originalTileChunk.getTile(tx, tz),
                                        mp.getBlockStateShortShapeCache(), mp);
                            }
                        }
                        tileChunk.setLoadState((byte) 2);
                        tileChunk.updateBuffers(mp, mp.getWorldBlockTintProvider(), mp.getOverlayManager(),
                                false, mp.getBlockStateShortShapeCache(),
                                new xaero.map.region.MapUpdateFastConfig(mp));
                    }
                }
            } else {
                return null;
            }

            // Bake in map highlights (e.g. XaeroPlus NewChunks / portals) like the in-game map.
            try {
                mp.getMapRegionHighlightsPreparer().prepare(region, true);
            } catch (Throwable t) {
                XaeroDeck.LOG.debug("highlight prepare failed", t);
            }

            int[] pixels = new int[512 * 512];
            boolean any = false;
            for (int cx = 0; cx < 8; ++cx) {
                for (int cz = 0; cz < 8; ++cz) {
                    ExportMapTileChunk chunk = region.getChunk(cx, cz);
                    if (chunk == null) continue;
                    ExportLeafRegionTexture tex = chunk.getLeafTexture();
                    if (tex == null) continue;
                    try {
                        tex.applyHighlights(dim.getHighlightHandler(), tex.getColorBuffer());
                    } catch (Throwable ignored) {
                    }
                    ByteBuffer buf = tex.getDirectColorBuffer();
                    if (buf == null) continue;
                    any = true;
                    for (int py = 0; py < 64; ++py) {
                        int rowBase = (cz * 64 + py) * 512 + cx * 64;
                        for (int px = 0; px < 64; ++px) {
                            int idx = (py * 64 + px) * 4;
                            int r = buf.get(idx) & 0xFF;
                            int g = buf.get(idx + 1) & 0xFF;
                            int b = buf.get(idx + 2) & 0xFF;
                            pixels[rowBase + px] = 0xFF000000 | (r << 16) | (g << 8) | b;
                        }
                    }
                    tex.deleteColorBuffer();
                }
            }
            if (!any) return null;
            return new RawTile(pixels, version);
        });
    }
}
