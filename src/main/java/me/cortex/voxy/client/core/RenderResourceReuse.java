package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.TrackedObject;

import java.util.ArrayList;

import static org.lwjgl.opengl.ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.*;

//System to allow reuse/recycling of render buffer/texture allocations
// specfically the geometry buffer and texture atlas allocation
public class RenderResourceReuse {
    //Escape hatch: sparse buffers upset some capture tools (RenderDoc in particular)
    private static final boolean DISABLE_SPARSE = System.getProperty("voxy.disableSparseGeometry", "false").equals("true");

    private static final ArrayList<GlTexture> MODEL_TEXTURE_CACHE = new ArrayList<>();
    private static final ArrayList<GlBuffer> GEOMETRY_BUFFER_CACHE = new ArrayList<>();

    //Clears and frees any cached resources (used when the entire instance is shutdown)
    public static void clearResources() {
        MODEL_TEXTURE_CACHE.forEach(TrackedObject::free);
        GEOMETRY_BUFFER_CACHE.forEach(TrackedObject::free);
        MODEL_TEXTURE_CACHE.clear();
        GEOMETRY_BUFFER_CACHE.clear();
    }


    public static GlTexture getOrCreateModelStoreTextureAtlas() {
        GlTexture atlas = null;
        if (!MODEL_TEXTURE_CACHE.isEmpty()) {
            atlas = MODEL_TEXTURE_CACHE.removeFirst();
            if (!atlas.isSparse()) {
                atlas.zero();//Zeroing a sparse atlas would touch pages that are not backed
            }
        } else {
            int levels = Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE);
            int width = ModelFactory.MODEL_TEXTURE_SIZE*3*256;
            int height = ModelFactory.MODEL_TEXTURE_SIZE*2*256;

            //The atlas is addressed as a fixed 256x256 grid of models -- the shaders normalise UVs
            //against that grid -- so it cannot simply be allocated smaller. Sparse storage keeps the
            //full addressable size while only the model rows actually baked cost memory, which for a
            //typical pack is a small fraction of the 65536 slots. See ModelStore.ensureModelIdFits.
            if (Capabilities.INSTANCE.sparseTexture && !DISABLE_SPARSE) {
                glGetError();//Clear any errors
                atlas = new GlTexture().storeSparse(GL_RGBA8, levels, width, height);
                int error = glGetError();
                if (error != GL_NO_ERROR) {
                    Logger.warn("Sparse model atlas allocation failed with gl error " + error + ", falling back to a fully resident atlas");
                    atlas.free();
                    atlas = null;
                    glGetError();
                }
            }

            if (atlas == null) {
                atlas = new GlTexture().store(GL_RGBA8, levels, width, height);
            }
            atlas.name("ModelTextures");
        }
        return atlas;
    }
    public static void giveBackModelStoreTextureAtlas(GlTexture texture) {
        MODEL_TEXTURE_CACHE.add(texture);
    }

    static GlBuffer getOrCreateGeometryBuffer() {
        GlBuffer buffer = null;
        if (!GEOMETRY_BUFFER_CACHE.isEmpty()) {
            buffer = GEOMETRY_BUFFER_CACHE.removeFirst();
            //Reuse buffer, todo: probably check the geometry size and try upsize if possible
        } else {
            boolean useSparse = Capabilities.INSTANCE.sparseBuffer && !DISABLE_SPARSE;
            long capacity = getGeometryBufferSize(useSparse);
            long driverMemory = -1;
            if (Capabilities.INSTANCE.canQueryGpuMemory) {
                driverMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
            }

            glGetError();//Clear any errors

            //Sparse is the preferred allocation, not a fallback. The capacity here is a worst case
            //(up to 2GB) but only the pages the geometry arena has actually grown into are ever
            //committed -- see BasicSectionGeometryData.ensureAccessable -- so the buffer costs its
            //working set instead of its capacity. Upstream restricted this to NVIDIA+Windows, which
            //left every other driver holding the full capacity resident for no benefit.
            if (useSparse) {
                buffer = new GlBuffer(capacity, GL_SPARSE_STORAGE_BIT_ARB);
                int sparseError = glGetError();
                if (sparseError != GL_NO_ERROR) {
                    Logger.warn("Sparse geometry buffer allocation failed with gl error " + sparseError + ", falling back to a fully resident buffer");
                    buffer.free();
                    buffer = null;
                    glGetError();//The free is not what we want to report on below
                }
            }

            if (buffer == null) {
                //A resident buffer has to respect the free-VRAM limit that the sparse capacity ignores
                capacity = Math.min(capacity, getGeometryBufferSize(false));
                buffer = new GlBuffer(capacity, false);
                //TODO: FIXME: TEST, see if the issue is that we are trying to zero the entire buffer, try only zeroing increments
                // or dont zero it at all
                int error = glGetError();
                if (error != GL_NO_ERROR) {
                    buffer.free();
                    throw new IllegalStateException("Unable to allocate geometry buffer, got gl error " + error + ". Failed to allocate buffer of size "+capacity);
                }
            }
            String extra = "";
            if (driverMemory != -1) {
                extra = ", driver stated " + (driverMemory/(1024*1024)) + "Mb of free memory";
            }
            Logger.info("Allocated new geometry buffer: " + buffer.size() + ", isSparse: " + buffer.isSparse() + extra);
        }
        return buffer;
    }

    public static void giveBackGeometryBuffer(GlBuffer geometryBuffer) {
        GEOMETRY_BUFFER_CACHE.add(geometryBuffer);
    }

    private static long getGeometryBufferSize(boolean sparse) {
        long geometryCapacity = Math.min((1L<<(64-Long.numberOfLeadingZeros(Capabilities.INSTANCE.ssboMaxSize-1)))<<1, 1L<<32)-1024/*(1L<<32)-1024*/;
        if (Capabilities.INSTANCE.isIntel) {
            geometryCapacity = Math.max(geometryCapacity, 1L<<30);//intel moment, force min 1gb
        }
        if (Capabilities.INSTANCE.isNvidia) {
            //Above GL_MAX_SHADER_STORAGE_BLOCK_SIZE the tail of the buffer is not addressable as an ssbo
            geometryCapacity = Math.min(geometryCapacity, 2000L*1024L*1024L);//nvidia moment, force max 2gb heap
        }

        geometryCapacity = Math.max(512*1024*1024, geometryCapacity);//min of 512 mb

        //Limit to available dedicated memory if possible. A sparse buffer only reserves address space
        //until its pages are committed, so free VRAM does not bound it -- clamping there would cut the
        //usable render distance on small cards for memory the buffer was never going to occupy.
        if (Capabilities.INSTANCE.canQueryGpuMemory && !sparse) {
            //512mb less than avalible,
            long limit = Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - (long)(1.5*1024*1024*1024);//1.5gb vram buffer
            // Give a minimum of 512 mb requirement
            limit = Math.max(512*1024*1024, limit);

            geometryCapacity = Math.min(geometryCapacity, limit);
        }
        //geometryCapacity = 1<<28;
        //geometryCapacity = 1<<30;//1GB test
        var override = System.getProperty("voxy.geometryBufferSizeOverrideMB", "");
        if (!override.isEmpty()) {
            geometryCapacity = Long.parseLong(override)*1024L*1024L;
        }
        return geometryCapacity;
    }
}
