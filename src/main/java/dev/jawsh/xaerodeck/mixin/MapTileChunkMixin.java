package dev.jawsh.xaerodeck.mixin;

import dev.jawsh.xaerodeck.DirtyRegions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.MapTileChunk;

@Mixin(value = MapTileChunk.class, remap = false)
public class MapTileChunkMixin {
    /** Fires whenever Xaero rebuilds a tile chunk's pixels — the moment map content changes. */
    @Inject(method = "updateBuffers", at = @At("RETURN"), require = 0)
    private void xaerodeck$markDirty(CallbackInfo ci) {
        try {
            MapTileChunk self = (MapTileChunk) (Object) this;
            xaero.map.region.MapRegion region = self.getInRegion();
            if (region == null || region instanceof xaero.map.region.ExportMapRegion) return;
            String dim = region.getDim().getDimId().identifier().getPath();
            DirtyRegions.mark(dim, self.getX() >> 3, self.getZ() >> 3);
        } catch (Throwable ignored) {
        }
    }
}
