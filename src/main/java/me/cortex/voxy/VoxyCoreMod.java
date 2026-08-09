package me.cortex.voxy;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.List;
import java.util.Map;

/**
 * Registers Voxy's mixin configs during the coremod phase.
 *
 * <p>This has to be a coremod, not the late {@code ILateMixinLoader} it started as. A late loader
 * runs during FML mod construction, and several of Voxy's targets are already loaded by then —
 * {@code Minecraft} and {@code Framebuffer} are created inside {@code Minecraft#init} before mod
 * loading begins, {@code Util} early too, and {@code BlockStairs} during vanilla bootstrap. Their
 * mixins were silently never applied: the config was accepted, the classes had already been
 * transformed, and nothing logged a failure. Notably {@code MixinMinecraftStartup} is what calls
 * {@code VoxyClient.initVoxyClient()}, so its absence left the whole mod inert with a single
 * "Not creating renderer due to disabled" line as the only symptom.
 *
 * <p>Keep this class free of references to Voxy's own code: coremod plugins load on the launch
 * classloader, before the mod classpath exists.
 */
@IFMLLoadingPlugin.Name("voxy")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)//After the deobfuscation transformer
public class VoxyCoreMod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return List.of("mixins.voxy.json", "mixins.voxycommon.json");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
