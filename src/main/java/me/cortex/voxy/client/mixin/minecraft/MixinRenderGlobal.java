package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/**
 * Owns the Voxy render system, hung off the world renderer exactly as upstream hangs it off
 * {@code LevelRenderer}.
 *
 * <p>Port note: 1.12.2's {@code RenderGlobal} has no {@code close()}; the world renderer lives as
 * long as the client does. {@code setWorldAndLoadRenderers} is both the world-change and the
 * teardown hook (it is called with null on disconnect), so it serves as both here.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal implements IVoxyRenderSystemHolder {
    @Unique @Nullable private WorldIdentifier voxy$identifier;
    @Unique private @Nullable VoxyRenderSystem voxy$renderer;

    @Override
    public VoxyRenderSystem voxy$getRenderSystem() {
        return this.voxy$renderer;
    }

    @Inject(method = "setWorldAndLoadRenderers", at = @At("HEAD"))
    private void voxy$injectWorldChange(@Nullable WorldClient worldClientIn, CallbackInfo ci) {
        this.voxy$setWorld(worldClientIn);
    }

    @Inject(method = "loadRenderers", at = @At("TAIL"))
    private void voxy$reload(CallbackInfo ci) {
        //Upstream hooks LevelExtractor#allChanged, which is the same "renderers were rebuilt" event
        this.voxy$shutdownRenderer();
        this.voxy$createRenderer();
    }

    @Override
    public void voxy$shutdownRenderer() {
        if (this.voxy$renderer != null) {
            this.voxy$renderer.shutdown();
            this.voxy$renderer = null;
        }
    }

    @Override
    public void voxy$setWorld(World level) {
        WorldIdentifier identifier = level == null ? null : WorldIdentifier.of(level);
        if (Objects.equals(this.voxy$identifier, identifier)) return;
        this.voxy$shutdownRenderer();
        this.voxy$identifier = identifier;
    }

    @Override
    public void voxy$createRenderer() {
        if (this.voxy$renderer != null) {
            //Never leak the old one: it holds a world reference, and an unreleased reference makes
            //instance shutdown wait on a world that can never become unused
            Logger.warn("Creating a renderer while one already exists, shutting the old one down first");
            this.voxy$shutdownRenderer();
        }
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.voxy$identifier == null) {
            Logger.info("Not creating renderer due to null identifier");
            return;
        }
        if (net.minecraft.client.Minecraft.getMinecraft().world == null) {
            //loadRenderers can run while Minecraft.world is still unset: another mod's
            //WorldEvent.Load handler calls setWorldAndLoadRenderers from inside WorldClient's
            //constructor, before Minecraft.loadWorld assigns the field. VoxyRenderSystem reads the
            //world for its section range, so it would NPE. Minecraft.loadWorld calls
            //setWorldAndLoadRenderers again once the field is set, which is when this succeeds.
            Logger.info("Not creating renderer yet, client world is not set");
            return;
        }
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            //This is now legal (e.g. when the instance is disabled)
            Logger.info("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = this.voxy$identifier.getOrCreateEngine(true);
        if (world == null) {
            Logger.warn("Not creating renderer due to null engine");
            return;
        }
        this.voxy$createEngineDirect(world);
    }

    @Unique
    private void voxy$createEngineDirect(WorldEngine world) {
        var instance = world.instanceIn;
        if (instance == null) throw new IllegalStateException();//in theory this could be null if is like in a test suit or something
        //Upstream falls back to disabling Iris shaders if construction throws; with no Iris on 1.12.2
        // there is nothing to fall back to, so the exception propagates
        this.voxy$renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        instance.updateDedicatedThreads();
    }
}
