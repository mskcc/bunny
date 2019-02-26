package org.rabix.awsbatch;

import com.google.inject.Scopes;
import org.rabix.awsbatch.mapper.LocalToS3PathMapper;
import org.rabix.awsbatch.mapper.S3ToLocalPathMapper;
import org.rabix.backend.api.BackendModule;
import org.rabix.backend.api.WorkerService;
import org.rabix.bindings.mapper.FilePathMapper;
import org.rabix.common.config.ConfigModule;
import org.rabix.executor.pathmapper.InputFileMapper;
import org.rabix.executor.pathmapper.OutputFileMapper;

public class AWSBatchModule extends BackendModule {

    protected ConfigModule configModule;

    public AWSBatchModule(ConfigModule configModule) {
        super(configModule);
    }

    @Override
    protected void configure() {
        bind(WorkerService.class).annotatedWith(AWSBatchScheduler.AWSBatchWorker.class).to(AWSBatchScheduler.class).in(Scopes.SINGLETON);
        bind(FilePathMapper.class).annotatedWith(InputFileMapper.class).to(S3ToLocalPathMapper.class);
        bind(FilePathMapper.class).annotatedWith(OutputFileMapper.class).to(LocalToS3PathMapper.class);
    }

}

