package me.cortex.voxy.commonImpl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Port note: upstream identifies a world by {@code ResourceKey<Level>} plus
 * {@code ResourceKey<DimensionType>}. 1.12.2 has neither -- dimensions there are plain integer ids
 * registered through DimensionManager, and DimensionType is an enum, not a registry entry.
 *
 * <p>Both fields therefore become {@link ResourceLocation}, which 1.12.2 does have:
 * <ul>
 *   <li>{@code key} is derived from the integer dimension id, because that is the only value which
 *       is stable across restarts for modded dimensions. It is what the on-disk storage id hashes,
 *       so it must not change between sessions.</li>
 *   <li>{@code dimension} is derived from {@link DimensionType#getName()}.</li>
 * </ul>
 *
 * <p>This is free to differ from upstream because {@code WorldIdentifier} is constructed in exactly
 * one place (the world mixin) and no other class reads these fields.
 */
public class WorldIdentifier {
    public static final String NAMESPACE = "voxy";
    private static final ResourceLocation NULL_DIM_KEY = new ResourceLocation(NAMESPACE, "null_dimension_id");

    public final ResourceLocation key;
    public final long biomeSeed;
    public final ResourceLocation dimension;
    private final transient long hashCode;
    @Nullable transient WeakReference<WorldEngine> cachedEngineObject;

    public WorldIdentifier(@NotNull ResourceLocation key, long biomeSeed, @Nullable ResourceLocation dimension) {
        if (key == null) {
            throw new IllegalStateException("Key cannot be null");
        }
        dimension = dimension==null?NULL_DIM_KEY:dimension;
        this.key = key;
        this.biomeSeed = biomeSeed;
        this.dimension = dimension;
        this.hashCode = mixStafford13(registryKeyHashCode(key))^mixStafford13(registryKeyHashCode(dimension))^mixStafford13(biomeSeed);
    }

    /** Builds the world key from a 1.12.2 integer dimension id. */
    public static ResourceLocation keyForDimensionId(int dimensionId) {
        return new ResourceLocation(NAMESPACE, "dimension_" + dimensionId);
    }

    /** Builds the dimension-type location from 1.12.2's DimensionType enum, modded entries included. */
    public static ResourceLocation locationForDimensionType(@Nullable DimensionType type) {
        return type == null ? null : new ResourceLocation(NAMESPACE, type.getName());
    }

    @Override
    public int hashCode() {
        return (int) this.hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof WorldIdentifier other) {
            return other.hashCode == this.hashCode &&
                    other.biomeSeed == this.biomeSeed &&
                    Objects.equals(other.key, this.key) &&
                    Objects.equals(other.dimension, this.dimension)
                    ;
        }
        return false;
    }

    //Quick access utility method to get or create a world object in the current instance
    public WorldEngine getOrCreateEngine() {
        return getOrCreateEngine(false);
    }

    public WorldEngine getOrCreateEngine(boolean allowNull) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            this.cachedEngineObject = null;
            return null;
        }
        var engine = instance.getOrCreate(this);
        if (allowNull&&engine==null) {
            throw new IllegalStateException("Engine null on creation");
        }
        return engine;
    }

    public WorldEngine getNullable() {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            this.cachedEngineObject = null;
            return null;
        }
        return instance.getNullable(this);
    }

    public static WorldIdentifier of(World level) {
        //Gets or makes an identifier for world
        if (level == null) {
            return null;
        }
        return ((IWorldGetIdentifier)level).voxy$getIdentifier();
    }

    //Common utility function to get or create a world engine
    public static WorldEngine ofEngine(World level) {
        var id = of(level);
        if (id == null) {
            return null;
        }
        return id.getOrCreateEngine();
    }

    public static WorldEngine ofEngineNullable(World level) {
        var id = of(level);
        if (id == null) {
            return null;
        }
        return id.getNullable();
    }

    public static long mixStafford13(long seed) {
        seed += 918759875987111L;
        seed = (seed ^ seed >>> 30) * -4658895280553007687L;
        seed = (seed ^ seed >>> 27) * -7723592293110705685L;
        return seed ^ seed >>> 31;
    }

    public long getLongHash() {
        return this.hashCode;
    }

    private static long registryKeyHashCode(ResourceLocation key) {
        // Mirrors upstream's shape: the two halves of a ResourceKey (its registry and its
        // identifier) become the namespace and path of the ResourceLocation.
        var A = key==null?null:key.getNamespace();
        var B = key==null?null:key.getPath();
        int a = A==null?0:A.hashCode();
        int b = B==null?0:B.hashCode();
        return (Integer.toUnsignedLong(a)<<32)|Integer.toUnsignedLong(b);
    }


    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public String getWorldId() {
        return getWorldId(this);
    }

    public static String getWorldId(WorldIdentifier identifier) {
        String data = identifier.biomeSeed + identifier.key.toString();
        try {
            return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes())).substring(0, 32);
        } catch (
                NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "WorldIdentifier[" + this.key.toString() + ", " + this.biomeSeed + ", " + this.dimension.toString() + ']';
    }

    public static class GsonAdapter extends TypeAdapter<WorldIdentifier> {
        public static final GsonAdapter INSTANCE = new GsonAdapter();

        private GsonAdapter(){}

        @Override
        public void write(JsonWriter writer, WorldIdentifier identifier) throws IOException {
            writer.beginObject();

            writer.name("key");
            writer.value(identifier.key.toString());

            writer.name("biomeSeed");
            writer.value(identifier.biomeSeed);

            writer.name("dimension");
            writer.value(identifier.dimension.toString());

            writer.endObject();
        }


        private static final Gson GSON = new Gson();
        @Override
        public WorldIdentifier read(JsonReader reader) throws IOException {
            var obj = GSON.getAdapter(JsonElement.class).read(reader).getAsJsonObject();

            var sKey = obj.getAsJsonPrimitive("key").getAsString();
            long biomeSeed = obj.getAsJsonPrimitive("biomeSeed").getAsLong();
            var sDim = obj.getAsJsonPrimitive("dimension").getAsString();

            return new WorldIdentifier(new ResourceLocation(sKey), biomeSeed, new ResourceLocation(sDim));
        }
    }
}
