package me.cortex.voxy.commonImpl.mixin.minecraft;

import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.IBlockStatePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Port note: upstream Voxy reads {@code PalettedContainer.data.palette} and
 * {@code .storage} directly, which its accesswidener opens up. 1.12.2's equivalent is
 * {@link BlockStateContainer}, whose {@code palette} and {@code storage} fields are protected.
 *
 * <p>This is an accessor mixin rather than an access transformer entry so the refmap resolves the
 * SRG names during the build -- writing {@code field_186021_b} by hand into an AT is easy to get
 * wrong and fails in a way that is hard to attribute.
 *
 * <p>Reading these directly matters: this is the per-chunk-section voxelization hot path, and the
 * public {@code get(x, y, z)} accessor does a bounds-checked BitArray read plus a palette lookup
 * for every one of the 4096 blocks.
 */
@Mixin(BlockStateContainer.class)
public interface AccessorBlockStateContainer {
    @Accessor("storage")
    BitArray voxy$getStorage();

    @Accessor("palette")
    IBlockStatePalette voxy$getPalette();
}
