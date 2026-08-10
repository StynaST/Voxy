package me.cortex.voxy.client.mixin.compat;

import me.cortex.voxy.client.compat.Weather2Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Weather2 Remastered overwrites {@code farPlaneDistance} at the RETURN of setupCameraTransform with
 * {@code renderDistanceChunks * 4 * 1.25}, and renderWorldPass rebuilds the GL projection from it
 * around the sky -- clipping terrain at ~85 blocks and desyncing GL from the matrix Celeritas uses.
 * Restoring it at the call site avoids racing W2R's own injector.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinWeather2FarPlane {
    @Shadow private float farPlaneDistance;

    @Inject(method = "renderWorldPass", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;setupCameraTransform(FI)V", shift = At.Shift.AFTER))
    private void voxy$restoreVanillaFarPlane(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (!Weather2Compat.PRESENT) return;

        float vanilla = Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16.0f;
        if (this.farPlaneDistance == vanilla) return;

        Weather2Compat.logFarPlaneRestore(this.farPlaneDistance, vanilla);
        this.farPlaneDistance = vanilla;
    }
}
