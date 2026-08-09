package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;

import java.util.function.BooleanSupplier;

/**
 * Port note: upstream picks between an Iris-aware pipeline and the normal one. Iris has no 1.12.2
 * counterpart (see {@link me.cortex.voxy.client.core.util.IrisUtil}), and the Iris pipeline it built
 * is written against Iris' shader-pack internals rather than anything portable, so only the normal
 * pipeline exists here. The selection point is kept so an OptiFine-shader pipeline can slot in.
 */
public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        return new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
    }
}
