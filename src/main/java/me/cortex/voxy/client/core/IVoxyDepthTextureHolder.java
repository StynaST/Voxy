package me.cortex.voxy.client.core;

import net.minecraft.client.Minecraft;

/**
 * Implemented by the mixin on 1.12.2's {@code Framebuffer}, which has no sampleable depth attachment
 * of its own. See {@code MixinFramebufferDepthTexture}.
 */
public interface IVoxyDepthTextureHolder {
    /** GL id of the depth texture, or -1 if the framebuffer has no depth. */
    int voxy$getDepthTexture();

    static int getMainDepthTexture() {
        var framebuffer = Minecraft.getMinecraft().getFramebuffer();
        return framebuffer == null ? -1 : ((IVoxyDepthTextureHolder) framebuffer).voxy$getDepthTexture();
    }
}
