package me.cortex.voxy.common.util;

/**
 * Port note: upstream Voxy calls {@code net.minecraft.world.level.levelgen.RandomSupport}, which
 * 1.12.2 does not have -- Mojang only added it when the world-generation RNG was overhauled. Voxy
 * uses exactly one method from it, as a bucket-spreading hash, so it is reproduced here with the
 * same name and signature to keep the call sites identical to upstream.
 */
public final class RandomSupport {
    private RandomSupport() {}

    /**
     * David Stafford's "Mix13" finalizer for splitmix64. Identical to Mojang's implementation, so
     * the bucket distribution matches upstream exactly.
     */
    public static long mixStafford13(long value) {
        value = (value ^ value >>> 30) * -4658895280553007687L;
        value = (value ^ value >>> 27) * -7723592293110705685L;
        return value ^ value >>> 31;
    }
}
