package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.GPUSelectorWindows2;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.util.ThreadUtils;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startup hooks that have to straddle display creation.
 *
 * <p>Port note: upstream splits these across {@code util/MixinGPUSelect} (which injects into
 * {@code Minecraft}'s constructor, before the window exists) and {@code MixinRenderSystem} (which
 * injects after the renderer is up). 1.12.2 does both inside {@code Minecraft#init}: the GPU has to
 * be picked before {@code createDisplay()}, and Voxy's own init needs the GL context that call
 * creates, so HEAD and TAIL of the same method are the two points.
 *
 * <p>This replaces the earlier arrangement where {@code ClientProxy} ran Voxy's GL init from
 * {@code FMLPostInitializationEvent} and simply assumed a context existed by then.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftStartup {

    @Inject(method = "init", at = @At("HEAD"))
    private void voxy$beforeDisplayCreated(CallbackInfo ci) {
        var prop = System.getProperty("voxy.forceGpuSelectionIndex", "NO");
        if (!prop.equals("NO")) {
            //Must happen before the display exists, otherwise the context is already on a GPU
            GPUSelectorWindows2.doSelector(Integer.parseInt(prop));
        }

        //Force the current thread priority to be realtime
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        ThreadUtils.SetSelfThreadPriorityWin32(ThreadUtils.WIN32_THREAD_PRIORITY_TIME_CRITICAL);
    }

    //Right after the GL context exists and before anything that reads Voxy's config. RETURN of init
    //is far too late: RenderGlobal is constructed ~70 lines earlier, and its loadRenderers call
    //reaches voxy$createRenderer, which touches VoxyConfig.CONFIG. That static initializes to the
    //"voxy unavailable" fallback while the instance factory is still unset, and it is a singleton --
    //so the whole mod stays disabled for the session with only "Not creating renderer due to
    //disabled" in the log.
    @Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;initializeTextures()V", shift = At.Shift.AFTER))
    private void voxy$afterDisplayCreated(CallbackInfo ci) {
        try {
            VoxyClient.initVoxyClient();
        } catch (Throwable t) {
            //Log through log4j directly, NOT Voxy's Logger: if init failed because a Voxy class
            //failed to initialize, touching another one here turns a soft failure into a
            //NoClassDefFoundError and takes the game down.
            LogManager.getLogger("Voxy").error("Failed to initialize voxy, it will be unavailable", t);
        }
    }
}
