package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import net.minecraftforge.fml.common.Loader;

/** State for {@code MixinWeather2FarPlane}, kept out of the mixin to avoid a static initialiser. */
public final class Weather2Compat {
    public static final boolean PRESENT = Loader.isModLoaded("weather2remaster");

    private static boolean logged = false;

    private Weather2Compat() {
    }

    public static void logFarPlaneRestore(float weather2Value, float vanillaValue) {
        if (logged) return;
        logged = true;
        Logger.info("Weather2 Remastered set the far plane to", weather2Value,
                "blocks; restoring vanilla's", vanillaValue);
    }
}
