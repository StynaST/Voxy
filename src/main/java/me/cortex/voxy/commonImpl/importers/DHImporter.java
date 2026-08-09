package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.ByteBufferBackedInputStream;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.Zstd;
import org.tukaani.xz.BasicArrayCache;
import org.tukaani.xz.ResettableArrayCache;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

//Imports a Distant Horizons / Vintage Horizons sqlite database. VH is a backport of DH 2.x and keeps
// the same schema and the same on disk data format, so the only version specific part is decoding the
// block state strings in the mapping table back into 1.12.2 IBlockStates.
public class DHImporter implements IDataImporter {
    private final Connection db;
    private final WorldEngine engine;
    private final Service service;
    private final World world;
    private final int bottomOfWorld;
    private final int worldHeightSections;
    private final Biome defaultBiome;
    private Thread runner;
    private volatile boolean isRunning = false;
    private final AtomicInteger processedChunks = new AtomicInteger();
    private int totalChunks;
    private IUpdateCallback updateCallback;

    private record Task(int x, int z, int fmt, int compression) {
        public long distanceFromZero() {
            return ((long)this.x)*this.x+((long)this.z)*this.z;
        }
    }
    private final ConcurrentLinkedDeque<Task> tasks = new ConcurrentLinkedDeque<>();
    private static final class WorkCTX {
        private final PreparedStatement stmt;
        private final ResettableArrayCache cache;
        private final long[] storageCache;
        private final byte[] colScratch;
        private final VoxelizedSection section;

        private ByteBuffer zstdScratch;
        private ByteBuffer zstdScratch2;
        private final long zstdDCtx;

        public WorkCTX(PreparedStatement stmt, int worldHeight) {
            this.stmt = stmt;
            this.cache = new ResettableArrayCache(new BasicArrayCache());
            this.storageCache = new long[64*16*worldHeight];
            this.colScratch = new byte[1<<16];
            this.section = VoxelizedSection.createEmpty();
            this.zstdDCtx = Zstd.ZSTD_createDCtx();
        }

        public void free() {
            if (this.zstdScratch != null) {
                MemoryUtil.memFree(this.zstdScratch);
                MemoryUtil.memFree(this.zstdScratch2);
                Zstd.ZSTD_freeDCtx(this.zstdDCtx);
            }
        }
    }

