package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorBlockStateContainer;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.BlockStatePaletteRegistry;
import net.minecraft.world.chunk.IBlockStatePalette;

import java.util.WeakHashMap;

/**
 * Port notes for 1.12.2, all in the two places this class actually touches Minecraft:
 *
 * <ul>
 *   <li><b>Bit packing.</b> Modern {@code SimpleBitStorage} never lets an entry straddle two longs,
 *       so upstream unpacks with a running shift and a fixed entries-per-long stride. 1.12.2's
 *       {@link net.minecraft.util.BitArray} <em>does</em> straddle, so that loop would silently
 *       decode garbage here. The unpack below reads by absolute bit offset and stitches across the
 *       long boundary instead.</li>
 *   <li><b>Palettes.</b> Linear/HashMap/Registry replace Linear/HashMap/SingleValue/Global. There is
 *       no single-value palette in 1.12.2 (a uniform section is just a 4-bit linear palette), and no
 *       Lithium palette to special-case, since Lithium does not exist for this version.</li>
 *   <li><b>Biomes.</b> 1.12.2 biomes are 2D and per-column, so the 4x4x4 cell grid Voxy stores is
 *       filled by sampling the column biome once per horizontal cell and replicating down Y. The
 *       storage format and the shader-side layout are unchanged.</li>
 * </ul>
 */
public class WorldConversionFactory {

    /** Immutable copy of the palette and packed block-state data captured before async ingest. */
    public record BlockStateSnapshot(long[] storage, IBlockState[] palette) {}

