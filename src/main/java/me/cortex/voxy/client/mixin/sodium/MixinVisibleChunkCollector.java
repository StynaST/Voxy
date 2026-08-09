package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.util.SectionPos;
import org.embeddedt.embeddium.impl.render.chunk.lists.VisibleChunkCollector;
import org.embeddedt.embeddium.impl.render.chunk.occlusion.OcclusionNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records which sections vanilla/Celeritas terrain actually drew this frame, so Voxy can skip
 * drawing LODs where real terrain already covers the view.
 *
 * <p>Port note: upstream redirects {@code RenderRegionManager#getForChunk} inside Sodium's
 * {@code visit(x, y, z, ...)} and checks the region's built flag. Celeritas' collector visits
 * {@code OcclusionNode}s instead and already has the section in hand, so the same information comes
 * from {@code hasAnythingToRender()} with no redirect needed.
 *
 * <p>The per-frame reset lives in the constructor rather than in the section manager's render-list
 * entry points: a new collector is built for exactly one traversal, which is the same boundary.
 */
@Mixin(value = VisibleChunkCollector.class, remap = false)
public class MixinVisibleChunkCollector {

    //Signature must match the SHIPPED Celeritas ctor (int, int, boolean), which differs from the
    //one in celeritas-repo -- the repo is a newer commit than the jar. Mixin handler parameters are
    //only checked when the mixin applies, so a mismatch here is a hard crash at runtime, not a
    //compile error. Always javap the jar in libs/ before writing one of these.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetVisibleStream(int frame, int regionIdsLength, boolean ignoreQueueSizeLimit, CallbackInfo ci) {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null) {
            vrs.visbleSectionStream.reset();
        }
    }

    @Inject(method = "visit", at = @At("TAIL"))
    private void voxy$gatherVisibleSection(OcclusionNode node, boolean visible, CallbackInfo ci) {
        if (!visible) return;
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) return;
        var section = node.getRenderSection();
        if (section == null || !section.hasAnythingToRender()) return;
        vrs.visbleSectionStream.put(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }
}