    public DHImporter(File file, WorldEngine worldEngine, World mcWorld, ServiceManager servicePool, BooleanSupplier rateLimiter) {
        this.engine = worldEngine;
        this.world = mcWorld;
        //1.12.2 has no dynamic registry access, both registries are global singletons
        this.defaultBiome = Biomes.PLAINS;

        //1.12.2 worlds always start at y=0, and getHeight() is the build height of the dimension
        this.bottomOfWorld = 0;
        int worldHeight = mcWorld.getHeight();
        this.worldHeightSections = (worldHeight+15)/16;

        String con = "jdbc:sqlite:" + file.getPath();
        try {
            this.db = DriverManager.getConnection(con);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        this.service = servicePool.createService(()->{
            try {
                var dataFetchStmt = this.db.prepareStatement("SELECT Data,ColumnGenerationStep,Mapping FROM FullData WHERE DetailLevel = 0 AND PosX = ? AND PosZ = ?;");
                var ctx = new WorkCTX(dataFetchStmt, this.worldHeightSections*16);
                return new Pair<>(()->{
                    this.importSection(dataFetchStmt, ctx, this.tasks.poll());
                },()->{
                    ctx.free();
                    try {
                        dataFetchStmt.close();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, 10, "DH Importer", rateLimiter);
    }

    public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
        if (this.isRunning()) {
            throw new IllegalStateException();
        }
        this.engine.acquireRef();
        this.updateCallback = updateCallback;
        this.runner = new Thread(()-> {
            Queue<Task> taskQ = new PriorityQueue<>(Comparator.comparingLong(Task::distanceFromZero));
            try (var stmt = this.db.createStatement()) {
                var resSet = stmt.executeQuery("SELECT PosX,PosZ,CompressionMode,DataFormatVersion FROM FullData WHERE DetailLevel = 0;");
                while (resSet.next()) {
                    int x = resSet.getInt(1);
                    int z = resSet.getInt(2);
                    int compression = resSet.getInt(3);
                    int format = resSet.getInt(4);
                    if (format != 1) {
                        Logger.warn("Unknown format mode: " + format);
                        continue;
                    }
                    if (compression != 3 && compression != 4) {
                        Logger.warn("Unknown compression mode: " + compression);
                        continue;
                    }
                    taskQ.add(new Task(x, z, format, compression));
                }
                resSet.close();

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            this.totalChunks = taskQ.size() * (4*4);//(since there are 4*4 chunks to every dh section)

            while (this.isRunning&&!taskQ.isEmpty()) {
                this.tasks.add(taskQ.poll());
                this.service.execute();

                while (this.tasks.size() > 100 && this.isRunning) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            while (!this.tasks.isEmpty()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            completionCallback.onCompletion(this.processedChunks.get());
            this.shutdown();
        });
        this.isRunning = true;
        this.runner.setDaemon(true);
        this.runner.start();
    }

    //Applies a single serialized property value to a state. DH serializes property values with the
    // game's own name for them, but which of the two 1.12.2 spellings that is depends on the property
    // type, so both are accepted (they only ever differ for properties whose value type does not
    // override toString with its serialized name).
    private static <T extends Comparable<T>> IBlockState applyProperty(IBlockState state, IProperty<T> property, String value) {
        for (T allowed : property.getAllowedValues()) {
            if (property.getName(allowed).equals(value) || allowed.toString().equals(value)) {
                return state.withProperty(property, allowed);
            }
        }
        return null;
    }

    //Decodes DH's "{name:value}{name:value}" property encoding onto a block's default state.
    //Upstream instead serializes every possible state of the block and string compares, which needs
    // the serialization to match DH's byte for byte; applying properties one at a time gets the same
    // result while tolerating an unknown property or value (that property just keeps its default).
    private static IBlockState parseBlockState(Block block, String serializedProperties, String forLogging) {
        IBlockState state = block.getDefaultState();
        int idx = 0;
        while (idx < serializedProperties.length()) {
            int start = serializedProperties.indexOf('{', idx);
            if (start == -1) {
                break;
            }
            int end = serializedProperties.indexOf('}', start);
            if (end == -1) {
                Logger.warn("Malformed block state properties", forLogging);
                break;
            }
            int split = serializedProperties.indexOf(':', start);
            if (split == -1 || split > end) {
                Logger.warn("Malformed block state property entry", forLogging);
                idx = end + 1;
                continue;
            }
            String name = serializedProperties.substring(start + 1, split);
            String value = serializedProperties.substring(split + 1, end);

            IProperty<?> property = null;
            for (IProperty<?> candidate : state.getPropertyKeys()) {
                if (candidate.getName().equals(name)) {
                    property = candidate;
                    break;
                }
            }
            if (property == null) {
                Logger.warn("Block has no property named " + name, forLogging);
            } else {
                var withProperty = applyProperty(state, property, value);
                if (withProperty == null) {
                    Logger.warn("Property " + name + " has no value " + value, forLogging);
                } else {
                    state = withProperty;
                }
            }
            idx = end + 1;
        }
        return state;
    }

    //TODO: add global mapping cache (with thread local secondary cache)
    private long[] readMappings(InputStream in, WorkCTX ctx) throws IOException {
        final String BLOCK_STATE_SEPARATOR_STRING = "_DH-BSW_";
        final String STATE_STRING_SEPARATOR = "_STATE_";
        var stream = new DataInputStream(in);
        int entries = stream.readInt();
        if (entries < 0)
            throw new IllegalStateException();
        long[] out = new long[entries];
        for (int i = 0; i < entries; i++) {
            int biomeId;
            int blockId;
            String encEntry = stream.readUTF();
            int idx = encEntry.indexOf(BLOCK_STATE_SEPARATOR_STRING);
            if (idx == -1)
                throw new IllegalStateException();
            {
                var biomeRes = new ResourceLocation(encEntry.substring(0, idx));
                var biome = Biome.REGISTRY.getObject(biomeRes);
                if (biome == null) {
                    biome = this.defaultBiome;
                }
                biomeId = this.engine.getMapper().getIdForBiome(biome);
            }
            {
                int b = idx + BLOCK_STATE_SEPARATOR_STRING.length();
                if (encEntry.substring(b).equals("AIR")) {
                    blockId = 0;
                } else {
                    var sIdx = encEntry.indexOf(STATE_STRING_SEPARATOR, b);
                    String bStateStr = null;
                    if (sIdx != -1) {
                        bStateStr = encEntry.substring(sIdx + STATE_STRING_SEPARATOR.length());
                    }
                    var bId = new ResourceLocation(encEntry.substring(b, sIdx != -1 ? sIdx : encEntry.length()));
                    //REGISTRY is defaulted, so a missing block silently resolves to air, hence the explicit check
                    Block block = Block.REGISTRY.containsKey(bId) ? Block.REGISTRY.getObject(bId) : Blocks.AIR;
                    var state = block.getDefaultState();
                    if (bStateStr != null && block != Blocks.AIR) {
                        state = parseBlockState(block, bStateStr, encEntry.substring(b));
                    }
                    if (block == Blocks.AIR) {
                        Logger.warn("Could not find block entry with id:", bId);
                    }
                    blockId = this.engine.getMapper().getIdForBlockState(state);
                }
            }
            out[i] = Mapper.composeMappingId((byte) 0, blockId, biomeId);
        }
        stream.close();
        return out;
    }

    private static int getId(long dp) {
        return (int)(dp&Integer.MAX_VALUE);
    }

    private static int getHeight(long dp) {
        return (int)((dp>>>32)&((1<<12)-1));
    }

    private static int getMinHeight(long dp) {
        return (int)((dp>>>(32+12))&((1<<12)-1));
    }

    private static int getSkyLight(long dp) {
        return (int)((dp>>>(32+12+12))&0xF);
    }

    private static int getBlockLight(long dp) {
        return (int)((dp>>>(32+12+12+4))&0xF);
    }

    private static InputStream createDecompressedStream(int decompressor, InputStream in, WorkCTX ctx) throws IOException {
        if (decompressor == 3) {
            ctx.cache.reset();
            return new XZInputStream(new BufferedInputStream(in), -1, false, ctx.cache);
        } else if (decompressor == 4) {
            if (ctx.zstdScratch == null) {
                ctx.zstdScratch = MemoryUtil.memAlloc(8196);
                ctx.zstdScratch2 = MemoryUtil.memAlloc(8196);
            }
            ctx.zstdScratch.clear();
            ctx.zstdScratch2.clear();
            //Read the whole compressed frame in, growing the scratch buffer whenever it fills up
            try (var channel = Channels.newChannel(in)) {
                while (true) {
                    if (!ctx.zstdScratch.hasRemaining()) {
                        var newBuffer = MemoryUtil.memAlloc(ctx.zstdScratch.capacity()*2);
                        newBuffer.put(ctx.zstdScratch.flip());
                        MemoryUtil.memFree(ctx.zstdScratch);
                        ctx.zstdScratch = newBuffer;
                    }
                    if (channel.read(ctx.zstdScratch) == -1) {
                        break;
                    }
                }
            }
            ctx.zstdScratch.limit(ctx.zstdScratch.position()).rewind();
            {
                int decompSize = (int) Zstd.ZSTD_getFrameContentSize(ctx.zstdScratch);
                if (ctx.zstdScratch2.capacity() < decompSize) {
                    MemoryUtil.memFree(ctx.zstdScratch2);
                    ctx.zstdScratch2 = MemoryUtil.memAlloc((int) (decompSize * 1.1));
                }
            }
            long size = Zstd.ZSTD_decompressDCtx(ctx.zstdDCtx, ctx.zstdScratch, ctx.zstdScratch2);
            if (Zstd.ZSTD_isError(size)) {
                throw new IllegalStateException("ZSTD EXCEPTION: " + Zstd.ZSTD_getErrorName(size));
            }
            ctx.zstdScratch2.limit((int) size);
            return new ByteBufferBackedInputStream(ctx.zstdScratch2);
        } else {
            throw new IllegalArgumentException("Unknown compressor " + decompressor);
        }
    }

    //TODO: create VoxelizedSection of 32*32*32
    private void readColumnData(int X, int Z, InputStream in, WorkCTX ctx, long[] mapping) throws IOException {
        //TODO: add datacache betweein XZ input stream
        var stream = new DataInputStream(in);
        long[] storage = ctx.storageCache;
        VoxelizedSection section = ctx.section;
        byte[] col = ctx.colScratch;
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int bPos = Integer.expand(x&0xF, 0b00_00_0000_0000_1111) |
                           Integer.expand(z, 0b00_11_0000_1111_0000);
                short cl = stream.readShort();
                if (cl < 0) {
                    throw new IllegalStateException();
                }
                //readFully, not read: the decompressed streams both hand back short reads
                stream.readFully(col, 0, cl*8);
                for (int j = 0; j < cl; j++) {
                    long entry = (long) LONG.get(col, j*8);
                    long mEntry = Mapper.withLight(mapping[getId(entry)], (getBlockLight(entry) << 4) | getSkyLight(entry));
                    int startY = getMinHeight(entry);
                    int tall = getHeight(entry);
                    int endY = Math.min(startY+tall, this.worldHeightSections*16);
                    //if (endY < startY+tall && ((this.worldHeightSections*16)+1 != startY+tall)) {
                    //    int a = 0;
                    //}
                    //Insert all entries into data cache
                    startY = Integer.expand(startY, 0b11111111_00_1111_0000_0000);
                    endY = Integer.expand(endY, 0b11111111_00_1111_0000_0000);
                    final int Msk = 0b11111111_00_1111_0000_0000;
                    final int iMsk1 = (~Msk)+1;
                    for (int y = startY; y != endY; y = (y+iMsk1)&Msk) {
                        storage[y+bPos] = mEntry;
                        //touched[(idx >>> 12)>>6] |= 1L<<(idx&0x3f);
                    }
                }
            }

            if ((x+1)%16==0) {
                for (int sz = 0; sz < 4; sz++) {
                    for (int sy = 0; sy < this.worldHeightSections; sy++) {
                        {
                            int base = (sz|(sy<<2))<<12;
                            int nonAirCount = 0;
                            final var dat = section.section;
                            for (int i = 0; i < 4096; i++) {
                                nonAirCount += Mapper.isAir(dat[i] = storage[i+base])?0:1;
                            }
                            section.lvl0NonAirCount = nonAirCount;
                        }

                        WorldVoxilizedSectionMipper.mipSection(section, this.engine.getMapper());

                        section.setPosition(X*4+(x>>4), sy+(this.bottomOfWorld>>4), (Z*4)+sz);
                        WorldUpdater.insertUpdate(this.engine, section);
                    }

                    int count = this.processedChunks.incrementAndGet();
                    this.updateCallback.onUpdate(count, this.totalChunks);
                }
                Arrays.fill(storage, 0);
                //Process batch
            }
        }
        stream.close();
    }
    private void importSection(PreparedStatement dataFetchStmt, WorkCTX ctx, Task task) {
        if (!this.isRunning || task == null) {
            return;
        }
        try {
            dataFetchStmt.setInt(1, task.x);
            dataFetchStmt.setInt(2, task.z);
            try (var rs = dataFetchStmt.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                var mapping = readMappings(createDecompressedStream(task.compression, rs.getBinaryStream(3), ctx), ctx);
                //var columnGenStep = new byte[64*64];
                //readStream(rs.getBinaryStream(2), cache, columnGenStep);
                readColumnData(task.x, task.z, createDecompressedStream(task.compression, rs.getBinaryStream(1), ctx), ctx, mapping);
            };
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void shutdown() {
        if (!this.isRunning) {
            return;
        }
        this.isRunning = false;
        while (!this.tasks.isEmpty())
            this.tasks.poll();
        try {
            if (this.runner != Thread.currentThread()) {
                this.runner.join();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.service.shutdown();
        this.engine.releaseRef();
        try {
            this.db.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        this.updateCallback = null;
        this.runner = null;
    }

    @Override
    public boolean isRunning() {
        return this.isRunning;
    }

    @Override
    public WorldEngine getEngine() {
        return this.engine;
    }

    private static VarHandle create(Class<?> viewArrayClass) {
        return MethodHandles.byteArrayViewVarHandle(viewArrayClass, ByteOrder.BIG_ENDIAN);
    }

    public static final boolean HasRequiredLibraries;

    private static final VarHandle LONG = create(long[].class);
    static {
        boolean hasJDBC = false;
        try {
            Class.forName("org.sqlite.JDBC");
            Class.forName("org.tukaani.xz.XZInputStream");
            hasJDBC = true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            //throw new RuntimeException(e);
            Logger.warn("Unable to load sqlite JDBC or lzma decompressor, DHImporting wont be available");
        }
        HasRequiredLibraries = hasJDBC;
    }

}
