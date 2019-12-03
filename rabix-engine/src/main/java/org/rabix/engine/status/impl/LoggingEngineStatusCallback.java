package org.rabix.engine.status.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.configuration.Configuration;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.model.Job;
import org.rabix.common.helper.JSONHelper;
import org.rabix.common.logging.VerboseLogger;
import org.rabix.engine.service.BackendService;
import org.rabix.engine.service.JobService;
import org.rabix.engine.status.EngineStatusCallbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LoggingEngineStatusCallback extends DefaultEngineStatusCallback {
    private final Logger logger = LoggerFactory.getLogger(LoggingEngineStatusCallback.class);

    private final JobService jobService;
    private final Configuration configuration;

    @Inject
    public LoggingEngineStatusCallback(BackendService backendService, JobService jobService, Configuration configuration) {
        super(backendService);
        this.jobService = jobService;
        this.configuration = configuration;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onJobRootCompleted(UUID rootId) throws EngineStatusCallbackException {
        super.onJobRootCompleted(rootId);
        Job rootJob = jobService.get(rootId);
        if (rootJob.getStatus().equals(Job.JobStatus.COMPLETED)) {
            try {
                try {
                    Bindings bindings = BindingsFactory.create(rootJob);
                    Map<String, Object> outputs = (Map<String, Object>) bindings.translateToSpecific(rootJob.getOutputs());
                    System.out.println(JSONHelper.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(outputs));
                    System.exit(0);
                } catch (BindingException e) {
                    logger.error("Failed to translate common outputs to native", e);
                    throw new RuntimeException(e);
                }
            } catch (JsonProcessingException e) {
                logger.error("Failed to write outputs to standard out", e);
                System.exit(10);
            }
        } else {
            VerboseLogger.log("Failed to execute a Job");
            System.exit(10);
        }
    }

    @Override
    public void onJobRootFailed(UUID rootId, String message) throws EngineStatusCallbackException {
        System.out.println(message);
        System.exit(1);
    }

    @Override public void onJobReady(Job job) throws EngineStatusCallbackException {
        super.onJobReady(job);
        logComposerInfo(job.getName(), job.getStatus().toString(), null);
    }

    @Override public void onJobCompleted(Job job) throws EngineStatusCallbackException {
        super.onJobCompleted(job);
        logComposerInfo(job.getName(), job.getStatus().toString(), null);
    }

    @Override public void onJobFailed(Job job) throws EngineStatusCallbackException {
        super.onJobFailed(job);
        logComposerInfo(job.getName(), job.getStatus().toString(), job.getMessage());
    }

    private void logComposerInfo(String stepId, String status, String message) {
        if (!configuration.getBoolean("composer.logs.enabled", false))
            return;

        Map<String, String> info = new HashMap<>();
        info.put("stepId", stepId);
        info.put("status", status);
        if (message != null)
            info.put("message", message);

        logger.info("Composer: " + JSONHelper.writeObject(info).replace(System.getProperty("line.separator"), ""));
    }
}
