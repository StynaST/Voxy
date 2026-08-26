package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;

import static org.lwjgl.opengl.ARBSparseTexture.GL_TEXTURE_SPARSE_ARB;
import static org.lwjgl.opengl.ARBSparseTexture.GL_VIRTUAL_PAGE_SIZE_X_ARB;
import static org.lwjgl.opengl.ARBSparseTexture.GL_VIRTUAL_PAGE_SIZE_Y_ARB;
import static org.lwjgl.opengl.ARBSparseTexture.glTexturePageCommitmentEXT;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL42C.glGetInternalformativ;
import static org.lwjgl.opengl.GL45C.*;

public class GlTexture extends TrackedObject {
    public final int id;
    private final int type;
    private int format;
    private int width;
    private int height;
    private int levels;
    private boolean hasAllocated;
    private boolean sparse;
    private int pageSizeX;
    private int pageSizeY;
    private long committedSize;//Sparse only: bytes actually backed by memory

    private static int COUNT;
    private static long ESTIMATED_TOTAL_SIZE;

    public GlTexture() {
        this(GL_TEXTURE_2D);
    }

    public GlTexture(int type) {
        this.id = glCreateTextures(type);
        this.type = type;
        COUNT++;
    }

    private GlTexture(int type, boolean useGenTypes) {
        if (useGenTypes) {
            this.id = glGenTextures();
        } else {
            this.id = glCreateTextures(type);
        }
        this.type = type;
        COUNT++;
    }

    public GlTexture store(int format, int levels, int width, int height) {
        if (this.hasAllocated) {
            throw new IllegalStateException("Texture already allocated");
        }
        this.hasAllocated = true;

        this.format = format;
        if (this.type == GL_TEXTURE_2D) {
            glTextureStorage2D(this.id, levels, format, width, height);
            this.width = width;
            this.height = height;
            this.levels = levels;
        } else {
            throw new IllegalStateException("Unknown texture type");
        }
        ESTIMATED_TOTAL_SIZE += this.getEstimatedSize();
        return this;
    }

    /**
     * Allocates the texture as a sparse texture: the storage is address space only until
     * {@link #commitPages} backs parts of it with memory. Callers must commit every region they
     * intend to read or write; the contents of uncommitted pages are undefined and writes to them
     * are discarded.
     *
     * <p>Only valid when {@link Capabilities#sparseTexture} is set.
     */
    public GlTexture storeSparse(int format, int levels, int width, int height) {
        if (this.hasAllocated) {
            throw new IllegalStateException("Texture already allocated");
        }
        if (!Capabilities.INSTANCE.sparseTexture) {
            throw new IllegalStateException("Sparse textures are not supported here");
        }
        if (this.type != GL_TEXTURE_2D) {
            throw new IllegalStateException("Unknown texture type");
        }

        //GL_TEXTURE_SPARSE_ARB has to be set before the storage is created
        glTextureParameteri(this.id, GL_TEXTURE_SPARSE_ARB, GL_TRUE);

        //Page size is a property of the internal format, not of the level
        this.pageSizeX = getInternalFormatQuery(format, GL_VIRTUAL_PAGE_SIZE_X_ARB);
        this.pageSizeY = getInternalFormatQuery(format, GL_VIRTUAL_PAGE_SIZE_Y_ARB);
        if (this.pageSizeX <= 0 || this.pageSizeY <= 0) {
            throw new IllegalStateException("Driver reported a virtual page size of " + this.pageSizeX + "x" + this.pageSizeY + " for format " + format);
        }

        this.sparse = true;
        this.hasAllocated = true;
        this.format = format;
        this.width = width;
        this.height = height;
        this.levels = levels;
        glTextureStorage2D(this.id, levels, format, width, height);
        return this;
    }

    private static int getInternalFormatQuery(int format, int query) {
        int[] result = new int[1];
        glGetInternalformativ(GL_TEXTURE_2D, format, query, result);
        return result[0];
    }

    public boolean isSparse() {
        return this.sparse;
    }

    public int getPageSizeX() {
        return this.pageSizeX;
    }

    public int getPageSizeY() {
        return this.pageSizeY;
    }

