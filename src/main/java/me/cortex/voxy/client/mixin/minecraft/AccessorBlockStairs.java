package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Port note: the model bakery renders stairs as the block they are cut from, so a stair LOD carries
 * the base material's texture. Upstream reads {@code StairBlock.baseState}, which is public; 1.12.2
 * keeps the same thing in {@code BlockStairs.modelState}, which is private.
 */
@Mixin(BlockStairs.class)
public interface AccessorBlockStairs {
    @Accessor("modelState")
    IBlockState voxy$getModelState();
}
