package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.ingest.ChunkUpdateQueue;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

public class ClientSessionEvents {
    public static boolean inSession = false;

    //FML network events, not world load/unload: those also fire on every dimension change, but a
    //session spans them
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new ClientSessionEvents());
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        sessionStart();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        VoxyRenderSystem.shutdownOrphans();
        ChunkUpdateQueue.tick();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (inSession) {
            sessionEnd();
        }
    }

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;

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
        ChunkUpdateQueue.clear();
        var holder = IVoxyRenderSystemHolder.getNullableHolder();
        if (holder != null) {
            holder.voxy$shutdownRenderer();
        }
        //An orphan holds a world reference that instance shutdown would wait on
        me.cortex.voxy.client.core.VoxyRenderSystem.shutdownAll();
        VoxyCommon.shutdownInstance();
    }
}
