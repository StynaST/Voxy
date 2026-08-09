package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Port note: upstream reads the lightmap straight off {@code gameRenderer.levelLightmap()}. 1.12.2
 * keeps it as a private {@code DynamicTexture} on {@link EntityRenderer}, and the only other route
 * to it -- the dynamic texture's ResourceLocation -- is private as well.
 */
@Mixin(EntityRenderer.class)
public interface AccessorEntityRenderer {
    @Accessor("lightmapTexture")
    DynamicTexture voxy$getLightmapTexture();
}
