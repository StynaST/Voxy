package me.cortex.voxy.client.core.model;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;

/**
 * A one-block fake world: every position holds the same state and the same biome.
 *
 * <p>Port note: upstream builds anonymous {@code BlockAndTintGetter}s inline wherever it needs one.
 * 1.12.2's equivalent, {@link IBlockAccess}, is used by three separate call sites here (fluid
 * tesselation, constant-colour capture, biome-dependence probing) and has more methods to stub, so it
 * is one reusable class instead.
 *
 * <p>{@link #biomeAccessed} is how biome-dependent tinting is detected: 1.12.2 colour providers ask
 * the world for the biome rather than being handed a resolver, so a provider that never calls
 * {@link #getBiome} produces a constant colour.
 */
public class BakeBlockAccess implements IBlockAccess {
    private IBlockState state;
    private Biome biome;
    /** Face being baked, or -1. Positions on the outward side of that face read as air. */
    private int airFace = -1;
    public boolean biomeAccessed;

    public BakeBlockAccess set(IBlockState state, Biome biome) {
        this.state = state;
        this.biome = biome;
        return this;
    }

    public BakeBlockAccess setAirFace(int face) {
        this.airFace = face;
        return this;
    }

    public BakeBlockAccess resetBiomeAccessed() {
        this.biomeAccessed = false;
        return this;
    }

    private boolean isAirAt(BlockPos pos) {
        if (this.airFace == -1) {
            return false;
        }
        var vec = EnumFacing.byIndex(this.airFace).getDirectionVec();
        return vec.getX()*pos.getX() + vec.getY()*pos.getY() + vec.getZ()*pos.getZ() >= 1;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return this.isAirAt(pos) ? Blocks.AIR.getDefaultState() : this.state;
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return this.getBlockState(pos).getMaterial() == Material.AIR;
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        this.biomeAccessed = true;
        return this.biome;
    }

    @Nullable
    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        //Lighting is irrelevant: the rasterizer samples the atlas and ignores vertex colour entirely
        return 0;
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return 0;
    }

    @Override
    public WorldType getWorldType() {
        return WorldType.DEFAULT;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        return _default;
    }
}
