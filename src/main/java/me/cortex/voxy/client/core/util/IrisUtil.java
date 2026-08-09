package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import me.cortex.voxy.client.core.rendering.FogParameters;
import org.embeddedt.embeddium.impl.render.ShaderModBridge;

/**
 * Port note: Iris does not exist for 1.12.2, and has no backport -- the shader mod for this version
 * is OptiFine, whose pipeline is nothing like Iris'. Upstream already guards every entry point here
 * behind {@link #IRIS_INSTALLED}, so the honest port is to report Iris as absent and keep the exact
 * same public contract. Every method below then behaves as it does on a Sodium install with no
 * shader pack, which is the correct answer on this version.
 *
 * <p>{@link #SHADER_MOD_PRESENT} is exposed separately, backed by Celeritas' own shader-mod
 * detection, so a future OptiFine-shader pipeline has something to hang off. It deliberately does
 * not feed {@link #IRIS_INSTALLED}: the methods below promise Iris semantics specifically, and
 * answering them from OptiFine state would make them lie.
 */
public class IrisUtil {

    public record CapturedViewportParameters(ChunkRenderMatrices matrices, FogParameters parameters, int width, int height, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            //Celeritas hands us its shaded joml matrices, Voxy's renderer wants the real ones
            return vrs.setupViewport(JomlConv.toVoxy(this.matrices.projection()), JomlConv.toVoxy(this.matrices.modelView()), this.parameters, this.width, this.height, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    public static final boolean IRIS_INSTALLED = false;

    /**
     * Whether some shader mod (OptiFine on this version) is loaded. Not wired into the Iris paths;
     * see the class comment.
     */
    public static final boolean SHADER_MOD_PRESENT = shaderModPresent();

    /**
     * Upstream leaves this on to select the Iris render pipeline. With no Iris present there is no
     * such pipeline to select, so Voxy uses its normal one.
     */
    public static final boolean SHADER_SUPPORT = false;

    private static boolean shaderModPresent() {
        try {
            return ShaderModBridge.isShaderModPresent();
        } catch (Throwable t) {
            // Celeritas resolves this through method handles, so a build without the bridge should
            // degrade rather than take the whole renderer down.
            return false;
        }
    }

    public static boolean irisShadowActive() {
        return false;
    }

    public static void clearIrisSamplers() {
    }

    public static void reload() {
    }

    public static boolean irisShaderPackEnabled() {
        return false;
    }

    public static boolean irisShadersEnabledInConfig() {
        return false;
    }

    public static void disableIrisShaders() {
    }
}
