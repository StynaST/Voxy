package me.cortex.voxy.common.util;

/**
 * Port note: 1.12.2 has no {@code net.minecraft.core.SectionPos}. Voxy only uses it as a
 * section-coordinate packer, so the packing is reproduced here bit-for-bit against modern
 * Minecraft's layout (22 bits X, 20 bits Y, 22 bits Z).
 *
 * <p>Keeping the exact same layout matters: these packed longs are used as keys throughout the
 * renderer and are handed to shaders, so a different packing would silently mismatch.
 */
public final class SectionPos {
    private SectionPos() {}

    public static long asLong(int x, int y, int z) {
        return ((long) x & 4194303L) << 42 | ((long) y & 1048575L) | ((long) z & 4194303L) << 20;
    }

    public static int x(long pos) {
        return (int) (pos >> 42);
    }

    public static int y(long pos) {
        return (int) (pos << 44 >> 44);
    }

    public static int z(long pos) {
        return (int) (pos << 22 >> 42);
    }
}