    /**
     * Backs (or releases) a region of a sparse texture level with memory. The region must be page
     * aligned, except where it runs to the edge of the level. Levels smaller than a page live in the
     * mip tail and have to be committed whole.
     */
    public void commitPages(int level, int x, int y, int width, int height, boolean commit) {
        this.assertAllocated();
        if (!this.sparse) {
            throw new IllegalStateException("Texture is not sparse");
        }
        glTexturePageCommitmentEXT(this.id, level, x, y, 0, width, height, 1, commit);

        long delta = (long) width * height * this.getElementSize();
        this.committedSize += commit ? delta : -delta;
        ESTIMATED_TOTAL_SIZE += commit ? delta : -delta;
    }

    public GlTexture createView() {
        return this.createView(0, 1);
    }

    /**
     * A view onto {@code numLevels} mip levels of this texture starting at {@code minLevel}. Only the
     * view's {@code id} is meaningful -- it carries no storage of its own, so the size accounting and
     * the allocation asserts stay with the texture it was made from.
     */
    public GlTexture createView(int minLevel, int numLevels) {
        this.assertAllocated();
        if (minLevel < 0 || numLevels < 1 || minLevel+numLevels > this.levels) {
            throw new IllegalArgumentException("View range " + minLevel + "+" + numLevels + " outside the texture's " + this.levels + " levels");
        }
        var view = new GlTexture(this.type, true);
        glTextureView(view.id, this.type, this.id, this.format, minLevel, numLevels, 0, 1);
        return view;
    }

    @Override
    public void free() {
        if (this.hasAllocated) {
            ESTIMATED_TOTAL_SIZE -= this.getEstimatedSize();
        }
        COUNT--;
        this.hasAllocated = false;
        super.free0();
        glDeleteTextures(this.id);
    }

    public GlTexture name(String name) {
        this.assertAllocated();
        return GlDebug.name(name, this);
    }

    public int getWidth() {
        this.assertAllocated();
        return this.width;
    }

    public int getHeight() {
        this.assertAllocated();
        return this.height;
    }

    public int getLevels() {
        this.assertAllocated();
        return this.levels;
    }

    public int getFormat() {
        this.assertAllocated();
        return this.format;
    }

    public int getPixelTransferFormat() {
        this.assertAllocated();
        return switch (this.format) {
            case GL_RGBA8 -> GL_RGBA;
            case GL_RG16F -> GL_RG;
            case GL_R32UI -> GL_RED_INTEGER;
            case GL_R32F -> GL_RED;
            case GL_DEPTH_COMPONENT24,GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT32 -> GL_DEPTH_COMPONENT;
            case GL_DEPTH24_STENCIL8 -> GL_DEPTH_STENCIL;
            default -> throw new IllegalStateException("Unknown format");
        };
    }

    private long getElementSize() {
        return switch (this.format) {
            case GL_R32UI, GL_RGBA8, GL_DEPTH24_STENCIL8, GL_R32F, GL_RG16F -> 4;
            case GL_DEPTH_COMPONENT24 -> 4;//TODO: check this is right????
            case GL_DEPTH_COMPONENT32F -> 4;
            case GL_DEPTH_COMPONENT32 -> 4;

            default -> throw new IllegalStateException("Unknown element size");
        };
    }

    private long getEstimatedSize() {
        this.assertAllocated();
        //A sparse texture only occupies what has been committed, which commitPages already tracks
        if (this.sparse) {
            return this.committedSize;
        }
        long elemSize = this.getElementSize();

        long size = 0;
        for (int lvl = 0; lvl < this.levels; lvl++) {
            size += Math.max((((long)this.width)>>lvl), 1) * Math.max((((long)this.height)>>lvl), 1) * elemSize;
        }
        return size;
    }

    public void assertAllocated() {
        if (!this.hasAllocated) {
            throw new IllegalStateException("Texture not yet allocated");
        }
    }

    public GlTexture zero() {
        this.assertAllocated();
        int type = switch (this.format) {
            case GL_R32UI -> GL_UNSIGNED_INT;
            case GL_RGBA8 -> GL_INT;
            case GL_R32F,GL_DEPTH_COMPONENT24,GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT32, GL_RG16F -> GL_FLOAT;
            case GL_DEPTH24_STENCIL8 -> GL_UNSIGNED_INT_24_8;
            case GL_DEPTH32F_STENCIL8 -> GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
            default -> throw new IllegalStateException("Unknown format");
        };
        for (int lvl = 0; lvl < this.levels; lvl++) {
            nglClearTexImage(this.id, lvl, this.getPixelTransferFormat(), type, 0);
        }
        return this;
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getEstimatedTotalSize() {
        return ESTIMATED_TOTAL_SIZE;
    }
}
