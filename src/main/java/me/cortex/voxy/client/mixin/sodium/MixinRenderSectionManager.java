package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.ingest.ChunkUpdateQueue;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.NibbleArray;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkStatus;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Celeritas keeps the level on the 1.12.2 subclass, so the world comes from Minecraft.world instead
@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) return;
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) return;
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
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) return;
        var world = Minecraft.getMinecraft().world;
        if (world == null) return;
        //By the time the render graph drops it, the normal lookup considers it out of range
        var chunk = ((ICheekyClientChunkCache) world.getChunkProvider()).voxy$cheekyGetChunk(x, z);
        if (chunk != null) {
            VoxelIngestService.tryAutoIngestChunk(chunk);
        }
    }

    @Redirect(method = "updateSectionInfo",
            at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/render/chunk/RenderSection;setInfo(Lorg/embeddedt/embeddium/impl/render/chunk/data/BuiltRenderSectionData;)Z"))
    private boolean voxy$updateOnUpload(RenderSection instance, @Nullable BuiltRenderSectionData info) {
        boolean changed = instance.setInfo(info);

        //Celeritas has no isInvisible() flag; changed-and-now-carries-data is the same event
        if (!changed || info == null || !VoxyConfig.CONFIG.ingestEnabled ||
                !Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
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
        //Never cache this per chunk pos: block data arrives before light, so a chunk first seen as
        //FLAG_HAS_BLOCK_DATA would stay cached as incomplete and never ingest
        var chunkStatus = ((AccessorChunkTracker) tracker).voxy$getChunkStatus();
        if (chunkStatus.get(ChunkPos.asLong(x, z)) != ChunkStatus.FLAG_ALL) {
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
            //rawIngest cannot express a clear, so let the chunk path zero it
            ChunkUpdateQueue.enqueue(x, z);
            return changed;
        }

        //1.12.2 light lives in the section itself, and NibbleArray has no copy()
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
