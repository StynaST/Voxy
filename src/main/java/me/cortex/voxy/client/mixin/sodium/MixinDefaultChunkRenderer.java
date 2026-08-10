package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IVoxyDepthTextureHolder;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.FogParameters;
import me.cortex.voxy.client.core.util.JomlConv;
import net.minecraft.client.Minecraft;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.embeddedt.embeddium.impl.render.chunk.DefaultChunkRenderer;
import org.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Voxy's LOD terrain inside Celeritas' cutout terrain pass, which is where upstream draws it
 * inside Sodium's.
 *
 * <p>Port note: three things differ from upstream. Celeritas' {@code render} has the older
 * Embeddium signature (no fog parameters, no GPU sampler or uniform slices), so the fog parameters
 * come from Voxy's own {@code FogParameters} built from game state. Render passes have no
 * {@code getTarget()}, so the framebuffer is Minecraft's own. And its depth attachment is a
 * renderbuffer, so the sampleable copy comes from {@link IVoxyDepthTextureHolder} (see
 * {@code MixinFramebufferDepthTexture}).
 *
 * <p>Upstream's {@code disableSodiumChunkRender()} path is not reproduced: it is a debug toggle that
 * is hardcoded to false, and cancelling Celeritas' render would need its private state.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {

    //Owner must be DefaultChunkRenderer, not ShaderChunkRenderer where end() is declared: render()
    //compiles the invokevirtual against its own type. Mixin matches INVOKE on owner+name+desc, and
    //defaultRequire is 0, so the declaring class matched nothing and applied silently doing nothing.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/render/chunk/DefaultChunkRenderer;end(Lorg/embeddedt/embeddium/impl/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE), require = 1)
    private void voxy$injectRender(ChunkRenderMatrices matrices,
                                   CommandList commandList,
                                   ChunkRenderListIterable renderLists,
                                   TerrainRenderPass renderPass,
                                   CameraTransform occlusionCamera,
                                   CameraTransform camera,
                                   CallbackInfo ci) {
        if (!renderPass.supportsFragmentDiscard()) {
            //Only the cutout pass, matching upstream's DefaultTerrainRenderPasses.CUTOUT check
            return;
        }
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) {
            return;
        }

        var mc = Minecraft.getMinecraft();
        var framebuffer = mc.getFramebuffer();
        int depthTexture = IVoxyDepthTextureHolder.getMainDepthTexture();
        if (framebuffer == null || depthTexture == -1) {
            return;
        }

        //Celeritas' own matrices: what the vanilla chunks in the depth buffer were drawn with
        var vanillaProjection = JomlConv.toVoxy(matrices.projection());
        var viewport = renderer.setupViewport(
                vanillaProjection,
                JomlConv.toVoxy(matrices.modelView()),
                FogParameters.captureFromGameState(),
                framebuffer.framebufferTextureWidth,
                framebuffer.framebufferTextureHeight,
                camera.x, camera.y, camera.z);

        renderer.renderOpaque(viewport, depthTexture, framebuffer.framebufferTexture);
    }
}
