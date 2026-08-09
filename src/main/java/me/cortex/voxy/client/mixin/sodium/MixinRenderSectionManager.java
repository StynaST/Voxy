package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.NibbleArray;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds Voxy's ingest service from Celeritas' chunk lifecycle: a chunk entering the render graph, a
 * chunk leaving it, and a section finishing a build.
 *
 * <p>Port note: upstream mixes Sodium's {@code RenderSectionManager}, which holds a {@code level}
 * field. Celeritas' base manager is generic over the world type and keeps it on the 1.12.2 subclass,
 * so the world comes from {@code Minecraft.world} instead — on a 1.12.2 client there is exactly one.
 *
 * <p>Upstream skips ingest-on-removal when Bobby is installed, since Bobby keeps the chunk around.
 * Bobby has no 1.12.2 build, so that path is always taken here.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) return;
        var world = Minecraft.getMinecraft().world;
        if (world == null) return;
        var chunk = world.getChunkProvider().getLoadedChunk(x, z);
        if (chunk != null) {
            VoxelIngestService.tryAutoIngestChunk(chunk);
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$ingestOnRemove(int x, int z, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) return;
        var world = Minecraft.getMinecraft().world;
        if (world == null) return;
        //Get the chunk at all costs: by the time the render graph drops it, the normal lookup may
        // already consider it out of range
        var chunk = ((ICheekyClientChunkCache) world.getChunkProvider()).voxy$cheekyGetChunk(x, z);
        if (chunk != null) {
            VoxelIngestService.tryAutoIngestChunk(chunk);
        }
    }

    @Unique private long voxy$cachedChunkPos = -1;
    @Unique private int voxy$cachedChunkStatus;

    @Redirect(method = "updateSectionInfo",
            at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/render/chunk/RenderSection;setInfo(Lorg/embeddedt/embeddium/impl/render/chunk/data/BuiltRenderSectionData;)Z"))
    private boolean voxy$updateOnUpload(RenderSection instance, @Nullable BuiltRenderSectionData info) {
        boolean changed = instance.setInfo(info);

        //Upstream keys off Sodium's isInvisible() flipping. Celeritas has no such flag; a section
        // that changed and now carries data is the same event.
        if (!changed || info == null || !VoxyConfig.CONFIG.ingestEnabled) {
            return changed;
        }
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) {
            return changed;
        }

        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();
        var world = Minecraft.getMinecraft().world;
        if (world == null) {
            return changed;
        }

        var tracker = ChunkTrackerHolder.get(world);
        if (tracker == null) {
            return changed;
        }
        var chunkStatus = ((AccessorChunkTracker) tracker).voxy$getChunkStatus();
        //In theory the cached value could be wrong, but that is so unlikely, and at worst it either
        // ingests a chunk twice or not at all, both of which are fine
        long key = ChunkPos.asLong(x, z);
        if (key != this.voxy$cachedChunkPos) {
            this.voxy$cachedChunkPos = key;
            this.voxy$cachedChunkStatus = chunkStatus.get(key);
        }
        if (this.voxy$cachedChunkStatus != 3) {//Only ingest while the chunk still has its neighbours
            return changed;
        }

        var chunk = world.getChunkProvider().getLoadedChunk(x, z);
        if (chunk == null) {
            return changed;
        }
        var sections = chunk.getBlockStorageArray();
        if (y < 0 || y >= sections.length) {
            return changed;
        }
        var section = sections[y];
        if (section == null) {
            return changed;
        }

        //Port note: 1.12.2 light lives in the section itself, not in a light engine keyed by
        // SectionPos, and NibbleArray has no copy()
        VoxelIngestService.rawIngest(vrs.getEngine(), section, x, y, z,
                voxy$copyNibble(section.getBlockLight()),
                voxy$copyNibble(section.getSkyLight()),
                chunk.getBiomeArray().clone());

        return changed;
    }

    @Unique
    private static NibbleArray voxy$copyNibble(NibbleArray array) {
        return array == null ? null : new NibbleArray(array.getData().clone());
    }
}
