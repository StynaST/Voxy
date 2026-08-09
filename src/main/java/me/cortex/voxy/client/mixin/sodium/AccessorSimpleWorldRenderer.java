package me.cortex.voxy.client.mixin.sodium;

import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Port note: upstream mixes into Sodium's {@code SodiumWorldRenderer} to read its private
 * {@code renderSectionManager}. Celeritas' equivalent is {@link SimpleWorldRenderer}, where the
 * field is {@code protected} and declared on the generic base class, so the accessor is declared
 * there rather than on the 1.12.2 {@code CeleritasWorldRenderer} subclass.
 *
 * <p>The field's type variable erases to {@link RenderSectionManager}, which is what the accessor
 * has to return for the descriptor to match. No refmap entry is involved: this targets a Celeritas
 * class, not an obfuscated Minecraft one.
 */
@Mixin(SimpleWorldRenderer.class)
public interface AccessorSimpleWorldRenderer {
    @Accessor("renderSectionManager")
    RenderSectionManager voxy$getRenderSectionManager();
}
