package me.cortex.voxy.client.core.rendering.section.backend;


import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.multiplayer.WorldClient;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

//Takes in mesh ids from the hierachical traversal and may perform more culling then renders it
public abstract class AbstractSectionRenderer <T extends Viewport<T>, J extends IGeometryData> {
    public interface FactoryConstructor<VIEWPORT extends Viewport<VIEWPORT>, GEODATA extends IGeometryData> {
        AbstractSectionRenderer<VIEWPORT, GEODATA> create(AbstractRenderPipeline pipeline, ModelStore modelStore, GEODATA geometryData);
    }

    public record Factory<VIEWPORT extends Viewport<VIEWPORT>, GEODATA extends IGeometryData>(Class<? extends AbstractSectionRenderer<VIEWPORT, GEODATA>> clz, FactoryConstructor<VIEWPORT, GEODATA> constructor) {
        @SuppressWarnings("unchecked")//The geometry data is created for this exact backend
        public AbstractSectionRenderer<VIEWPORT, GEODATA> create(AbstractRenderPipeline pipeline, ModelStore store, IGeometryData geometryData) {
            return this.constructor.create(pipeline, store, (GEODATA) geometryData);
        }

        @SuppressWarnings("unchecked")//clz is the renderer class, so its constructor yields that renderer
        public static <VIEWPORT2 extends Viewport<VIEWPORT2>, GEODATA2 extends IGeometryData> Factory<VIEWPORT2, GEODATA2> create(Class<? extends AbstractSectionRenderer<VIEWPORT2, GEODATA2>> clz) {
            var constructors = clz.getConstructors();
            if (constructors.length != 1) {
                Logger.error("Render backend " + clz.getCanonicalName() + " had more then 1 constructor");
                return null;
            }
            var constructor = constructors[0];
            var params = constructor.getParameterTypes();
            if (params.length != 3 || params[0] != AbstractRenderPipeline.class || params[1] != ModelStore.class || !IGeometryData.class.isAssignableFrom(params[2])) {
                Logger.error("Render backend " + clz.getCanonicalName() + " had invalid constructor");
                return null;
            }
            return new Factory<>(clz, (a,b,c)-> {
                try {
                    return (AbstractSectionRenderer<VIEWPORT2, GEODATA2>) constructor.newInstance(a,b,c);
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }


    protected final J geometryManager;
    protected final ModelStore modelStore;
    protected final RenderProperties properties;
    protected AbstractSectionRenderer(RenderProperties properties, ModelStore modelStore, J geometryManager) {
        this.properties = properties;
        this.geometryManager = geometryManager;
        this.modelStore = modelStore;
    }

    public abstract void renderOpaque(T viewport);
    public abstract void buildDrawCalls(T viewport);
    public abstract void renderTemporal(T viewport);
    public void postOpaquePreperation(T viewport){}//can be used for next frame culling
    public abstract void renderTranslucent(T viewport);
    public abstract T createViewport();
    public abstract void free();

    public J getGeometryManager() {
        return this.geometryManager;
    }

    public void addDebug(List<String> lines) {}

    //Port note: 1.12.2 has no CardinalLighting -- the per-face shade multipliers are constants baked
    // into the vanilla block renderer (see BlockModelRenderer / BlockFluidRenderer), so they are
    // reproduced here. The world parameter is kept because a shader-mod pipeline would need it.
    private static final float FACE_TINT_UP = 1.0f;
    private static final float FACE_TINT_DOWN = 0.5f;
    private static final float FACE_TINT_Z_AXIS = 0.8f;
    private static final float FACE_TINT_X_AXIS = 0.6f;

    protected static void addDirectionalFaceTint(Shader.Builder<?> builder, WorldClient cl) {
        builder.define("NO_SHADE_FACE_TINT", FACE_TINT_UP);
        builder.define("UP_FACE_TINT", FACE_TINT_UP);
        builder.define("DOWN_FACE_TINT", FACE_TINT_DOWN);
        builder.define("Z_AXIS_FACE_TINT", FACE_TINT_Z_AXIS);//assumed here that EnumFacing.SOUTH returns the same value
        builder.define("X_AXIS_FACE_TINT", FACE_TINT_X_AXIS);//assumed here that EnumFacing.WEST returns the same value
        /*
        //TODO: generate the tinting table here and use the replacement feature
        float[] tints = new float[7];
        tints[6] = cl.getShade(Direction.UP, false);
        for (Direction direction : Direction.values()) {
            tints[direction.get3DDataValue()] = cl.getShade(direction, true);
        }*/
    }

    protected static Shader tryCompilePatchedOrNormal(Shader.Builder<?> builder, String shader, String original) {
        boolean patched = shader != original;//This is the correct comparison type (reference)
        try {
            return builder.clone()
                    .defineIf("PATCHED_SHADER", patched)
                    .addSource(ShaderType.FRAGMENT, shader)
                    .compile();
        } catch (RuntimeException e) {
            if (patched) {
                Logger.error("Failed to compile shader patch, using normal pipeline to prevent errors", e);
                return tryCompilePatchedOrNormal(builder, original, original);
            } else {
                throw e;
            }
        }
    }
}