    private static final class Cache {
        private final int[] biomeCache = new int[4*4*4];
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<IBlockState>> localMapping = new WeakHashMap<>();
        private int[] paletteCache = new int[1024];
        private final long[] zoomCellCache = new long[5*5*5];
        private Reference2IntOpenHashMap<IBlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }
        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }

    //TODO: create a mapping for world/mapper -> local mapping
    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    /**
     * A chunk section always holds 4096 entries, and 1.12.2 sizes the backing array as
     * {@code roundUp(4096 * bits, 64) / 64}, which is exactly {@code 64 * bits}. So the bit width is
     * recoverable from the array length, and we avoid needing an accessor for the private
     * {@code bits} field.
     */
    private static int bitsFromStorage(long[] raw) {
        return raw.length / 64;
    }

    /**
     * Captures both mutable fields from one {@link BlockStateContainer} generation. Ingest callers
     * invoke this on the client thread before handing work to Voxy workers.
     */
    public static BlockStateSnapshot snapshot(BlockStateContainer blockContainer) {
        var accessor = (AccessorBlockStateContainer) blockContainer;

        net.minecraft.util.BitArray storage;
        IBlockStatePalette palette;
        long[] raw;
        do {
            storage = accessor.voxy$getStorage();
            palette = accessor.voxy$getPalette();
            raw = storage.getBackingLongArray().clone();
        } while (storage != accessor.voxy$getStorage() || palette != accessor.voxy$getPalette());

        if (palette instanceof BlockStatePaletteRegistry) {
            return new BlockStateSnapshot(raw, null);
        }

        int bits = bitsFromStorage(raw);
        IBlockState[] paletteSnapshot = new IBlockState[1 << bits];
        for (int i = 0; i < paletteSnapshot.length; i++) {
            try {
                paletteSnapshot[i] = palette.getBlockState(i);
            } catch (Exception ignored) {
                // Unmapped palette slots remain null and convert to air.
            }
        }
        return new BlockStateSnapshot(raw, paletteSnapshot);
    }

    private static int resolveBlockId(IBlockState state, Reference2IntOpenHashMap<IBlockState> blockCache, Mapper mapper) {
        if (state == null) {
            return -1;
        }
        int blockId = blockCache.getOrDefault(state, -1);
        if (blockId == -1) {
            blockId = mapper.getIdForBlockState(state);
            blockCache.put(state, blockId);
        }
        return blockId;
    }

    /**
     * Builds the palette-index to Voxy-block-id lookup for a section-local palette. 1.12.2 palettes
     * expose no size, but every index storable in {@code bits} bits is addressable, and
     * {@code getBlockState} returns null for unused slots, so the full range is safe to walk.
     */
    private static int setupLocalPalette(IBlockStatePalette palette, Reference2IntOpenHashMap<IBlockState> blockCache, Mapper mapper, int[] pc, int bits) {
        int count = 1 << bits;
        for (int i = 0; i < count; i++) {
            IBlockState state = null;
            try {
                state = palette.getBlockState(i);
            } catch (Exception e) {
                // Matches upstream's tolerance of a palette that throws on an unmapped index.
            }
            pc[i] = resolveBlockId(state, blockCache, mapper);
        }
        return count;
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           BlockStateContainer blockContainer,
                                           IBiomeSupplier biomeSupplier,
                                           ILightingSupplier lightSupplier) {
        return convert(section, stateMapper, snapshot(blockContainer), biomeSupplier, lightSupplier, false, 0);
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           BlockStateSnapshot blockStates,
                                           IBiomeSupplier biomeSupplier,
                                           ILightingSupplier lightSupplier) {
        return convert(section, stateMapper, blockStates, biomeSupplier, lightSupplier, false, 0);
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           BlockStateContainer blockContainer,
                                           IBiomeSupplier biomeSupplier,
                                           ILightingSupplier lightSupplier,
                                           boolean shouldZoom,
                                           long zoomSeed) {
        return convert(section, stateMapper, snapshot(blockContainer), biomeSupplier, lightSupplier, shouldZoom, zoomSeed);
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           BlockStateSnapshot blockStates,
                                           IBiomeSupplier biomeSupplier,
                                           ILightingSupplier lightSupplier,
                                           boolean shouldZoom,
                                           long zoomSeed) {
        //Cheat by creating a local pallet then read the data directly
        var cache = THREAD_LOCAL.get();
        var blockCache = cache.getLocalMapping(stateMapper);

        var biomes = cache.biomeCache;
        var data = section.section;
        var zoomCells = cache.zoomCellCache;

        long[] raw = blockStates.storage();
        int bits = bitsFromStorage(raw);
        long mask = (1L << bits) - 1L;

        // A null snapshot palette marks 1.12.2's global registry palette. Local palette entries were
        // copied together with the packed storage on the producer thread.
        boolean globalPalette = blockStates.palette() == null;
        int[] pc = null;
        if (!globalPalette) {
            IBlockState[] palette = blockStates.palette();
            pc = cache.getPaletteCache(palette.length);
            for (int i = 0; i < palette.length; i++) {
                pc[i] = resolveBlockId(palette[i], blockCache, stateMapper);
            }
        }

        {
            int i = 0;
            int inital = -1;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        // Sample the middle of each 4-wide horizontal cell. Y is ignored because a
                        // 1.12.2 biome is constant down the column.
                        int bid = stateMapper.getIdForBiome(biomeSupplier.supply(x*4 + 1, z*4 + 1));
                        biomes[i++] = bid;
                        if (inital==-1) inital = bid;
                        shouldZoom &= inital == bid;//Evil hacky trick, we only need to zoom if on a biome boarder
                    }
                }
            }

            if (shouldZoom) {
                computeZoomCells(biomes, zoomSeed, zoomCells);
            }
        }

        int nonZeroCnt = 0;
        for (int i = 0; i <= 0xFFF; i++) {
            // Absolute-bit-offset read, so an entry spanning two longs is stitched correctly.
            int bitPos = i * bits;
            int wordIdx = bitPos >>> 6;
            int bitOff = bitPos & 63;
            long sample = raw[wordIdx] >>> bitOff;
            if (bitOff + bits > 64) {
                sample |= raw[wordIdx + 1] << (64 - bitOff);
            }
            int paletteIdx = (int) (sample & mask);

            int bId;
            if (globalPalette) {
                bId = stateMapper.getIdForBlockState(Block.getStateById(paletteIdx));
            } else {
                bId = pc[paletteIdx];
            }
            if (bId < 0) {
                bId = 0;//Unmapped palette slot, treat as air like upstream does for a null state
            }

            byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
            nonZeroCnt += (bId != 0)?1:0;
            data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
        }

        section.lvl0NonAirCount = nonZeroCnt;
        return section;
    }


    private static void computeZoomCells(int[] biomes, long zoomSeed, long[] zoomInfo) {
        for (int cy = 0; cy<4; cy++) {
            for (int cz = 0; cz<4; cz++) {
                for (int cx = 0; cx<4; cx++) {

                }
            }
        }
    }

    //Support for other mods etc that use this entry point
    @Deprecated
    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        WorldVoxilizedSectionMipper.mipSection(section, mapper);
    }
}
