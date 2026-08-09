package me.cortex.voxy.common.config;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.util.Collections;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Serialization {
    public static final Set<Class<?>> CONFIG_TYPES = new HashSet<>();
    public static Gson GSON;

    private static final class GsonConfigSerialization <T> implements TypeAdapterFactory {
        private final String typeField = "TYPE";
        private final Class<T> clz;

        private final Map<String, Class<? extends T>> name2type = new HashMap<>();
        private final Map<Class<? extends T>, String> type2name = new HashMap<>();

        private GsonConfigSerialization(Class<T> clz) {
            this.clz = clz;
        }

        public GsonConfigSerialization<T> register(String typeName, Class<? extends T> cls) {
            if (this.name2type.put(typeName, cls) != null) {
                throw new IllegalStateException("Type name already registered: " + typeName);
            }
            if (this.type2name.put(cls, typeName) != null) {
                throw new IllegalStateException("Class already registered with type name: " + typeName + ", " + cls);
            }
            return this;
        }


        private T deserialize(Gson gson, JsonElement json) {
            var retype = this.name2type.get(json.getAsJsonObject().remove(this.typeField).getAsString());
            return gson.getDelegateAdapter(this, TypeToken.get(retype)).fromJsonTree(json);
        }

        private JsonElement serialize(Gson gson, T value) {
            String name = this.type2name.get(value.getClass());
            if (name == null) {
                name = "UNKNOWN_TYPE_{" + value.getClass().getName() + "}";
            }

            var vjson = gson
                    .getDelegateAdapter(this, TypeToken.get((Class<T>) value.getClass()))
                    .toJsonTree(value);
            //All of this is so that the config_type is at the top :blob_face:
            var json = new JsonObject();
            json.addProperty(this.typeField, name);
            vjson.getAsJsonObject().asMap().forEach(json::add);
            return json;
        }


        @Override
        public <X> TypeAdapter<X> create(Gson gson, TypeToken<X> type) {
            if (this.clz.isAssignableFrom(type.getRawType())) {
                var jsonObjectAdapter = gson.getAdapter(JsonElement.class);

                return (TypeAdapter<X>) new TypeAdapter<T>() {
                    @Override
                    public void write(JsonWriter out, T value) throws IOException {
                        jsonObjectAdapter.write(out, GsonConfigSerialization.this.serialize(gson, value));
                    }

                    @Override
                    public T read(JsonReader in) throws IOException {
                        var obj = jsonObjectAdapter.read(in);
                        return GsonConfigSerialization.this.deserialize(gson, obj);
                    }
                };
            }
            return null;
        }
    }

    /**
     * The three abstract config bases register themselves into {@link #CONFIG_TYPES} from their
     * static initializers, and the scan in {@link #init()} matches candidates against that set.
     *
     * <p>Upstream gets them populated for free: it loads every scanned class with an initializing
     * {@code Class.forName}, so reaching any concrete subclass runs its superclass's {@code <clinit>}
     * on the way. This port cannot do that (see the {@code initialize = false} note in the scan), so
     * without this nothing ever triggers them, {@code CONFIG_TYPES} stays empty, and every candidate
     * fails the {@code contains} check. The result is silent: zero adapters register, the storage
     * config is then written with no {@code TYPE} discriminators, and that file cannot be read back.
     */
    private static void initialiseConfigTypeRoots() {
        for (var root : new String[]{
                "me.cortex.voxy.common.config.storage.StorageConfig",
                "me.cortex.voxy.common.config.section.SectionStorageConfig",
                "me.cortex.voxy.common.config.compressors.CompressorConfig"}) {
            try {
                Class.forName(root, true, Serialization.class.getClassLoader());
            } catch (Throwable e) {
                Logger.error("Failed to initialize config type root " + root, e);
            }
        }
    }

    public static void init() {
        String BASE_SEARCH_PACKAGE = "me.cortex.voxy";

        initialiseConfigTypeRoots();

        Map<Class<?>, GsonConfigSerialization<?>> serializers = new HashMap<>();

        Set<String> clazzs = new LinkedHashSet<>();
        var path = getModRootPath();
        if (path != null) {
            clazzs.addAll(collectAllClasses(path, BASE_SEARCH_PACKAGE));
        }
        clazzs.addAll(collectAllClasses(BASE_SEARCH_PACKAGE));
        if (clazzs.isEmpty()) {
            //Both discovery routes came up empty, which is not survivable but looks identical to
            //"no config types exist" in the log below. Say so explicitly.
            Logger.error("Config class scan found no classes at all (mod root: " + path + ")");
        }
        int count = 0;
        outer:
        for (var clzName : clazzs) {
            if (VoxyCommon.IS_DEDICATED_SERVER&&clzName.startsWith("me.cortex.voxy.client")) {
                continue;//Dont load stuff from client path when were on a dedicated server
            }
            if (!clzName.toLowerCase(Locale.ROOT).contains("config")) {
                continue;//Only load classes that contain the word config
            }
            if (clzName.contains("mixin")) {
                continue;//Dont want to load mixins
            }
            if (clzName.contains("ModMenuIntegration")) {
                continue;//Dont want to modmenu incase it doesnt exist
            }
            if (clzName.contains("VoxyConfigScreenPages")) {
                continue;//Dont want to modmenu incase it doesnt exist
            }
            if (clzName.endsWith("VoxyConfig")) {
                continue;//Special case to prevent recursive loading pain
            }

            if (clzName.equals(Serialization.class.getName())) {
                continue;//Dont want to load ourselves
            }

            if (clzName.startsWith("me.cortex.voxy.shadow.")) {
                continue;//Shaded third party libraries, never config types
            }

            try {
                //initialize = false: this walks every class in the jar, and running static
                //initializers just to read a superclass is both wasteful and dangerous. Jedis'
                //MultiClusterClientConfig, for one, needs resilience4j at clinit -- an optional
                //dependency we do not ship -- so initializing it throws NoClassDefFoundError.
                var clz = Class.forName(clzName, false, Serialization.class.getClassLoader());
                if (Modifier.isAbstract(clz.getModifiers())) {
                    //Dont want to register abstract classes
                    continue;
                }
                var original = clz;
                while ((clz = clz.getSuperclass()) != null) {
                    if (CONFIG_TYPES.contains(clz)) {
                        Method nameMethod = null;
                        try {
                            nameMethod = original.getMethod("getConfigTypeName");
                            nameMethod.setAccessible(true);
                        } catch (NoSuchMethodException e) {}
                        if (nameMethod == null) {
                            Logger.error("WARNING: Config class " + clzName + " doesnt contain a getConfigTypeName and thus wont be serializable");
                            continue outer;
                        }
                        count++;
                        String name = (String) nameMethod.invoke(null);
                        serializers.computeIfAbsent(clz, GsonConfigSerialization::new)
                                .register(name, (Class) original);
                        Logger.info("Registered " + original.getSimpleName() + " as " + name + " for config type " + clz.getSimpleName());
                        break;
                    }
                }
            } catch (Throwable e) {
                Logger.error("Error while setting up config serialization", e);
            }
        }

        var builder = new GsonBuilder()
                .setPrettyPrinting();
        for (var entry : serializers.entrySet()) {
            builder.registerTypeAdapterFactory(entry.getValue());
        }

        GSON = builder.create();
        Logger.info("Registered " + count + " config types (scanned " + clazzs.size() + " classes)");
    }

    /**
     * Port note: upstream asks FabricLoader for Voxy's mod container and walks its root path. Forge
     * has no equivalent, so this resolves our own code source instead, which works for both a
     * built jar and an exploded classes directory in the dev environment.
     *
     * <p>Returns null rather than throwing: the scan below is a best-effort supplement to the
     * classloader-resource walk in {@link #collectAllClasses(String)}, and Voxy still starts
     * without it.
     */
    private static Path getModRootPath() {
        try {
            var codeSource = Serialization.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return null;
            }
            var location = codeSource.getLocation();
            var locationUri = location.toURI();

            // Cleanroom hands out a "jar:file:/...!/" code source, not a plain file: one ending in
            // .jar, so both forms have to be handled. Getting this wrong is quiet and expensive:
            // the walk finds nothing, zero config types register, and the storage config is then
            // written without its TYPE discriminators -- a file that cannot be read back.
            String jarUri = null;
            if ("jar".equals(locationUri.getScheme())) {
                jarUri = locationUri.toString();
            } else if (location.getPath().endsWith(".jar")) {
                jarUri = "jar:" + locationUri;
            }

            if (jarUri != null) {
                // A jar has to be mounted as a filesystem before it can be walked with Files.list.
                var uri = URI.create(jarUri);
                FileSystem fs;
                try {
                    fs = FileSystems.newFileSystem(uri, Collections.<String, Object>emptyMap());
                } catch (FileSystemAlreadyExistsException e) {
                    fs = FileSystems.getFileSystem(uri);
                }
                return fs.getPath("/");
            }
            return Paths.get(locationUri);
        } catch (Exception e) {
            Logger.warn("Unable to resolve voxy's own code source, config classes will only be discovered via the classloader", e);
            return null;
        }
    }

    private static List<String> collectAllClasses(String pack) {
        try {
            InputStream stream = Serialization.class.getClassLoader()
                    .getResourceAsStream(pack.replaceAll("[.]", "/"));
            if (stream == null) {
                return List.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            return reader.lines().flatMap(inner -> {
                if (inner.endsWith(".class")) {
                    return Stream.of(pack + "." + inner.replace(".class", ""));
                } else if (!inner.contains(".")) {
                    return collectAllClasses(pack + "." + inner).stream();
                } else {
                    return Stream.of();
                }
            }).collect(Collectors.toList());
        } catch (Exception e) {
            Logger.error("Failed to collect classes in package: " + pack, e);
            return List.of();
        }
    }
    private static List<String> collectAllClasses(Path base, String pack) {
        if (!Files.exists(base.resolve(pack.replaceAll("[.]", "/")))) {
            return List.of();
        }
        try {
            return Files.list(base.resolve(pack.replaceAll("[.]", "/"))).flatMap(inner -> {
                if (inner.getFileName().toString().endsWith(".class")) {
                    return Stream.of(pack + "." + inner.getFileName().toString().replace(".class", ""));
                } else if (Files.isDirectory(inner)) {
                    return collectAllClasses(base, pack + "." + inner.getFileName()).stream();
                } else {
                    return Stream.of();
                }
            }).collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
