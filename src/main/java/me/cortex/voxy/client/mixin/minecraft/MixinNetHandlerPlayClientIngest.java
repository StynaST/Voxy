package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.ingest.ChunkUpdateQueue;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//The render-graph hooks only see chunks Celeritas renders. ChunkEvent.Load is no good on 1.12.2:
//it fires before the packet has filled the chunk, so this is the earliest point with data.
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClientIngest {

    @Inject(method = "handleChunkData", at = @At("RETURN"))
    private void voxy$ingestChunkData(SPacketChunkData packet, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) return;
        if (VoxyCommon.getInstance() == null) return;
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) return;
        //Singleplayer sends the same packets locally and the lifecycle hooks cover it, so this
        //would ingest twice
        if (Minecraft.getMinecraft().getIntegratedServer() != null) return;

        var world = Minecraft.getMinecraft().world;
        if (world == null) return;

        //Queued, not ingested: block data arrives before light and ingest drops a lightless chunk
        ChunkUpdateQueue.enqueue(packet.getChunkX(), packet.getChunkZ());
    }
}
