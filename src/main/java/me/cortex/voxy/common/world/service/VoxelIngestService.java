package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.IBiomeSupplier;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.init.Biomes;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Port notes for 1.12.2:
 *
 * <ul>
 *   <li><b>Lighting.</b> Upstream has to interrogate a separate light engine and ask whether each
 *       section is in the LIGHT_AND_DATA state before it can read light data. 1.12.2 stores light
 *       inside the chunk section itself, as two NibbleArrays, so "do we have lighting" is a null
 *       check on those. Not {@code Chunk#isLightPopulated()} -- that is world-gen state the client
 *       never sets, so gating on it stops multiplayer ingesting anything.</li>
 *   <li><b>Biomes.</b> They live on the chunk as a flat {@code byte[256]}, not on the section, so
 *       the biome array has to be captured into the queued job alongside the section.</li>
 *   <li><b>Section range.</b> 1.12.2 worlds are always 0..255, so the section index is simply the
 *       array index, with no {@code getMinSectionY()} offset.</li>
 * </ul>
 */
public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    private record IngestSection(int cx, int cy, int cz, WorldEngine world,
                                 WorldConversionFactory.BlockStateSnapshot blockStates, boolean empty,
                                 NibbleArray blockLight, NibbleArray skyLight, byte[] biomes){}
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.pop();
        try {
            var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

            if (task.empty && task.blockLight == null && task.skyLight == null) {//If the chunk section has lighting data, propagate it
                //Only clear what is stored, else every air section above terrain gets created
                var existing = task.world.acquireIfExists(0, task.cx >> 1, task.cy >> 1, task.cz >> 1);
                if (existing == null) {
                    return;
                }
                existing.release();
                WorldUpdater.insertUpdate(task.world, vs.zero());
            } else {
                VoxelizedSection csec = WorldConversionFactory.convert(
                        vs,
                        task.world.getMapper(),
                        task.blockStates,
                        getBiomeSupplier(task),
                        getLightingSupplier(task)
                );
                WorldVoxilizedSectionMipper.mipSection(csec, task.world.getMapper());
                WorldUpdater.insertUpdate(task.world, csec);
            }
        } finally {
            //Release the ref we had acquired for the world
            task.world.releaseRef();
        }
    }

    /**
     * 1.12.2 biome ids are unsigned bytes indexed by (z &lt;&lt; 4) | x. An id with no registered
     * biome decodes to null, so it falls back to plains rather than propagating a null into the
     * mapper.
     */
    @NotNull
    private static IBiomeSupplier getBiomeSupplier(IngestSection task) {
        var biomes = task.biomes;
        if (biomes == null) {
            return (x, z) -> Biomes.PLAINS;
        }
        return (x, z) -> {
            int id = biomes[(z << 4) | x] & 0xFF;
            return Biome.getBiome(id, Biomes.PLAINS);
        };
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null;
        boolean bl = bla != null;
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private static boolean shouldIngestSection(ExtendedBlockStorage section, int cx, int cy, int cz) {
        return true;
    }

    /** 1.12.2 has no NibbleArray#copy, and the array is mutated on the client thread while we read it off-thread. */
    private static NibbleArray copyNibble(NibbleArray array) {
        return array == null ? null : new NibbleArray(array.getData().clone());
    }

    public boolean enqueueIngest(WorldEngine engine, Chunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var sections = chunk.getBlockStorageArray();
        var biomes = chunk.getBiomeArray().clone();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Upstream asks the light engine whether each section holds real light data; here a section
        // carries its own light arrays, so the arrays themselves are the check.
        // Do NOT gate this on Chunk#isLightPopulated: that flag is world-gen state and nothing on the
        // client ever sets it, so on a server every chunk reports false and never ingests at all.
        boolean gotLighting = false;
        boolean allEmpty = true;
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null || !shouldIngestSection(section, chunkX, i, chunkZ)) continue;
            if (section.getBlockLight() != null || section.getSkyLight() != null) {
                gotLighting = true;
            }
        }
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null || !shouldIngestSection(section, chunkX, i, chunkZ)) continue;
            allEmpty &= section.isEmpty();
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            for (int i = 0; i < sections.length; i++) {
                var section = sections[i];
                if (section == null || !shouldIngestSection(section, chunkX, i, chunkZ)) continue;
                engine.acquireRef();
                this.ingestQueue.add(new IngestSection(chunkX, i, chunkZ, engine, null, true, null, null, biomes));
                try {
                    this.service.execute();
                } catch (Exception e) {
                    Logger.error("Executing had an error: assume shutting down, aborting",e);
                    engine.releaseRef();//we must manually release
                    break;
                }
            }
        }

        if (!gotLighting) {
            return false;
        }

        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null || !shouldIngestSection(section, chunkX, i, chunkZ)) continue;

            var blockStates = WorldConversionFactory.snapshot(section.getData());
            boolean empty = section.isEmpty();
            var bl = copyNibble(section.getBlockLight());
            var sl = copyNibble(section.getSkyLight());

            engine.acquireRef();//This is not great but dont really have a better solution as all the others have there own problem
            this.ingestQueue.add(new IngestSection(chunkX, i, chunkZ, engine, blockStates, empty, bl, sl, biomes));
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting",e);
                engine.releaseRef();//we must manually release
                break;
            }
        }

        //1.12.2 nulls a section once it holds only air, where modern MC keeps the object, so
        //upstream's "section == null" skip would leave removed terrain in storage forever
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null) continue;
            engine.acquireRef();
            this.ingestQueue.add(new IngestSection(chunkX, i, chunkZ, engine, null, true, null, null, biomes));
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting",e);
                engine.releaseRef();//we must manually release
                break;
            }
        }
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
        while (!this.ingestQueue.isEmpty()) {
            //We need to manually release all our world locks
            this.ingestQueue.pop().world.releaseRef();
        }

    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, Chunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(Chunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getWorld()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, ExtendedBlockStorage section, int x, int y, int z, NibbleArray bl, NibbleArray sl, byte[] biomes) {
        var blockStates = WorldConversionFactory.snapshot(section.getData());
        boolean empty = section.isEmpty();
        engine.acquireRef();
        this.ingestQueue.add(new IngestSection(x, y, z, engine, blockStates, empty, bl, sl, biomes));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            engine.releaseRef();//we must manually release
            return false;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, ExtendedBlockStorage section, int x, int y, int z, NibbleArray bl, NibbleArray sl, byte[] biomes) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, x, y, z, bl, sl, biomes);
    }

    public static boolean rawIngest(WorldEngine engine, ExtendedBlockStorage section, int x, int y, int z, NibbleArray bl, NibbleArray sl, byte[] biomes) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, bl, sl, biomes);
    }
}
