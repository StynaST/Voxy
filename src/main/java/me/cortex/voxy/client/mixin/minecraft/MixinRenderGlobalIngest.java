package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.NibbleArray;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-ingests a section when a block on its border changes, so a neighbouring LOD does not keep a
 * stale face.
 *
 * <p>Port note: upstream hooks {@code ClientLevel#setBlocksDirty}. 1.12.2's client-side equivalent
 * is {@code RenderGlobal#notifyBlockUpdate}, which the world calls with both the old and the new
 * state and only on the client.
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobalIngest {

    @Inject(method = "notifyBlockUpdate", at = @At("TAIL"))
    private void voxy$injectIngestOnStateChange(World worldIn, BlockPos pos, IBlockState oldState, IBlockState newState, int flags, CallbackInfo ci) {
        if (oldState == newState) return;
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) return;

        //TODO: is this _really_ needed, we should have enough processing power to not need todo it if
        // its only a block removal
        if (newState.getMaterial() != net.minecraft.block.material.Material.AIR) return;
        if (VoxyCommon.getInstance() == null) return;
        if (!VoxyConfig.CONFIG.ingestEnabled) return;//Only ingest if setting enabled

        int x = pos.getX()&15;
        int y = pos.getY()&15;
        int z = pos.getZ()&15;
        //Only update when the state change is on the border of a section
        if (!(x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15)) return;

        var wi = WorldIdentifier.of(worldIn);
        if (wi == null) return;

        int cx = pos.getX()>>4;
        int cy = pos.getY()>>4;
        int cz = pos.getZ()>>4;

        var chunk = worldIn.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk == null) return;
        var sections = chunk.getBlockStorageArray();
        if (cy < 0 || cy >= sections.length) return;
        var section = sections[cy];
        if (section == null) return;

        VoxelIngestService.rawIngest(wi, section, cx, cy, cz,
                voxy$copyNibble(section.getBlockLight()),
                voxy$copyNibble(section.getSkyLight()),
                chunk.getBiomeArray().clone());
    }

    @Unique
    private static NibbleArray voxy$copyNibble(NibbleArray array) {
        return array == null ? null : new NibbleArray(array.getData().clone());
    }
}
