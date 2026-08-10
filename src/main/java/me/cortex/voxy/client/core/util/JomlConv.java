package me.cortex.voxy.client.core.util;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector3ic;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Port note: Celeritas shades JOML to {@code org.embeddedt.embeddium.impl.shadow.joml}, while Voxy
 * uses the real {@code org.joml} that Cleanroom ships. The two are the same library but unrelated
 * types, so anything crossing the Celeritas boundary has to be copied component by component.
 */
public final class JomlConv {
    private JomlConv() {}

    public static Matrix4f toVoxy(org.embeddedt.embeddium.impl.shadow.joml.Matrix4fc matrix) {
        //Avoid Matrix4f#set(float[]), which initializes JOML's MemUtil backend. JOML's internal
        //Unsafe implementation is not safe on the Java 25 ZGC runtime used by Cleanroom.
        return new Matrix4f(
                matrix.m00(), matrix.m01(), matrix.m02(), matrix.m03(),
                matrix.m10(), matrix.m11(), matrix.m12(), matrix.m13(),
                matrix.m20(), matrix.m21(), matrix.m22(), matrix.m23(),
                matrix.m30(), matrix.m31(), matrix.m32(), matrix.m33());
    }

    public static void put(Matrix4fc matrix, long address) {
        MemoryUtil.memPutFloat(address, matrix.m00()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m01()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m02()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m03()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m10()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m11()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m12()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m13()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m20()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m21()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m22()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m23()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m30()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m31()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m32()); address += 4;
        MemoryUtil.memPutFloat(address, matrix.m33());
    }

    public static void put(Vector4fc vector, long address) {
        MemoryUtil.memPutFloat(address, vector.x());
        MemoryUtil.memPutFloat(address + 4, vector.y());
        MemoryUtil.memPutFloat(address + 8, vector.z());
        MemoryUtil.memPutFloat(address + 12, vector.w());
    }

    public static void put(Vector3fc vector, long address) {
        MemoryUtil.memPutFloat(address, vector.x());
        MemoryUtil.memPutFloat(address + 4, vector.y());
        MemoryUtil.memPutFloat(address + 8, vector.z());
    }

    public static void put(Vector3ic vector, long address) {
        MemoryUtil.memPutInt(address, vector.x());
        MemoryUtil.memPutInt(address + 4, vector.y());
        MemoryUtil.memPutInt(address + 8, vector.z());
    }
}
