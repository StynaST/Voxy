package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.core.model.BakeBlockAccess;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.init.Biomes;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureLevelParameteri;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

/**
 * Software-rasterizes a block model into the six face textures Voxy stores per model.
 *
 * <p>Port note: the modern baking path pushes model geometry through a {@code VertexConsumer}, which
 * 1.12.2 has no equivalent of. Here quads come straight off {@code IBakedModel#getQuads} and are
 * decoded by {@link ReuseVertexConsumer}. Fluids keep their own path, as they do upstream, because
 * 1.12.2 fluids have no baked model at all -- {@link BlockFluidRenderer} generates their geometry --
 * so they are tesselated into a {@link BufferBuilder} and the raw vertices are read back out.
 */
public class SoftwareModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer(1/*has discard*/);
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);

    private final BlockFluidRenderer fr;
    private final BufferBuilder fluidBuffer = new BufferBuilder(0x20000);
    private final BakeBlockAccess blockAccess = new BakeBlockAccess();
    public SoftwareModelTextureBakery() {
        this.fr = new BlockFluidRenderer(Minecraft.getMinecraft().getBlockColors());
    }

    public void setupTexture() {
        int texId = Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId();

        int targetMipLevel = 0;// Math.min(tex.getMipLevels(), 4)-1;//todo: we want to target the mip layer that has the 16x16 sized textures

        //1.12.2 has no GpuTexture wrapper to ask, so the dimensions come from the driver
        int width = glGetTextureLevelParameteri(texId, targetMipLevel, GL_TEXTURE_WIDTH);
        int height = glGetTextureLevelParameteri(texId, targetMipLevel, GL_TEXTURE_HEIGHT);

        var texture = new int[width * height];

        glFlush();
        glFinish();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        try {
            //Zero uses the texture width. Never leak the atlas width through global pixel-pack state:
            //mods reading small textures afterwards would use the atlas-sized row stride and overflow.
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glGetTextureImage(texId, targetMipLevel, GL_RGBA, GL_UNSIGNED_BYTE, texture);
        } finally {
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
        }
        this.rasterizer.setSamplerTexture(texture, width, height);
    }

    /** 1.12.2 render layers are per block, not per quad, so the whole model lands in one consumer. */
    private ReuseVertexConsumer consumerForLayer(BlockRenderLayer layer) {
        return layer == BlockRenderLayer.TRANSLUCENT ? this.translucentVC : this.opaqueVC;
    }

    private static boolean layerNeedsDiscard(BlockRenderLayer layer) {
        return layer == BlockRenderLayer.CUTOUT || layer == BlockRenderLayer.CUTOUT_MIPPED;
    }

    private void bakeBlockModel(IBlockState state) {
        if (state.getRenderType() == EnumBlockRenderType.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(state);

        var layer = state.getBlock().getRenderLayer();
        var consumer = this.consumerForLayer(layer);
        boolean forceSolid = state.getBlock() instanceof BlockLeaves;
        boolean needsDiscard = layerNeedsDiscard(layer);

        for (EnumFacing direction : new EnumFacing[]{EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST, null}) {
            var quads = model.getQuads(state, direction, 42L);
            for (var quad : quads) {
                consumer.quad(quad, forceSolid, needsDiscard);
            }
        }
    }

    private void bakeFluidState(IBlockState state, int face) {
        //A biome is required, not optional: 1.12.2 water tinting reads getWaterColorMultiplier off it
        var access = this.blockAccess.set(state, Biomes.PLAINS).setAirFace(face);

        //1.12.2 tints fluids inside the fluid renderer via BlockColors, so tinting is detected by
        // asking the same provider rather than upstream's getBlockTint interception
        boolean tinted = Minecraft.getMinecraft().getBlockColors().colorMultiplier(state, access, BlockPos.ORIGIN, 0) != -1;

        var layer = state.getBlock().getRenderLayer();
        var consumer = this.consumerForLayer(layer);
        int meta = (tinted ? 4 : 0) | (layerNeedsDiscard(layer) ? 1 : 0);

        this.fluidBuffer.begin(GL_QUADS, DefaultVertexFormats.BLOCK);
        this.fluidBuffer.setTranslation(0, 0, 0);
        boolean rendered = this.fr.renderFluid(access, state, BlockPos.ORIGIN, this.fluidBuffer);
        this.fluidBuffer.finishDrawing();
        if (rendered) {
            appendBufferQuads(this.fluidBuffer, consumer, meta);
        }
        this.fluidBuffer.reset();

        this.blockAccess.setAirFace(-1);
    }

    private final float[] quadPositions = new float[4*3];
    private final float[] quadUvs = new float[4*2];

    /** Reads the finished vertices back out of a {@link BufferBuilder} and appends them as quads. */
    private void appendBufferQuads(BufferBuilder builder, ReuseVertexConsumer consumer, int metadata) {
        var format = builder.getVertexFormat();
        int stride = format.getSize();
        int positionOffset = offsetOf(format, VertexFormatElement.EnumUsage.POSITION, 0);
        int uvOffset = offsetOf(format, VertexFormatElement.EnumUsage.UV, 0);

        var bytes = builder.getByteBuffer();
        int vertices = builder.getVertexCount();
        //The buffer is reused and its limit is not necessarily the written length, so bound the read
        //by what the vertex count actually covers
        int available = bytes.capacity() / stride;
        if (available < vertices) {
            vertices = available;
        }
        for (int quad = 0; quad + 4 <= vertices; quad += 4) {
            for (int i = 0; i < 4; i++) {
                int base = (quad + i) * stride;
                this.quadPositions[i*3]     = bytes.getFloat(base + positionOffset);
                this.quadPositions[i*3 + 1] = bytes.getFloat(base + positionOffset + 4);
                this.quadPositions[i*3 + 2] = bytes.getFloat(base + positionOffset + 8);
                this.quadUvs[i*2]     = bytes.getFloat(base + uvOffset);
                this.quadUvs[i*2 + 1] = bytes.getFloat(base + uvOffset + 4);
            }
            consumer.quad(this.quadPositions, this.quadUvs, metadata);
        }
    }

    private static int offsetOf(VertexFormat format, VertexFormatElement.EnumUsage usage, int index) {
        for (int i = 0; i < format.getElementCount(); i++) {
            var element = format.getElement(i);
            if (element.getUsage() == usage && element.getIndex() == index) {
                return format.getOffset(i);
            }
        }
        throw new IllegalStateException("Vertex format is missing " + usage + "[" + index + "]: " + format);
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE)*8;
    //The outputBuffer layout is different from the non software rasterized ModelTextureBakery
    // in this version the values are simply appended (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(IBlockState state, long outputBuffer) {
        return renderToOutput(state, outputBuffer, false);
    }

    public int renderToOutput(IBlockState state, long outputBuffer, boolean rasterAsUV) {
        MemoryUtil.memSet(outputBuffer,0,16*16*8*6);


        boolean isBlock = !(state.getBlock() instanceof BlockLiquid);

        //TODO: support block model entities (1.12.2: TileEntitySpecialRenderer, no baked model)

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            this.bakeBlockModel(state);
            isAnyShaded |= this.opaqueVC.anyShaded|this.translucentVC.anyShaded;
            isAnyDarkend |= this.opaqueVC.anyDarkendTex|this.translucentVC.anyDarkendTex;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty()&&this.translucentVC.isEmpty())) {//only render if there... is shit to render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i==1||i==2||i==4);
                    this.rasterizer.clear();
                    this.rasterizer.setUVRaster(rasterAsUV);
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(!rasterAsUV);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
                }
            }
        } else {//Is fluid, slow path :(

            for (int i = 0; i < VIEWS.length; i++) {
                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i);
                if (this.opaqueVC.isEmpty()&&this.translucentVC.isEmpty()) continue;
                isAnyShaded |= this.opaqueVC.anyShaded|this.translucentVC.anyShaded;
                isAnyDarkend |= this.opaqueVC.anyDarkendTex|this.translucentVC.anyDarkendTex;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i==1||i==2||i==4);

                //The projection matrix
                this.rasterizer.clear();
                this.rasterizer.setUVRaster(rasterAsUV);
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(!rasterAsUV);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
            }
        }

        return (isAnyShaded?1:0)|(isAnyDarkend?2:0)|(anyTranslucent?4:0)|(anyDiscard?8:0);
    }




    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//EnumFacing.DOWN
        addView(1, 90,0, 0, 0b100);//EnumFacing.UP

        addView(2, 0,180, 0, 0b001);//EnumFacing.NORTH
        addView(3, 0,0, 0, 0);//EnumFacing.SOUTH

        addView(4, 0,90, 270, 0b100);//EnumFacing.WEST
        addView(5, 0,270, 270, 0);//EnumFacing.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        //Port note: upstream builds this on a blaze3d PoseStack. Every operation there is a
        // right-multiply onto the top matrix, which is exactly what these JOML calls do.
        var mat = new Matrix4f()
                .translate(0.5f, 0.5f, 0.5f)
                .rotate(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation))
                .rotate(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch))
                .rotate(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw))
                .mul(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)))
                .translate(-0.5f, -0.5f, -0.5f);

        mat = new Matrix4f().set(
                        2,0,0,0,
                        0,2,0,0,
                        0,0,-2,0,
                        -1,-1,1,1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
