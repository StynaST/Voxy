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
    private TileEntity tileEntity;
    private boolean isolated;

    public BakeBlockAccess set(IBlockState state, Biome biome) {
        this.state = state;
        this.biome = biome;
        return this;
    }

    /** Tile entity handed to getExtendedState, or null. Only the origin position carries one. */
    public BakeBlockAccess setTileEntity(@Nullable TileEntity tileEntity) {
        this.tileEntity = tileEntity;
        return this;
    }

    /** Only the origin holds the state, neighbours read as air. Stops mod code that walks
     * neighbours recursing forever (NTM's trapdoor isLadder). Fluid/tint paths want the uniform default. */
    public BakeBlockAccess setIsolated(boolean isolated) {
        this.isolated = isolated;
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
        //Bounded on purpose: self-culling only ever asks about direct neighbours, while mod code that
        //walks the world (NTM's trapdoor recursing down through isLadder) never terminates if every
        //position holds the same block. Isolated mode tightens that to the origin alone.
        int reach = this.isolated ? 0 : 1;
        if (Math.abs(pos.getX()) > reach || Math.abs(pos.getY()) > reach || Math.abs(pos.getZ()) > reach) {
            return Blocks.AIR.getDefaultState();
        }
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
        return pos.getX() == 0 && pos.getY() == 0 && pos.getZ() == 0 ? this.tileEntity : null;
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
