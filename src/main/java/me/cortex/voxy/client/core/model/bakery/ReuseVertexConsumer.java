package me.cortex.voxy.client.core.model.bakery;


import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vertex sink for the software rasterizer: 24 bytes per vertex — position (3 floats), metadata (int),
 * uv (2 floats) — four vertices per quad.
 *
 * <p>Port note: upstream implements {@code com.mojang.blaze3d.vertex.VertexConsumer} so the modern
 * model pipeline can push into it. 1.12.2 has no such interface, and a {@code BakedQuad} there is a
 * flat {@code int[]} laid out by a {@link VertexFormat}, so this reads that array directly. The
 * buffer layout the rasterizer consumes is unchanged.
 *
 * <p>Forge lets models declare their own vertex format, so position and uv offsets are resolved from
 * the quad's format rather than assuming {@code DefaultVertexFormats.BLOCK}.
 */
public final class ReuseVertexConsumer {
    public static final int VERTEX_FORMAT_SIZE = 24;
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;

    public boolean anyShaded;
    public boolean anyDarkendTex;
    public boolean anyDiscard;

    private final int globalOrMetadata;
    public ReuseVertexConsumer() {
        this(0);
    }
    public ReuseVertexConsumer(int globalOrMetadata) {
        this.reset();
        this.globalOrMetadata = globalOrMetadata;
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    public int getDefaultMeta() {
        return this.defaultMeta;
    }

    public ReuseVertexConsumer addVertex(float x, float y, float z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta|this.globalOrMetadata);
        MemoryUtil.memPutFloat(this.ptr, x);
        MemoryUtil.memPutFloat(this.ptr + 4, y);
        MemoryUtil.memPutFloat(this.ptr + 8, z);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        this.anyDiscard |= (metadata&1)!=0;
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    public ReuseVertexConsumer setUv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    /** Layout of a vertex within a {@link VertexFormat}, in ints. */
    private record FormatLayout(int stride, int positionOffset, int uvOffset) {}

    private static final Map<VertexFormat, FormatLayout> FORMAT_LAYOUTS = new ConcurrentHashMap<>();

    private static FormatLayout layoutOf(VertexFormat format) {
        return FORMAT_LAYOUTS.computeIfAbsent(format, fmt->{
            int position = -1;
            int uv = -1;
            for (int i = 0; i < fmt.getElementCount(); i++) {
                var element = fmt.getElement(i);
                if (element.getUsage() == VertexFormatElement.EnumUsage.POSITION) {
                    position = fmt.getOffset(i) / 4;
                } else if (element.getUsage() == VertexFormatElement.EnumUsage.UV && element.getIndex() == 0) {
                    //Index 0 is the block atlas uv, index 1 is the lightmap
                    uv = fmt.getOffset(i) / 4;
                }
            }
            if (position == -1 || uv == -1) {
                throw new IllegalStateException("Vertex format has no position or texture uv: " + fmt);
            }
            return new FormatLayout(fmt.getIntegerSize(), position, uv);
        });
    }

    public ReuseVertexConsumer quad(BakedQuad quad) {
        return this.quad(quad, false);
    }

    /**
     * @param forceSolid treat the quad as belonging to the solid layer, i.e. never alpha discarded
     * @param layerNeedsDiscard whether the block's render layer is a cutout layer
     */
    public ReuseVertexConsumer quad(BakedQuad quad, boolean forceSolid, boolean layerNeedsDiscard) {
        int meta = 0;
        meta |= (forceSolid || !layerNeedsDiscard)?0:1;//has discard
        meta |= quad.hasTintIndex()?4:0;//has tinting
        return this.quad(quad, meta);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, boolean forceSolid) {
        return this.quad(quad, forceSolid, !forceSolid);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.shouldApplyDiffuseLighting();
        //Upstream also sets anyDarkendTex from the sprite's DARK_CUTOUT mipmap strategy, which 1.12.2
        // has no concept of -- there is one mipmap path for the whole atlas.
        this.ensureCanPut();

        var layout = layoutOf(quad.getFormat());
        int[] data = quad.getVertexData();
        for (int i = 0; i < 4; i++) {
            int base = layout.stride * i;
            this.addVertex(Float.intBitsToFloat(data[base + layout.positionOffset]),
                           Float.intBitsToFloat(data[base + layout.positionOffset + 1]),
                           Float.intBitsToFloat(data[base + layout.positionOffset + 2]));
            this.setUv(Float.intBitsToFloat(data[base + layout.uvOffset]),
                       Float.intBitsToFloat(data[base + layout.uvOffset + 1]));

            this.meta(metadata|this.globalOrMetadata);
        }
        return this;
    }

    /** Appends a quad already decoded into world-space positions and atlas uvs. */
    public ReuseVertexConsumer quad(float[] positions, float[] uvs, int metadata) {
        this.ensureCanPut();
        for (int i = 0; i < 4; i++) {
            this.addVertex(positions[i*3], positions[i*3+1], positions[i*3+2]);
            this.setUv(uvs[i*2], uvs[i*2+1]);
            this.meta(metadata|this.globalOrMetadata);
        }
        return this;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr-this.buffer.address;
        //1.5x the size
        var newBuffer = new MemoryBuffer((((int)(this.buffer.size*2)+VERTEX_FORMAT_SIZE-1)/VERTEX_FORMAT_SIZE)*VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.anyDiscard = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;//the thing is first time this gets incremented by FORMAT_STRIDE
        return this;
    }

    public void free() {
        this.ptr = 0;
        this.count = 0;
        this.buffer.free();
        this.buffer = null;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public int quadCount() {
        if (this.count%4 != 0) throw new IllegalStateException();
        return this.count/4;
    }

    public long getAddress() {
        return this.buffer.address;
    }
}
