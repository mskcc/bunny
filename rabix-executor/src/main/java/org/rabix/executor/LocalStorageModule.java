package org.rabix.executor;

import com.google.inject.AbstractModule;
import org.apache.commons.configuration.Configuration;
import org.rabix.bindings.mapper.FilePathMapper;
import org.rabix.common.config.ConfigModule;
import org.rabix.executor.config.StorageConfiguration;
import org.rabix.executor.config.impl.DefaultStorageConfiguration;
import org.rabix.executor.config.impl.LocalStorageConfiguration;
import org.rabix.executor.pathmapper.InputFileMapper;
import org.rabix.executor.pathmapper.OutputFileMapper;
import org.rabix.executor.pathmapper.local.LocalPathMapper;

import java.util.Arrays;

public class LocalStorageModule extends AbstractModule {

  private ConfigModule configModule;
  
  public LocalStorageModule(ConfigModule configModule) {
    this.configModule = configModule;
  }
  
  @Override
  protected void configure() {
    Configuration configuration = configModule.provideConfig();

    if(isLSF(configuration)) {
      bind(StorageConfiguration.class).to(DefaultStorageConfiguration.class);
    }
    else {
      String rootDir = configuration.getString("backend.execution.directory.name");
      bind(StorageConfiguration.class).toInstance(new LocalStorageConfiguration(configModule.provideConfig(), rootDir));
    }

    bind(FilePathMapper.class).annotatedWith(InputFileMapper.class).to(LocalPathMapper.class);
    bind(FilePathMapper.class).annotatedWith(OutputFileMapper.class).to(LocalPathMapper.class);
  }

  private boolean isLSF(Configuration configuration) {
    String[] backendTypes = configuration.getStringArray("backend.embedded.types");
    return Arrays.stream(backendTypes)
            .anyMatch(b -> b.trim().equalsIgnoreCase("LSF"));
  }

}
