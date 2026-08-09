package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.shader.Shader;

import static org.lwjgl.opengl.GL11C.*;

/**
 * Port note: the depth-comparison helpers below are plain GL and port unchanged. Only the factory
 * needed reworking, because upstream sources all three flags from things 1.12.2 does not have:
 *
 * <ul>
 *   <li>{@code isZero2One} comes from blaze3d's device info. 1.12.2 never calls glClipControl and
 *       neither does Celeritas (checked across both source trees), so depth stays on OpenGL's
 *       default [-1, 1] NDC range.</li>
 *   <li>{@code isReverseZ} comes from blaze3d's DepthStencilState. 1.12.2 has no reverse-Z depth
 *       buffer at all.</li>
 *   <li>{@code useBlockAtlasUVs} is decided by the Iris pipeline, and Iris does not exist here.</li>
 * </ul>
 *
 * <p>So all three are constant false on this version. The structure is kept rather than folded
 * away, since it is what the shader defines and depth-func selection key off -- if Celeritas ever
 * adopts clip control, this is the single place that has to change.
 */
public record RenderProperties(boolean isZero2One, boolean isReverseZ, boolean useBlockAtlasUVs) {

    public <T extends Shader.Builder<J>, J extends Shader> T apply(T builder) {
        return (T) builder.defineIf("USE_ZERO_ONE_DEPTH", this.isZero2One)
                .defineIf("USE_REVERSE_Z", this.isReverseZ);
    }

    public int closerEqualDepthCompare() {
        return this.isReverseZ?GL_GEQUAL:GL_LEQUAL;
    }

    public int closerDepthCompare() {
        return this.isReverseZ?GL_GREATER:GL_LESS;
    }

    public int furtherDepthCompare() {
        return this.isReverseZ?GL_LESS:GL_GREATER;
    }

    public float clearDepth() {
        return this.isReverseZ?0.0f:1.0f;
    }

    public float inverseClearDepth() {
        return this.isReverseZ?1.0f:0.0f;
    }







    /** 1.12.2 leaves depth on OpenGL's default [-1, 1] NDC range; nothing calls glClipControl. */
    private static boolean useZeroToOneDepth() {
        return false;
    }

    /** No reverse-Z depth buffer exists on this version. */
    private static boolean useReverseZ() {
        return false;
    }

    public static RenderProperties getRenderProperties() {
        return new RenderProperties(
                useZeroToOneDepth(),
                useReverseZ(),
                false);
    }
}
