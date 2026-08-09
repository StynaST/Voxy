package me.cortex.voxy;

/**
 * Server (and shared) half of the Forge sided proxy. Voxy's client entrypoint touches GL and
 * client-only Minecraft classes, so it must not be reachable from a dedicated server; the proxy
 * split is what keeps those classes off the server's classloading path.
 */
public class CommonProxy {
    public void postInit() {}
}
