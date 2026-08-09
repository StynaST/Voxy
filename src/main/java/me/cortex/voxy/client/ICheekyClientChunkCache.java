package me.cortex.voxy.client;

import net.minecraft.world.chunk.Chunk;
import org.jetbrains.annotations.Nullable;

/**
 * Implemented by the mixin on 1.12.2's {@code ChunkProviderClient} (upstream: {@code ClientChunkCache}).
 * jspecify is not on the 1.12.2 classpath, so the annotation comes from jetbrains instead.
 */
public interface ICheekyClientChunkCache {
    @Nullable
    Chunk voxy$cheekyGetChunk(int x, int z);
}
