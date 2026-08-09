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
            voxy$traceGate("pass " + renderPass + " has no fragment discard");
            return;
        }
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) {
            voxy$traceGate("no render system on the holder");
            return;
        }

        var mc = Minecraft.getMinecraft();
        var framebuffer = mc.getFramebuffer();
        int depthTexture = IVoxyDepthTextureHolder.getMainDepthTexture();
        if (framebuffer == null || depthTexture == -1) {
            voxy$traceGate("framebuffer=" + framebuffer + " depthTexture=" + depthTexture);
            return;
        }
        voxy$traceGate("drawing, depthTexture=" + depthTexture);
        voxy$traceCamera(camera);
        voxy$traceGlState("before");

        var viewport = renderer.setupViewport(
                JomlConv.toVoxy(matrices.projection()),
                JomlConv.toVoxy(matrices.modelView()),
                FogParameters.captureFromGameState(),
                framebuffer.framebufferTextureWidth,
                framebuffer.framebufferTextureHeight,
                camera.x, camera.y, camera.z);

        renderer.renderOpaque(viewport, depthTexture, framebuffer.framebufferTexture);
        voxy$traceGlState("after");
    }

    //Camera height, bucketed, so "nothing renders when high up" can be tied to an actual altitude.
    @org.spongepowered.asm.mixin.Unique
    private static final java.util.Set<Integer> voxy$seenCameraBuckets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.spongepowered.asm.mixin.Unique
    private static void voxy$traceCamera(CameraTransform camera) {
        int bucket = (int) Math.floor(camera.y / 32.0);
        if (voxy$seenCameraBuckets.add(bucket)) {
            me.cortex.voxy.common.Logger.info("[render gate] camera y~" + (bucket * 32) + ".." + (bucket * 32 + 31));
        }
    }

    //GL state around Voxy's draw, for the GUI corruption. Off unless -Dvoxy.glStateDebug=true,
    //because the glGet calls stall the driver every frame.
    @org.spongepowered.asm.mixin.Unique
    private static final boolean VOXY_GL_STATE_DEBUG = Boolean.getBoolean("voxy.glStateDebug");

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.Set<String> voxy$seenGlStates = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.spongepowered.asm.mixin.Unique
    private static void voxy$traceGlState(String when) {
        if (!VOXY_GL_STATE_DEBUG) return;
        int active = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        StringBuilder sb = new StringBuilder();
        sb.append("[gl state ").append(when).append("] active=").append(active - org.lwjgl.opengl.GL13.GL_TEXTURE0);
        for (int i = 0; i < 3; i++) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + i);
            sb.append(" tex").append(i).append('=')
              .append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D))
              .append('/')
              .append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_TEXTURE_2D) ? "on" : "off");
        }
        org.lwjgl.opengl.GL13.glActiveTexture(active);
        sb.append(" prog=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM));
        sb.append(" blend=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND) ? "on" : "off");
        sb.append(" depth=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST) ? "on" : "off");
        sb.append(" depthFunc=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DEPTH_FUNC));
        sb.append(" alpha=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_ALPHA_TEST) ? "on" : "off");
        sb.append(" vao=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING));
        String line = sb.toString();
        if (voxy$seenGlStates.add(line)) {
            me.cortex.voxy.common.Logger.info(line);
        }
    }

    /**
     * Reports, once per distinct reason, why this frame did or did not reach {@code renderOpaque}.
     *
     * <p>Every gate above is silent and per-frame, so "no LOD terrain on screen" looks identical
     * whether the pass is wrong, the holder is empty, the depth texture failed to attach, or the
     * draw ran and produced nothing. Deduplicating on the message keeps this to a handful of lines
     * per session while still distinguishing those cases.
     */
    @org.spongepowered.asm.mixin.Unique
    private static final java.util.Set<String> voxy$seenGates = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.spongepowered.asm.mixin.Unique
    private static void voxy$traceGate(String reason) {
        if (voxy$seenGates.add(reason)) {
            me.cortex.voxy.common.Logger.info("[render gate] " + reason);
        }
    }
}
