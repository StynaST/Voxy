package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.util.IrisUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Port note: upstream asks Vivecraft for the current VR render pass so each eye gets its own
 * viewport. Vivecraft does exist for 1.12.2, but as a full client fork with no
 * {@code org.vivecraft.api.client} API surface to query -- that API is a modern-Vivecraft addition.
 * So VR is reported as absent, which collapses this to the single-viewport path. The structure is
 * left intact so wiring a 1.12.2 VR hook in later is a one-method change.
 */
public class ViewportSelector <T extends Viewport<?>> {
    public static final boolean VIVECRAFT_INSTALLED = false;

    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();//TODO should maybe be a weak hashmap with value cleanup queue thing?

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
    }

    private T getOrCreate(Object holder) {
        return this.extraViewports.computeIfAbsent(holder, a->this.creator.get());
    }

    private T getVivecraftViewport() {
        // Unreachable while VIVECRAFT_INSTALLED is false; see the class comment.
        return null;
    }

    private static final Object IRIS_SHADOW_OBJECT = new Object();
    public T getViewport() {
        T viewport = null;
        if (viewport == null && VIVECRAFT_INSTALLED) {
            viewport = getVivecraftViewport();
        }

        if (viewport == null && IrisUtil.irisShadowActive()) {
            viewport = this.getOrCreate(IRIS_SHADOW_OBJECT);
        }

        if (viewport == null) {
            viewport = this.defaultViewport;
        }
        return viewport;
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(Viewport::delete);
        this.extraViewports.clear();
    }
}
