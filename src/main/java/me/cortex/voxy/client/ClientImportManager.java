package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.SPacketUpdateBossInfo;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.BossInfo;

import java.util.UUID;

public class ClientImportManager extends ImportManager {
    //1.12.2 has no client side boss bar api. GuiBossOverlay keeps its bar map private and the only
    // BossInfoClient constructor takes a packet, so the overlay is driven with synthetic
    // SPacketUpdateBossInfo packets through its public read(), rather than an accessor mixin.
    private static final class LocalBossInfo extends BossInfo {
        private LocalBossInfo(UUID uniqueId, ITextComponent name) {
            super(uniqueId, name, BossInfo.Color.GREEN, BossInfo.Overlay.PROGRESS);
        }
    }

    protected class ClientImportTask extends ImportTask {
        private final UUID bossbarUUID;
        private final LocalBossInfo bossBar;
        protected ClientImportTask(IDataImporter importer) {
            super(importer);

            this.bossbarUUID = UUID.randomUUID();
            this.bossBar = new LocalBossInfo(this.bossbarUUID, new TextComponentString("Voxy world importer"));
            this.bossBar.setPercent(0.0f);
            Minecraft.getMinecraft().addScheduledTask(()->{
                Minecraft.getMinecraft().ingameGUI.getBossOverlay().read(new SPacketUpdateBossInfo(SPacketUpdateBossInfo.Operation.ADD, this.bossBar));
            });
        }

        @Override
        protected boolean onUpdate(int completed, int outOf) {
            if (!super.onUpdate(completed, outOf)) {
                return false;
            }
            Minecraft.getMinecraft().addScheduledTask(()->{
                this.bossBar.setPercent((float) (((double)completed) / ((double) Math.max(1, outOf))));
                this.bossBar.setName(new TextComponentString("Voxy import: " + completed + "/" + outOf + " chunks"));
                var overlay = Minecraft.getMinecraft().ingameGUI.getBossOverlay();
                overlay.read(new SPacketUpdateBossInfo(SPacketUpdateBossInfo.Operation.UPDATE_PCT, this.bossBar));
                overlay.read(new SPacketUpdateBossInfo(SPacketUpdateBossInfo.Operation.UPDATE_NAME, this.bossBar));
            });
            return true;
        }

        @Override
        protected void onCompleted(int total) {
            super.onCompleted(total);
            Minecraft.getMinecraft().addScheduledTask(()->{
                Minecraft.getMinecraft().ingameGUI.getBossOverlay().read(new SPacketUpdateBossInfo(SPacketUpdateBossInfo.Operation.REMOVE, this.bossBar));
                long delta = Math.max(System.currentTimeMillis() - this.startTime, 1);

                String msg = "Voxy world import finished in " + (delta/1000) + " seconds, averaging " + (int)(total/(delta/1000f)) + " chunks per second";
                Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentString(msg));
                Logger.info(msg);
            });
        }
    }

    @Override
    protected synchronized ImportTask createImportTask(IDataImporter importer) {
        return new ClientImportTask(importer);
    }
}
