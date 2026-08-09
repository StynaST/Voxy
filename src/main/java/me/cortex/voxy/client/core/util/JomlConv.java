package me.cortex.voxy.client.core.util;

import org.joml.Matrix4f;

/**
 * Port note: Celeritas shades JOML to {@code org.embeddedt.embeddium.impl.shadow.joml}, while Voxy
 * uses the real {@code org.joml} that Cleanroom ships. The two are the same library but unrelated
 * types, so anything crossing the Celeritas boundary has to be copied through a float array.
 */
public final class JomlConv {
    private JomlConv() {}

    public static Matrix4f toVoxy(org.embeddedt.embeddium.impl.shadow.joml.Matrix4fc matrix) {
        float[] values = new float[16];
        matrix.get(values);
        return new Matrix4f().set(values);
    }
}
