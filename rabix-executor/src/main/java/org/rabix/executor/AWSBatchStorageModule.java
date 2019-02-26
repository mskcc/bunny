package org.rabix.executor;

import com.google.inject.AbstractModule;
import org.apache.commons.configuration.Configuration;
import org.rabix.common.config.ConfigModule;
import org.rabix.executor.config.StorageConfiguration;
import org.rabix.executor.config.impl.LocalStorageConfiguration;


public class AWSBatchStorageModule extends AbstractModule {

    private ConfigModule configModule;

    public AWSBatchStorageModule(ConfigModule configModule) {
        this.configModule = configModule;
    }

    @Override
    protected void configure() {
        Configuration configuration = configModule.provideConfig();
        String rootDir = configuration.getString("backend.execution.directory.name");
        bind(StorageConfiguration.class).toInstance(new LocalStorageConfiguration(configModule.provideConfig(), rootDir));
    }
}
