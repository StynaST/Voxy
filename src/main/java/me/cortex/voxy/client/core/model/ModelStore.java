package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.util.GlobalCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;

import java.lang.ref.Cleaner;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;

    //The shaders normalise atlas UVs against a fixed 256x256 grid of model tiles, so the atlas is
    //always allocated at its full addressable size. When it is sparse, only the rows holding models
    //that have actually been baked are backed by memory -- a pack using a few thousand of the 65536
    //slots pays for a few thousand, not for all of them.
    private static final int ATLAS_ROWS = 256;
    private static final int INITIAL_ROWS = 16;//4096 models before the first growth

    private Cleaner.Cleanable ref;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    private int committedRows;
    private final int[] committedLevelHeight;

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        var tex = this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
        this.committedLevelHeight = new int[tex.getLevels()];
        this.ref = GlobalCleaner.CLEANER.register(this, ()->RenderResourceReuse.giveBackModelStoreTextureAtlas(tex));

        //Limit the mips of the texture to match that of the terrain atlas
        //TextureMap.mipmapLevels is private, but it is set straight from this game setting, which is
        // the same number without needing an accessor mixin
        int mipLvl = Minecraft.getMinecraft().gameSettings.mipmapLevels;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }


    /**
     * Makes the atlas rows holding {@code modelId} usable. Must be called before any upload writes
     * that model's tile, since writes to uncommitted sparse pages are discarded. A no-op when the
     * atlas is fully resident.
     */
    public void ensureModelIdFits(int modelId) {
        if (!this.textures.isSparse()) {
            return;
        }
        int neededRows = (modelId>>8)+1;
        if (neededRows <= this.committedRows) {
            return;
        }
        //Double rather than commit exactly what is needed, so a pack that loads models steadily does
        //not hit the driver with a commitment call every 256 models
        int newRows = Math.max(this.committedRows == 0 ? INITIAL_ROWS : this.committedRows*2, neededRows);
        this.commitUpToRow(Math.min(newRows, ATLAS_ROWS));
    }

    private void commitUpToRow(int rows) {
        int pageX = this.textures.getPageSizeX();
        int pageY = this.textures.getPageSizeY();
        for (int lvl = 0; lvl < this.textures.getLevels(); lvl++) {
            int levelWidth  = Math.max(this.textures.getWidth()>>lvl, 1);
            int levelHeight = Math.max(this.textures.getHeight()>>lvl, 1);
            int rowHeight   = Math.max((ModelFactory.MODEL_TEXTURE_SIZE*2)>>lvl, 1);

            int want;
            if (levelWidth < pageX || levelHeight < pageY) {
                want = levelHeight;//Level is in the mip tail, it can only be committed whole
            } else {
                want = Math.min(levelHeight, alignUp(rows*rowHeight, pageY));
            }

            int have = this.committedLevelHeight[lvl];
            if (want > have) {
                //Full level width, so the region only has to be page aligned vertically
                this.textures.commitPages(lvl, 0, have, levelWidth, want-have, true);
                this.committedLevelHeight[lvl] = want;
            }
        }
        this.committedRows = rows;
    }

    private void decommitAll() {
        for (int lvl = 0; lvl < this.committedLevelHeight.length; lvl++) {
            int have = this.committedLevelHeight[lvl];
            if (have != 0) {
                this.textures.commitPages(lvl, 0, 0, Math.max(this.textures.getWidth()>>lvl, 1), have, false);
                this.committedLevelHeight[lvl] = 0;
            }
        }
        this.committedRows = 0;
    }

    private static int alignUp(int value, int alignment) {
        return (value+alignment-1)/alignment*alignment;
    }

    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        //The atlas outlives this store (it goes back to the reuse cache), so hand it back with
        //nothing committed rather than leaving a dead world's model rows resident
        if (this.textures.isSparse()) {
            this.decommitAll();
        }
        this.ref.clean();
        glDeleteSamplers(this.blockSampler);
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
