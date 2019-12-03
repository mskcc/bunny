package org.rabix.engine.status.impl;

import com.google.inject.Inject;
import org.apache.commons.configuration.Configuration;
import org.rabix.bindings.model.Job;
import org.rabix.engine.service.BackendService;
import org.rabix.engine.service.JobService;
import org.rabix.engine.status.EngineStatusCallback;
import org.rabix.engine.status.EngineStatusCallbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DefaultEngineStatusCallback implements EngineStatusCallback {

  private final static Logger logger = LoggerFactory.getLogger(DefaultEngineStatusCallback.class);

  private final BackendService backendService;

  @Inject
  private Configuration configuration;

  @Inject
  private JobService jobService;

  @Inject
  public DefaultEngineStatusCallback(BackendService backendService) {
    this.backendService = backendService;
  }

  @Override
  public void onJobReady(Job job) throws EngineStatusCallbackException {
    logger.debug("onJobReady(jobId={})", job.getId());
    backendService.sendToExecution(job);
  }

  @Override
  public void onJobsReady(Set<Job> jobs, UUID rootId, UUID producedByNode) throws EngineStatusCallbackException {
    for (Job job : jobs) {
      onJobReady(job);
    }
  }

  @Override
  public void onJobFailed(Job job) throws EngineStatusCallbackException {
    logger.debug("onJobFailed(jobId={})", job.getId());
  }

  @Override
  public void onJobCompleted(Job job) throws EngineStatusCallbackException {
    logger.debug("onJobCompleted(jobId={})", job.getId());
  }

  @Override
  public void onJobRootCompleted(UUID rootId) throws EngineStatusCallbackException {
    logger.debug("onJobRootCompleted(jobId={})", rootId);
  }

  @Override
  public void onJobRootPartiallyCompleted(UUID rootId, Map<String,Object> outputs, UUID producedBy) throws EngineStatusCallbackException {
    logger.debug("onJobRootPartiallyCompleted(jobId={})", rootId);
  }

  @Override
  public void onJobRootFailed(UUID rootId, String message) throws EngineStatusCallbackException {
    logger.debug("onJobFailed(jobId={})", rootId);
  }

  @Override
  public void onJobRootAborted(UUID rootId) throws EngineStatusCallbackException {
    logger.debug("onJobAborted(jobId={})", rootId);
  }

  @Override
  public void onJobContainerReady(UUID id, UUID rootId) throws EngineStatusCallbackException {
    logger.debug("onJobRootReady(jobId={})", id);
  }

}
