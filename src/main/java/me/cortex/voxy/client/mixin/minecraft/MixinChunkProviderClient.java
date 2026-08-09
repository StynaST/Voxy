package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.chunk.Chunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Port note: upstream needs this because modern {@code ClientChunkCache} stores chunks in a ring
 * buffer whose public getter range-checks and whose slots can hold a chunk from another position, so
 * it reads {@code storage} directly and verifies the position itself.
 *
 * <p>1.12.2 keeps a plain position-keyed map and {@code getLoadedChunk} is already the exact,
 * unchecked lookup, so this only has to expose it under the interface the rest of Voxy calls.
 * Deliberately no {@code @Shadow}: shadowed fields do not reliably reach the refmap (see
 * {@code MixinWorld}), and none is needed here.
 */
@Mixin(ChunkProviderClient.class)
public class MixinChunkProviderClient implements ICheekyClientChunkCache {

    @Override
    public @Nullable Chunk voxy$cheekyGetChunk(int x, int z) {
        return ((ChunkProviderClient) (Object) this).getLoadedChunk(x, z);
    }
}
