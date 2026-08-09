package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pushes vanilla's render-distance fog out to infinity while Voxy is drawing, so terrain does not
 * fade out at the edge of the vanilla render distance with LODs visible beyond it.
 *
 * <p>Port note: upstream mixes the modern {@code FogRenderer} and edits the {@code FogData} it
 * returns. 1.12.2 has no fog object — {@code EntityRenderer#setupFog} programs fixed-function GL fog
 * directly — so this runs after it and overwrites the GL state. Priority is below Celeritas' own fog
 * handling for the same reason upstream sits below Sodium's.
 */
@Mixin(value = EntityRenderer.class, priority = 900)
public class MixinEntityRendererFog {
    @org.spongepowered.asm.mixin.Unique
    private static final int GL_LINEAR = 9729;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void voxy$modifyFog(int startCoords, float partialTicks, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) return;
        if (IVoxyRenderSystemHolder.getNullable() == null) return;

        var fog = GlStateManager.fogState;
        //Water, lava and cloud fog are EXP/EXP2 and are the effect itself, not a draw-distance
        //limit, so they are left exactly as vanilla set them -- which is also what makes night
        //vision underwater reach as far in the LODs as it does in vanilla chunks. Only linear fog
        //is the render-distance cutoff Voxy needs to push past. Checking the mode matters: for
        //exponential fog start/end are unused, so the old `end < 10` test read a stale value and
        //could either skip a real cutoff or stomp the underwater effect.
        if (fog.mode != GL_LINEAR) return;

        //Upstream leaves environmental fog alone when it is very close, since that fog is the
        //effect itself rather than a draw-distance limit
        boolean fogIsDamnClose = fog.end < 10;
        if (!VoxyConfig.CONFIG.useEnvironmentalFog && !fogIsDamnClose) {
            GlStateManager.setFogStart(99999999f);
            GlStateManager.setFogEnd(99999999f);
            return;
        }
        if (!fogIsDamnClose) {
            //Vanilla's fog here is the render distance cutoff, which Voxy renders past
            GlStateManager.setFogStart(999999999f);
            GlStateManager.setFogEnd(999999999f);
        }
    }
}
