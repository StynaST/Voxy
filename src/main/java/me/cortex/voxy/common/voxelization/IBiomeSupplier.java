package me.cortex.voxy.common.voxelization;

import net.minecraft.world.biome.Biome;

/**
 * Port note: upstream reads biomes out of a {@code PalettedContainerRO<Holder<Biome>>}, which is a
 * 4x4x4 grid per section. 1.12.2 has no 3D biomes at all -- a chunk carries a flat {@code byte[256]}
 * of biome ids indexed by (x, z), constant down the whole column.
 *
 * <p>So the supplier is 2D. Coordinates are section-local block coordinates, 0..15.
 */
public interface IBiomeSupplier {
    Biome supply(int x, int z);
}
