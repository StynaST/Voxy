package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.IVoxyDepthTextureHolder;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;

/**
 * Gives the framebuffer a depth <b>texture</b> alongside its depth renderbuffer, so Voxy can sample
 * scene depth when compositing LOD terrain against vanilla terrain.
 *
 * <p>Port note: upstream never needs this — modern Minecraft's render target already stores depth in
 * a texture, and Sodium hands Voxy {@code getDepthTextureView().glId()}. 1.12.2 attaches depth as a
 * renderbuffer (see {@code Framebuffer#createFramebuffer}), which cannot be sampled.
 *
 * <p><b>Stencil matters.</b> When a mod has called {@code enableStencil()}, vanilla allocates one
 * {@code DEPTH24_STENCIL8} renderbuffer and binds it to <i>both</i> the depth and stencil
 * attachments. Replacing only the depth half with a depth-only texture leaves the two attachments
 * disagreeing, the framebuffer goes incomplete, and every subsequent draw raises
 * {@code GL_INVALID_FRAMEBUFFER_OPERATION} (1286) — which is exactly what the first test run did.
 * So the texture matches the framebuffer: packed depth-stencil when stencil is on, depth-only
 * otherwise, bound to {@code GL_DEPTH_STENCIL_ATTACHMENT} and sampled explicitly as its depth half.
 * Any mod can enable stencil at any time (ModularUI does), so the packed path is not an edge case.
 *
 * <p>The renderbuffer itself is left allocated and untouched, so anything that reads or deletes
 * {@code depthBuffer} still behaves. If the swap still leaves the framebuffer incomplete, the
 * original attachment is restored and Voxy simply reports no depth texture — a bug here must not
 * be able to break the game's rendering.
 */
@Mixin(Framebuffer.class)
public abstract class MixinFramebufferDepthTexture implements IVoxyDepthTextureHolder {
    //The fields read below are public on Framebuffer, so they are reached through a cast rather
    // than @Shadow -- shadowed fields do not reliably reach the refmap (see MixinWorld), while a
    // plain field reference is remapped by reobf like any other call into Minecraft.
    @Unique private int voxy$depthTexture = -1;

    @Override
    public int voxy$getDepthTexture() {
        return this.voxy$depthTexture;
    }

    @Inject(method = "createFramebuffer", at = @At("RETURN"))
    private void voxy$attachDepthTexture(int width, int height, CallbackInfo ci) {
        var self = (Framebuffer) (Object) this;
        if (!self.useDepth || self.framebufferObject <= 0) {
            return;
        }

        boolean stencil = self.isStencilEnabled();

        if (this.voxy$depthTexture == -1) {
            this.voxy$depthTexture = glGenTextures();
        }
        //Not raw glBindTexture: GlStateManager caches per unit and would skip vanilla's next bind
        GlStateManager.bindTexture(this.voxy$depthTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        //Depth comparison off: Voxy reads the raw depth value, it does not use a shadow sampler
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        if (stencil) {
            //Sample the depth half, not the stencil half; the default is not worth relying on
            glTexParameteri(GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH24_STENCIL8, self.framebufferTextureWidth, self.framebufferTextureHeight, 0, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, (java.nio.ByteBuffer) null);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, self.framebufferTextureWidth, self.framebufferTextureHeight, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        }
        GlStateManager.bindTexture(0);

        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, self.framebufferObject);

        if (stencil) {
            //One packed texture goes on the combined attachment point
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, this.voxy$depthTexture, 0);
        } else {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, this.voxy$depthTexture, 0);
        }

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("Depth texture attachment left the framebuffer incomplete (0x" + Integer.toHexString(status) + "), reverting to the vanilla renderbuffer. Voxy will render without depth compositing.");
            voxy$restoreRenderbuffer(self, stencil);
            glDeleteTextures(this.voxy$depthTexture);
            this.voxy$depthTexture = -1;
        }

        glBindFramebuffer(GL_FRAMEBUFFER, previous);
    }

    @Unique
    private static void voxy$restoreRenderbuffer(Framebuffer self, boolean stencil) {
        if (stencil) {
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, self.depthBuffer);
        } else {
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, self.depthBuffer);
        }
    }

    @Inject(method = "deleteFramebuffer", at = @At("HEAD"))
    private void voxy$deleteDepthTexture(CallbackInfo ci) {
        if (this.voxy$depthTexture != -1) {
            glDeleteTextures(this.voxy$depthTexture);
            this.voxy$depthTexture = -1;
        }
    }
}
