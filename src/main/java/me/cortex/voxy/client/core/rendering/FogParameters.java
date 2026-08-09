package me.cortex.voxy.client.core.rendering;

/**
 * Port note: replaces {@code net.caffeinemc.mods.sodium.client.util.FogParameters}. Celeritas
 * carries the same information but exposes it through
 * {@code org.embeddedt.embeddium.impl.render.chunk.fog.FogService}, which has a different shape
 * (getFogStart/getFogEnd plus a float[4] colour) and is an interface rather than a value type.
 *
 * <p>Voxy passes fog state around by value and reads it on the render thread, so it gets its own
 * record here. The accessor names deliberately match Sodium's so the call sites in the render
 * pipeline stay identical to upstream; converting from Celeritas happens once, at the boundary.
 */
public record FogParameters(float environmentalStart,
                            float environmentalEnd,
                            float red,
                            float green,
                            float blue,
                            float alpha) {

    public static final FogParameters NONE = new FogParameters(Float.MAX_VALUE, Float.MAX_VALUE, 0, 0, 0, 1);

    /**
     * Reads the fog the game has currently set up. 1.12.2 fog is fixed-function state configured by
     * {@code EntityRenderer#setupFog}, which is also where Celeritas' own {@code FogService} reads it
     * from, so this mirrors that rather than depending on how Celeritas exposes the service.
     */
    public static FogParameters captureFromGameState() {
        var fog = net.minecraft.client.renderer.GlStateManager.fogState;
        var entityRenderer = net.minecraft.client.Minecraft.getMinecraft().entityRenderer;
        if (entityRenderer == null || !fog.fog.currentState) {
            return NONE;
        }
        return new FogParameters(fog.start, fog.end,
                entityRenderer.fogColorRed, entityRenderer.fogColorGreen, entityRenderer.fogColorBlue, 1.0f);
    }
}
