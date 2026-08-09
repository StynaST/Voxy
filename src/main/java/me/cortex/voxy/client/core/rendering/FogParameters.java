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

        float start = fog.start;
        float end = fog.end;
        if (fog.mode != GL_LINEAR) {
            //Underwater, lava and cloud fog are EXP/EXP2, where start/end are unused and hold
            //whatever the last linear pass left behind. Voxy's shader only understands a linear
            //range, so derive the distance at which the exponential has essentially reached full
            //opacity; otherwise the LODs get fogged by stale numbers.
            float density = Math.max(fog.density, 1.0e-4f);
            float distance = fog.mode == GL_EXP2
                    ? (float) (Math.sqrt(-Math.log(EXP_FOG_CUTOFF)) / density)
                    : (float) (-Math.log(EXP_FOG_CUTOFF) / density);
            start = 0;
            end = distance;
        }

        return new FogParameters(start, end,
                entityRenderer.fogColorRed, entityRenderer.fogColorGreen, entityRenderer.fogColorBlue, 1.0f);
    }

    /** Residual visibility treated as "fully fogged" when flattening exponential fog to a range. */
    private static final float EXP_FOG_CUTOFF = 0.02f;

    private static final int GL_LINEAR = 9729;
    private static final int GL_EXP2 = 2049;
}
