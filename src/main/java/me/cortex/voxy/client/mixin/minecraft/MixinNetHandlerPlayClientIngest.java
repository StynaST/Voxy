package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ingests server chunks as they arrive. The render-graph hooks only see chunks Celeritas chooses to
 * render, so on a server anything else never reaches storage. Distant Horizons uses
 * {@code ChunkEvent.Load} for this; on 1.12.2 that fires from ChunkProviderClient#loadChunk before
 * the packet has filled the chunk, so the packet handler is the equivalent point that has data.
 */
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClientIngest {

    @Inject(method = "handleChunkData", at = @At("RETURN"))
    private void voxy$ingestChunkData(SPacketChunkData packet, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) return;
        if (VoxyCommon.getInstance() == null) return;
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) return;

        var world = Minecraft.getMinecraft().world;
        if (world == null) return;

        var chunk = world.getChunkProvider().getLoadedChunk(packet.getChunkX(), packet.getChunkZ());
        if (chunk == null) return;

        VoxelIngestService.tryAutoIngestChunk(chunk);
    }
}
