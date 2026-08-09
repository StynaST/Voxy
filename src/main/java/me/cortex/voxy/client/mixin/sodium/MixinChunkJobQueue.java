package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.compat.SemaphoreBlockImpersonator;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.thread.MultiThreadPrioritySemaphore;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Semaphore;

/**
 * Puts Celeritas' chunk build workers on Voxy's shared thread pool, so the two do not oversubscribe
 * the CPU. This is what {@code dontUseSodiumBuilderThreads} switches off.
 *
 * <p>Port note: the class is package-private in Celeritas as it is in Sodium, hence the string
 * target. The semaphore is a field initializer here rather than a constructor argument, but a
 * {@code NEW} redirect catches it either way.
 */
@Mixin(targets = {"org.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobQueue"}, remap = false)
public class MixinChunkJobQueue {
    @Unique private MultiThreadPrioritySemaphore.Block voxy$semaphoreBlock;

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(I)Ljava/util/concurrent/Semaphore;"))
    private Semaphore voxy$injectUnifiedPool(int permits) {
        var instance = VoxyCommon.getInstance();
        if (instance != null && !VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            this.voxy$semaphoreBlock = instance.getThreadPool().groupSemaphore.createBlock();
            return new SemaphoreBlockImpersonator(this.voxy$semaphoreBlock);
        }
        return new Semaphore(permits);
    }

    @Inject(method = "shutdown", at = @At("RETURN"))
    private void voxy$injectAtShutdown(CallbackInfoReturnable<?> ci) {
        if (this.voxy$semaphoreBlock != null) {
            this.voxy$semaphoreBlock.free();
        }
    }
}
