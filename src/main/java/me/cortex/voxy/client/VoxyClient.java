package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlDebugCallback;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;

/**
 * Port note: upstream is a Fabric {@code ClientModInitializer}. Forge has one {@code @Mod} class
 * ({@link me.cortex.voxy.Voxy}) which calls {@link #onInitializeClient()} instead.
 *
 * <p>{@link #initVoxyClient()} still has to run once a GL context exists -- it queries capabilities
 * and allocates the shared index buffer -- so it stays separate from mod init.
 */
public class VoxyClient {
    private static FileLock EXCLUSIVE_LOCK;
    public static void initVoxyClient() {
        GlDebugCallback.installIfRequested();//Before the capability probes, so their errors are filtered
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getMinecraft().gameDir.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    public static void onInitializeClient() {
        DebugEntries.init();
        ClientSessionEvents.register();
        me.cortex.voxy.client.config.VoxyConfigMenu.register();

        //Forge client commands are registered once, not per world load like the fabric callback, so
        // availability is checked when the command runs instead of when it is registered.
        ClientCommandHandler.instance.registerCommand(new VoxyCommands());
    }

    /**
     * Port note: FREX flawless frames is a Fabric rendering-API concept with no 1.12.2 equivalent,
     * and its only entrypoint was the fabric mod loader, so nothing can turn it on here.
     */
    public static boolean isFrexActive() {
        return false;
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
