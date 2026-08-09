package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.commonImpl.VoxyCommon;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy shares Celeritas' chunk-builder threads, so its own pool has to be resized whenever Celeritas
 * rebuilds its renderer. Upstream hooks {@code SodiumWorldRenderer#initRenderer} for the same reason.
 */
@Mixin(value = SimpleWorldRenderer.class, remap = false)
public class MixinSimpleWorldRenderer {
    @Inject(method = "initRenderer", at = @At("TAIL"))
    private void voxy$injectThreadUpdate(CommandList commandList, CallbackInfo ci) {
        var vi = VoxyCommon.getInstance();
        if (vi != null) vi.updateDedicatedThreads();
    }
}
