package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

public class ClientSessionEvents {
    public static boolean inSession = false;

    /**
     * Port note: upstream hooks fabric's {@code ClientPlayConnectionEvents} JOIN/DISCONNECT. The
     * Forge equivalents are the FML network events, which fire for singleplayer too since the
     * integrated server runs over a local connection. World load/unload events would be wrong here:
     * those also fire on every dimension change, but a session spans them.
     */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new ClientSessionEvents());
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        sessionStart();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        //A failed connection attempt disconnects without ever having connected
        if (inSession) {
            sessionEnd();
        }
    }

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;

        //Should never try creating multiple instances via session start
        if (VoxyCommon.getInstance() != null) throw new IllegalStateException();

        if (VoxyCommon.isAvailable()) {
            if (VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();
            }
        }
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        inSession = false;

        var minecraft = Minecraft.getMinecraft();
        if (!minecraft.isCallingFromMinecraftThread()) {
            minecraft.addScheduledTask(ClientSessionEvents::shutdownClientSession);
            return;
        }
        shutdownClientSession();
    }

    private static void shutdownClientSession() {
        var holder = IVoxyRenderSystemHolder.getNullableHolder();
        if (holder != null) {
            holder.voxy$shutdownRenderer();
        }
        //Catches render systems the holder no longer points at; each one holds a world reference and
        //instance shutdown waits on those
        me.cortex.voxy.client.core.VoxyRenderSystem.shutdownAll();
        VoxyCommon.shutdownInstance();
    }
}
