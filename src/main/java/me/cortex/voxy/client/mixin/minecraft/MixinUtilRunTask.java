package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.LoadException;
import net.minecraft.util.Util;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes a Voxy {@link LoadException} raised on a scheduled task crash the game instead of being
 * logged and stepped over.
 *
 * <p>Port note: upstream redirects {@code BlockableEventLoop#isNonRecoverable}, the modern
 * "should this kill the game" hook. 1.12.2 has no such concept — {@link Util#runTask} catches the
 * failure and logs it fatal — so the equivalent is to redirect that log call and rethrow when the
 * cause is ours. Voxy's loading failures must be loud: swallowing one leaves the renderer
 * half-constructed and produces a far more confusing crash later.
 */
@Mixin(Util.class)
public class MixinUtilRunTask {

    @Redirect(method = "runTask", at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/Logger;fatal(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private static void voxy$forceCrashOnLoadError(Logger logger, String message, Throwable thrown) {
        //`thrown` is the ExecutionException (or InterruptedException) the task failed with
        Throwable cause = thrown.getCause();
        if (cause instanceof LoadException loadException) {
            if (loadException.getCause() instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw loadException;
        }
        logger.fatal(message, thrown);
    }
}
