package org.rabix.cli.status;

import org.apache.commons.configuration.Configuration;
import org.rabix.engine.service.BackendService;
import org.rabix.engine.service.JobService;
import org.rabix.engine.status.impl.LoggingEngineStatusCallback;

import javax.inject.Inject;

public class LocalBackendEngineStatusCallback extends LoggingEngineStatusCallback {

    @Inject
    public LocalBackendEngineStatusCallback(BackendService backendService, JobService jobService, Configuration
            configuration) {
        super(backendService, jobService, configuration);
    }
}
