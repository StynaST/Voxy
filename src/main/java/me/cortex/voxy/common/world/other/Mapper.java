package me.cortex.voxy.common.world.other;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.util.Pair;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.world.biome.Biome;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;


//There are independent mappings for biome and block states, these get combined in the shader and allow for more
// variaty of things
public class Mapper {
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;

    private final IMappingStorage storage;
    public static final long UNKNOWN_MAPPING = -1;
    public static final long AIR = 0;

    private final ReentrantLock blockLock = new ReentrantLock();
    private final ConcurrentHashMap<IBlockState, StateEntry> block2stateEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private final ObjectArrayList<StateEntry> blockId2stateEntry = new ObjectArrayList<>();


    private final ReentrantLock biomeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, BiomeEntry> biome2biomeEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private final ObjectArrayList<BiomeEntry> biomeId2biomeEntry = new ObjectArrayList<>();

    private Consumer<StateEntry> newStateCallback;
    private Consumer<BiomeEntry> newBiomeCallback;
    public Mapper(IMappingStorage storage) {
        this.storage = storage;
        //Insert air since its a special entry (index 0)
        var airEntry = new StateEntry(0, Blocks.AIR.getDefaultState());
        this.block2stateEntry.put(airEntry.state, airEntry);
        this.blockId2stateEntry.add(airEntry);

        this.loadFromStorage();
    }


    /**
     * Port note: modern Minecraft has {@code BlockState#isAir()}. 1.12.2 only offers
     * {@code Block#isAir(state, world, pos)}, which needs a world and position Voxy does not always
     * have. Testing the material is the position-independent equivalent, and it correctly covers
     * modded air blocks (barriers, structure voids) that set Material.AIR.
     */
    public static boolean isAir(IBlockState state) {
        return state == null || state.getMaterial() == Material.AIR;
    }

    public static boolean isAir(long id) {
        //Note: air can mean void, cave or normal air, as the block state is remapped during ingesting
        return (id&(((1L<<20)-1)<<27)) == 0;
    }

    public static int getBlockId(long id) {
        return (int) ((id>>27)&((1<<20)-1));
    }

    public static int getBiomeId(long id) {
        return (int) ((id>>47)&0x1FF);
    }

    public static int getLightId(long id) {
        return (int) ((id>>56)&0xFF);
    }

    public static long withLight(long id, int light) {
        return (id&(~(0xFFL<<56)))|(Integer.toUnsignedLong(light&0xFF)<<56);
    }

    public static long withBlockBiome(long id, int block, int biome) {
        return (id&(0xFFL<<56))|(Integer.toUnsignedLong(block)<<27)|(Integer.toUnsignedLong(biome)<<47);
    }

    public static long airWithLight(int light) {
        return Integer.toUnsignedLong(light&0xFF)<<56;
    }

    public void setStateCallback(Consumer<StateEntry> stateCallback) {
        this.newStateCallback = stateCallback;
    }

    public void setBiomeCallback(Consumer<BiomeEntry> biomeCallback) {
        this.newBiomeCallback = biomeCallback;
    }

