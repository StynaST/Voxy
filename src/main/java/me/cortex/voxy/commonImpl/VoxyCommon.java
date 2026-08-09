package me.cortex.voxy.commonImpl;

import me.cortex.voxy.Tags;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Port note: upstream this implements Fabric's {@code ModInitializer} and reads its metadata out of
 * the {@code ModContainer} that fabric.mod.json produced. Forge has no equivalent entrypoint
 * interface, so the Forge {@code @Mod} class ({@link me.cortex.voxy.Voxy}) drives the lifecycle and
 * this type is reduced to the static state that the rest of Voxy reads.
 */
public class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    static {
        boolean inMinecraft;
        Side launchSide = null;
        try {
            // FMLLaunchHandler.side() is set by the launch handler from the command line, so it is
            // valid from the very first tick of the game. Everything else was tried and is wrong at
            // this point in startup: this class initializes from Minecraft#init, before FML has
            // discovered mods (so isModLoaded/getModList report "no minecraft" and skip
            // Serialization.init(), surfacing later as a null GSON on world join) and before
            // FMLCommonHandler has a sided delegate (so getSide() throws NPE, which poisons this
            // whole class with an ExceptionInInitializerError and crashes the game).
            launchSide = FMLLaunchHandler.side();
            inMinecraft = launchSide != null;
        } catch (Throwable t) {
            inMinecraft = false;
        }
        IS_IN_MINECRAFT = inMinecraft;

        if (!IS_IN_MINECRAFT) {
            Logger.error("Running voxy without minecraft");
            MOD_VERSION = "<UNKNOWN>";
            IS_DEDICATED_SERVER = false;
        } else {
            MOD_VERSION = Tags.VERSION;
            IS_DEDICATED_SERVER = launchSide == Side.SERVER;
            //Builds Serialization.GSON, which every storage config read or write goes through.
            //Upstream does this in the same place; dropping it leaves GSON null and the first
            //world join dies with "Failed write the config, aborting!".
            Serialization.init();
        }
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        try {
            INSTANCE = FACTORY.create();
        } catch (DontCreateInstance e) {
            Logger.info("Not creating instance due to DontCreateInstance");
        }
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
