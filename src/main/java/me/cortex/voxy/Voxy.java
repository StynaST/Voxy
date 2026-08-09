package me.cortex.voxy;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge entrypoint for the 1.12.2 port of Voxy.
 *
 * <p>Upstream Voxy splits its entrypoints between {@code me.cortex.voxy.client.VoxyClient} and
 * {@code me.cortex.voxy.commonImpl.VoxyCommon}, wired up through fabric.mod.json. Forge has a
 * single {@code @Mod} class instead, so this type owns the lifecycle and delegates to those two
 * once they are ported.
 */
//Voxy renders through Celeritas and mixes into its classes; without it the sodium mixin config
//fails to apply and half of Voxy's render path has no target. Declared as a hard dependency so the
//failure is a readable FML message rather than a NoClassDefFoundError mid-load.
//
//clientSideOnly because Celeritas is a client mod and, more importantly, nothing server-side is
//ported: every feature that exists here (ingest, render, the DH importer) runs on the client. If a
//dedicated-server entry point is ever added, this and the dependency line both have to relax.
@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION, clientSideOnly = true,
        dependencies = "required-after:celeritas")
public class Voxy {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @SidedProxy(clientSide = "me.cortex.voxy.client.ClientProxy", serverSide = "me.cortex.voxy.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Voxy {} starting", Tags.VERSION);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit();
    }
}
