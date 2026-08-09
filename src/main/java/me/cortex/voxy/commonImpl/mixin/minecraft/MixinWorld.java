package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches a {@link WorldIdentifier} to every world, which is how the rest of Voxy finds the storage
 * engine for the world it is looking at.
 *
 * <p>Port note: upstream injects into {@code Level}'s constructor, where the dimension key, the
 * dimension type holder and the seed are all constructor arguments. 1.12.2's {@code World}
 * constructor carries none of them, and {@code provider} is only fully wired up by the subclass
 * constructor that runs after this one, so the identifier is built lazily on first use instead.
 *
 * <p>Everything it needs is public API ({@code provider}, {@code getSeed()}), so this reads it off a
 * cast {@code this} rather than through {@code @Shadow}. That keeps the mixin out of the refmap
 * entirely -- the field reference is remapped by reobf like any other call into Minecraft -- and
 * avoids shadowing {@code provider}, which is final.
 *
 * <p>The lazy field is deliberately unsynchronized: a race can only ever produce two identifiers
 * that are equal by value, and {@code WorldIdentifier} is used as a value key everywhere.
 */
@Mixin(World.class)
public abstract class MixinWorld implements IWorldGetIdentifier {
    @Unique
    private WorldIdentifier voxy$identifier;

    @Override
    public WorldIdentifier voxy$getIdentifier() {
        var identifier = this.voxy$identifier;
        if (identifier == null) {
            var self = (World) (Object) this;
            var provider = self.provider;
            if (provider == null) {
                return null;
            }
            identifier = this.voxy$identifier = new WorldIdentifier(
                    WorldIdentifier.keyForDimensionId(provider.getDimension()),
                    self.getSeed(),
                    WorldIdentifier.locationForDimensionType(provider.getDimensionType()));
        }
        return identifier;
    }
}
