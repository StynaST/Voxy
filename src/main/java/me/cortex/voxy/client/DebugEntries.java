package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Port note: upstream registers entries with {@code DebugScreenEntries}, the modern per-line debug
 * screen registry, which 1.12.2 has no equivalent of. Here the same information is appended to the
 * F3 overlay's left column through Forge's {@link RenderGameOverlayEvent.Text}, which only fires
 * while the debug screen is open.
 *
 * <p>The GPU timing entry is the one behaviour that does not survive the move. Upstream turns GPU
 * timing on when the player enables that specific debug entry; 1.12.2 debug lines cannot be toggled
 * individually, so it is an explicit flag instead, driven by {@code /voxy debug gpuTimings <bool>}.
 * Leaving it tied to the debug screen would silently pay for GPU queries every time someone opens
 * F3.
 */
public class DebugEntries {
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new DebugEntries());
    }

    @SubscribeEvent
    public void onRenderDebugText(RenderGameOverlayEvent.Text event) {
        //Forge fires this every frame, not just while the debug screen is open, so without this
        //check Voxy's lines sit permanently in the top left corner.
        if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            return;
        }
        var lines = event.getLeft();

        if (!VoxyCommon.isAvailable()) {
            lines.add(TextFormatting.RED + "voxy-" + VoxyCommon.MOD_VERSION);//Voxy installed, not avalible
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            lines.add(TextFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);//Voxy avalible, no instance active
            return;
        }
        //Voxy instance active
        var vrs = IVoxyRenderSystemHolder.getNullable();
        lines.add((vrs == null ? TextFormatting.DARK_GREEN : TextFormatting.GREEN) + "voxy-" + VoxyCommon.MOD_VERSION);

        List<String> instanceLines = new ArrayList<>();
        instance.addDebug(instanceLines);
        lines.addAll(instanceLines);

        if (vrs != null) {
            List<String> renderLines = new ArrayList<>();
            vrs.addDebugInfo(renderLines);
            lines.addAll(renderLines);
        }
    }

    private static boolean gpuDebugEnabled = false;

    public static boolean isGpuDebugEnabled() {
        return gpuDebugEnabled;
    }

    public static void setGpuDebugEnabled(boolean enabled) {
        if (gpuDebugEnabled == enabled) {
            return;
        }
        gpuDebugEnabled = enabled;

        GPUTiming.INSTANCE.setEnabled(enabled);
        RenderStatistics.enabled = enabled;
        var renderer = Minecraft.getMinecraft().renderGlobal;
        if (renderer != null) renderer.loadRenderers();
    }
}
