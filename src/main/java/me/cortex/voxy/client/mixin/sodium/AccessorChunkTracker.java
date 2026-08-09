package me.cortex.voxy.client.mixin.sodium;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The tracker's per-column status flags. A column only has all its neighbours once the status
 * reaches {@code 3}, which is when a section is safe to ingest — Celeritas keeps that map private,
 * exactly as Sodium does upstream.
 */
@Mixin(value = ChunkTracker.class, remap = false)
public interface AccessorChunkTracker {
    @Accessor("chunkStatus")
    Long2IntOpenHashMap voxy$getChunkStatus();
}
