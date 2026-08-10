package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSimpleWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;

import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private final Config config;
    private final Path basePath;

    public VoxyClientInstance() {
        {
            //Port note: upstream checks Flashback here and redirects storage into the replay. Flashback
            // has no 1.12.2 build, so that compat layer was deleted and the base path is always ours.
            var basePath = this.basePath = getBasePath().normalize();
            this.config = StorageConfigUtil.getCreateStorageConfig(Config.class, c->c.version==1&&c.sectionStorageConfig!=null, ()->DEFAULT_STORAGE_CONFIG, basePath);
        }
        super();
        this.updateDedicatedThreads();
    }

    @Override
    protected boolean shouldCreateInstance() {
        return !this.config.disabled;
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            var swr = CeleritasWorldRenderer.instanceNullable();
            if (swr != null) {
                var rsm = ((AccessorSimpleWorldRenderer) swr).voxy$getRenderSectionManager();
                if (rsm != null) {
                    this.setNumThreads(Math.max(1, target - rsm.getBuilder().getTotalThreadCount()));
                    return;
                }
            }
        }
        this.setNumThreads(target);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, Minecraft.getMinecraft().getSession().getProfile().getId().toString().replace(':','-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        //Free the render resources cache since the entire instance is freed
        RenderResourceReuse.clearResources();
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getMinecraft().gameDir.toPath().resolve(".voxy").resolve("saves");
        var iserver = Minecraft.getMinecraft().getIntegratedServer();
        if (iserver != null) {
            //1.12.2 has no LevelResource: the save root is the overworld save handler's directory
            basePath = iserver.getEntityWorld().getSaveHandler().getWorldDirectory().toPath().resolve("voxy");
        } else {
            var info = Minecraft.getMinecraft().getCurrentServerData();
            if (info == null) {
                Logger.error("Server info null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                //1.12.2's ServerData has no isRealm flag, the client tracks it instead
                if (Minecraft.getMinecraft().isConnectedToRealms()) {
                    basePath = basePath.resolve("realms");
                } else {
                    //Host only: the port is not part of the world's identity, and a server that moves
                    //port would otherwise orphan every LOD already stored for it
                    String host = info.serverIP;
                    int portSplit = host.lastIndexOf(':');
                    if (portSplit != -1) {
                        host = host.substring(0, portSplit);
                    }
                    basePath = basePath.resolve(host.replace(":", "_"));
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
