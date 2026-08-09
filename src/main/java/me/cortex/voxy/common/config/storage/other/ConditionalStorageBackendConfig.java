package me.cortex.voxy.common.config.storage.other;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import org.apache.commons.lang3.NotImplementedException;

//A conditional storage backend depending on build time config, this enables conditional backends depending on the
// dimension as an example
public class ConditionalStorageBackendConfig extends StorageConfig {
    @Override
    public StorageBackend build(ConfigBuildCtx ctx) {
        // commons-lang3 3.5 (bundled by 1.12.2 Forge) has no no-arg NotImplementedException ctor.
        throw new NotImplementedException("ConditionalStorageBackendConfig#build");
    }

    public static String getConfigTypeName() {
        return "ConditionalConfig";
    }
}
