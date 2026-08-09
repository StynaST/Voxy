package me.cortex.voxy.client;

import me.cortex.voxy.CommonProxy;

/**
 * Port note: Voxy's GL-side init does not live here. It needs a GL context and has to run before
 * anything renders, so it is driven from {@code MixinMinecraftStartup} at the end of
 * {@code Minecraft#init} -- the point where upstream's mixin on {@code RenderSystem#initRenderer}
 * fires. Only the parts that need a loaded mod environment stay on the Forge lifecycle.
 */
public class ClientProxy extends CommonProxy {
    @Override
    public void postInit() {
        VoxyClient.onInitializeClient();
    }
}
