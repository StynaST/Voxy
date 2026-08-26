package me.cortex.voxy.common.util;

import org.lwjgl.system.MemoryUtil;

/**
 * Bulk memory copies between raw addresses and Java arrays.
 *
 * <p>Port note: upstream reflected {@code sun.misc.Unsafe} out of the JDK and called
 * {@code copyMemory} directly. Every one of those methods is terminally deprecated on Java 25
 * (JEP 498) and warns at both compile and run time. LWJGL 3.4's {@link MemoryUtil} exposes the
 * exact same primitives ({@code memCopy}) and is the copy path the rest of Voxy already uses, so
 * these are now thin delegates -- same generated code, no deprecated API on our side.
 *
 * <p>Argument order is kept as upstream had it (src first, dst last) so call sites are unchanged;
 * MemoryUtil's array overloads instead take (array, address, arrayIndex, elementCount).
 */
public class UnsafeUtil {
    public static void memcpy(long src, long dst, long length) {
        MemoryUtil.memCopy(src, dst, length);
    }

    //Copy the entire length of src to the dst memory where dst is a byte array (source length from dst)
    public static void memcpy(long src, byte[] dst) {
        MemoryUtil.memCopy(src, dst);
    }

    public static void memcpy(long src, int length, byte[] dst) {
        MemoryUtil.memCopy(src, dst, 0, length);
    }

    public static void memcpy(long src, int length, byte[] dst, int offset) {
        MemoryUtil.memCopy(src, dst, offset, length);
    }

    //Copy the entire length of src to the dst memory where src is a byte array (source length from src)
    public static void memcpy(byte[] src, long dst) {
        MemoryUtil.memCopy(src, dst);
    }

    public static void memcpy(byte[] src, int len, long dst) {
        MemoryUtil.memCopy(src, dst, 0, len);
    }

    public static void memcpy(short[] src, long dst) {
        MemoryUtil.memCopy(src, dst);
    }

    public static void memcpy(long[] src, long dst) {
        MemoryUtil.memCopy(src, dst);
    }

    public static void memcpy(int[] src, long dst) {
        MemoryUtil.memCopy(src, dst);
    }

    public static void memcpy(int[] src, int count, long dst) {
        MemoryUtil.memCopy(src, dst, 0, count);
    }
}