    private void loadFromStorage() {
        //TODO: FIXME: have/store the minecraft version the mappings are from (the data version)
        // SharedConstants.getGameVersion().dataVersion().id()
        // then use this to create an update path instead

        var mappings = this.storage.getIdMappingsData();
        List<StateEntry> sentries = new ArrayList<>();
        List<BiomeEntry> bentries = new ArrayList<>();
        List<Pair<byte[], Integer>> sentryErrors = new ArrayList<>();

        boolean[] forceResave = new boolean[1];
        for (var entry : mappings.int2ObjectEntrySet()) {
            int entryType = entry.getIntKey()>>>30;
            int id = entry.getIntKey() & ((1<<30)-1);
            if (entryType == BLOCK_STATE_TYPE) {
                var sentry = StateEntry.deserialize(id, entry.getValue(), forceResave);
                if (isAir(sentry.state)) {
                    Logger.error("Deserialization was air, removed block");
                    sentryErrors.add(new Pair<>(entry.getValue(), id));
                    continue;
                }
                sentries.add(sentry);
                var oldEntry = this.block2stateEntry.putIfAbsent(sentry.state, sentry);
                if (oldEntry != null) {
                    //forceResave[0] |= true;
                    Logger.warn("Multiple mappings for blockstate, using old state, expect things to possibly go really badly. " + oldEntry.id + ":" + sentry.id + ":" + sentry.state );
                }
            } else if (entryType == BIOME_TYPE) {
                var bentry = BiomeEntry.deserialize(id, entry.getValue());
                bentries.add(bentry);
                if (this.biome2biomeEntry.put(bentry.biome, bentry) != null) {
                    throw new IllegalStateException("Multiple mappings for biome entry");
                }
            } else {
                throw new IllegalStateException("Unknown entryType");
            }
        }

        if (!sentryErrors.isEmpty()) {
            forceResave[0] |= true;
            //Insert garbage types into the mapping for those blocks, TODO:FIXME: Need to upgrade the type or have a solution to error blocks
            // Port note: upstream picks random entries out of Block.BLOCK_STATE_REGISTRY until it
            // finds an unused one. 1.12.2's equivalent map (Block.BLOCK_STATE_IDS) is sparse -- most
            // ids in its range decode to air -- so random probing can spin for a long time. Walking
            // the id space instead terminates, and still satisfies the only real requirement: give
            // each broken entry some distinct non-air placeholder so ids stay dense and ordered.
            int probe = 0;
            int stateIdLimit = Block.BLOCK_STATE_IDS.size();
            for (var error : sentryErrors) {
                StateEntry placeholder = null;
                while (probe < stateIdLimit) {
                    var candidate = Block.getStateById(probe++);
                    if (isAir(candidate)) {
                        continue;
                    }
                    var attempt = new StateEntry(error.right(), candidate);
                    if (this.block2stateEntry.put(candidate, attempt) == null) {
                        placeholder = attempt;
                        break;
                    }
                }
                if (placeholder == null) {
                    throw new IllegalStateException("Ran out of block states to use as placeholders for corrupt mapping id " + error.right());
                }
                sentries.add(placeholder);
            }
        }

        //Insert into the arrays
        sentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
            if (this.blockId2stateEntry.size() != entry.id) {
                throw new IllegalStateException("Block entry not ordered");
            }
            this.blockId2stateEntry.add(entry);
        });

        bentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
            if (this.biomeId2biomeEntry.size() != entry.id) {
                throw new IllegalStateException("Biome entry not ordered. got " + entry.biome + " with id " + entry.id + " expected id " + this.biomeId2biomeEntry.size());
            }
            this.biomeId2biomeEntry.add(entry);
        });

        if (forceResave[0]) {
            Logger.warn("Forced state resave triggered");
            this.forceResaveStates();
        }
    }

    public final int getBlockStateCount() {
        return this.blockId2stateEntry.size();
    }

    private StateEntry registerNewBlockState(IBlockState state) {
        this.blockLock.lock();
        var entry = this.block2stateEntry.get(state);
        if (entry != null) {
            this.blockLock.unlock();
            return entry;
        }

        entry = new StateEntry(this.blockId2stateEntry.size(), state);
        this.blockId2stateEntry.add(entry);
        this.block2stateEntry.put(state, entry);
        this.blockLock.unlock();

        byte[] serialized = entry.serialize();
        ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
        buffer.put(serialized);
        buffer.rewind();
        this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE<<30), buffer);
        MemoryUtil.memFree(buffer);
        //this.storage.flush();

        if (this.newStateCallback!=null)this.newStateCallback.accept(entry);
        return entry;
    }

    private BiomeEntry registerNewBiome(String biome) {
        this.biomeLock.lock();
        var entry = this.biome2biomeEntry.get(biome);
        if (entry != null) {
            this.biomeLock.unlock();
            return entry;
        }
        entry = new BiomeEntry(this.biomeId2biomeEntry.size(), biome);
        this.biomeId2biomeEntry.add(entry);
        this.biome2biomeEntry.put(biome, entry);
        this.biomeLock.unlock();

        byte[] serialized = entry.serialize();
        ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
        buffer.put(serialized);
        buffer.rewind();
        this.storage.putIdMapping(entry.id | (BIOME_TYPE<<30), buffer);
        MemoryUtil.memFree(buffer);
        //this.storage.flush();

        if (this.newBiomeCallback!=null)this.newBiomeCallback.accept(entry);
        return entry;
    }


    //TODO:FIXME: IS VERY SLOW NEED TO MAKE IT LOCK FREE, or at minimum use a concurrent map
    public long getBaseId(byte light, IBlockState state, Biome biome) {
        if (isAir(state)) return Byte.toUnsignedLong(light) <<56;//Special case and fast return for air, dont care about the biome
        return composeMappingId(light, this.getIdForBlockState(state), this.getIdForBiome(biome));
    }

    public IBlockState getBlockStateFromBlockId(int blockId) {
        return this.blockId2stateEntry.get(blockId).state;
    }

    public int getIdForBlockState(IBlockState state) {
        if (isAir(state)) {
            return 0;
        }
        var mapping = this.block2stateEntry.get(state);
        if (mapping == null) {
            mapping = this.registerNewBlockState(state);
        }
        return mapping.id;
    }

    public int getBlockStateOpacity(long mappingId) {
        return this.getBlockStateOpacity(getBlockId(mappingId));
    }

    public int getBlockStateOpacity(int blockId) {
        return this.blockId2stateEntry.get(blockId).opacity;
    }

    public int getIdForBiome(Biome biome) {
        String biomeId = Biome.REGISTRY.getNameForObject(biome).toString();
        var entry = this.biome2biomeEntry.get(biomeId);
        if (entry == null) {
            entry = this.registerNewBiome(biomeId);
        }
        return entry.id;
    }

    public static long composeMappingId(byte light, int blockId, int biomeId) {
        if (blockId == AIR) {//Dont care about biome for air
            return Byte.toUnsignedLong(light)<<56;
        }
        return (Byte.toUnsignedLong(light)<<56)|(Integer.toUnsignedLong(biomeId) << 47)|(Integer.toUnsignedLong(blockId)<<27);
    }

    //TODO: fixme: synchronize access to this.blockId2stateEntry
    public StateEntry[] getStateEntries() {
        this.blockLock.lock();
        var set = new ArrayList<>(this.blockId2stateEntry);
        StateEntry[] out = new StateEntry[set.size()];
        int i = 0;
        for (var entry : set) {
            if (entry.id != i++) {
                throw new IllegalStateException();
            }
            out[i-1] = entry;
        }
        this.blockLock.unlock();
        return out;
    }

    //TODO: fixme: synchronize access to this.biomeId2biomeEntry
    public BiomeEntry[] getBiomeEntries() {
        this.biomeLock.lock();
        var set = new ArrayList<>(this.biomeId2biomeEntry);
        BiomeEntry[] out = new BiomeEntry[set.size()];
        int i = 0;
        for (var entry : set) {
            if (entry.id != i++) {
                throw new IllegalStateException();
            }
            out[i-1] = entry;
        }
        this.biomeLock.unlock();
        return out;
    }

    public void forceResaveStates() {
        var blocks = new ArrayList<>(this.block2stateEntry.values());
        var biomes = new ArrayList<>(this.biome2biomeEntry.values());


        for (var entry : blocks) {
            if (isAir(entry.state) && entry.id == 0) {
                continue;
            }
            if (this.blockId2stateEntry.indexOf(entry) != entry.id) {
                throw new IllegalStateException("State Id NOT THE SAME, very critically bad. arr:" + this.blockId2stateEntry.indexOf(entry) + " entry: " + entry.id);
            }
            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
        }

        for (var entry : biomes) {
            if (this.biomeId2biomeEntry.indexOf(entry) != entry.id) {
                throw new IllegalStateException("Biome Id NOT THE SAME, very critically bad");
            }

            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BIOME_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
        }

        this.storage.flush();
    }

    public void close() {

    }


    public static final class StateEntry {
        public final int id;
        public final IBlockState state;
        public final int opacity;
        public StateEntry(int id, IBlockState state) {
            this.id = id;
            this.state = state;
            //Override opacity of leaves to be solid
            if (state.getBlock() instanceof BlockLeaves) {
                this.opacity = 15;
            } else {
                this.opacity = state.getLightOpacity();
            }
        }

        // Port note: upstream round-trips through BlockState.CODEC. 1.12.2's direct equivalent is
        // NBTUtil.writeBlockState/readBlockState, which stores the block's registry name plus its
        // property values rather than a raw id -- so mappings still survive block id churn between
        // launches, which is the property Voxy actually depends on here.
        public byte[] serialize() {
            try {
                var serialized = new NBTTagCompound();
                serialized.setInteger("id", this.id);
                serialized.setTag("block_state", NBTUtil.writeBlockState(new NBTTagCompound(), this.state));
                var out = new ByteArrayOutputStream();
                CompressedStreamTools.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static StateEntry deserialize(int id, byte[] data, boolean[] forceResave) {
            try {
                var compound = CompressedStreamTools.readCompressed(new ByteArrayInputStream(data));
                if (!compound.hasKey("id") || compound.getInteger("id") != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                // Port note: there is no datafixer path here. 1.12.2's DataFixer has no
                // BLOCK_STATE reference to update against, and readBlockState already degrades to
                // air for a block that is no longer registered -- which is the same outcome
                // upstream reaches after its fixer fails. The caller treats an air result as a
                // corrupt entry and resaves, so behaviour matches.
                var state = NBTUtil.readBlockState(compound.getCompoundTag("block_state"));
                if (isAir(state)) {
                    Logger.error("Could not decode blockstate setting to air. id:" + id);
                    return new StateEntry(id, Blocks.AIR.getDefaultState());
                }
                return new StateEntry(id, state);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final class BiomeEntry {
        public final int id;
        public final String biome;

        public BiomeEntry(int id, String biome) {
            this.id = id;
            this.biome = biome;
        }

        public byte[] serialize() {
            try {
                var serialized = new NBTTagCompound();
                serialized.setInteger("id", this.id);
                serialized.setString("biome_id", this.biome);
                var out = new ByteArrayOutputStream();
                CompressedStreamTools.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static BiomeEntry deserialize(int id, byte[] data) {
            try {
                var compound = CompressedStreamTools.readCompressed(new ByteArrayInputStream(data));
                if (!compound.hasKey("id") || compound.getInteger("id") != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                // getString returns "" for a missing key in 1.12.2, where upstream's getStringOr
                // returns null; normalise so a truncated entry stays detectable downstream.
                String biome = compound.hasKey("biome_id") ? compound.getString("biome_id") : null;
                return new BiomeEntry(id, biome);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
