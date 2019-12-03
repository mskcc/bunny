package org.rabix.engine.status;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.rabix.backend.lsf.service.LSFWorkerServiceImpl;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.helper.ErrorFileHelper;
import org.rabix.bindings.helper.FileValueHelper;
import org.rabix.bindings.model.DirectoryValue;
import org.rabix.bindings.model.FileValue;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.requirement.DockerContainerRequirement;
import org.rabix.bindings.model.requirement.Requirement;
import org.rabix.common.helper.CloneHelper;
import org.rabix.engine.service.JobService;
import org.rabix.executor.config.StorageConfiguration;
import org.rabix.transport.mechanism.TransportPluginException;
import org.rabix.transport.mechanism.impl.rabbitmq.TransportPluginRabbitMQ;
import org.rabix.transport.mechanism.impl.rabbitmq.TransportQueueRabbitMQ;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.rabix.DbCacheService.CACHED_ID;
import static org.rabix.common.helper.InternalSchemaHelper.ROOT_NAME;
import static org.rabix.engine.status.LSFEngineStatusCallback.JobResponse.JobResponseBuilder;

public class LSFEngineStatusCallback implements EngineStatusCallback {
  private final static Logger logger = LoggerFactory.getLogger(LSFEngineStatusCallback.class);
  private EngineStatusCallback engineStatusCallback;

  @Inject
  private Configuration configuration;

  @Inject
  private JobService jobService;

  @Inject
  private StorageConfiguration storageConfig;

  private TransportPluginRabbitMQ transportPluginRabbitMQ;

  private TransportQueueRabbitMQ transportQueueRabbitMQ;

  @Inject
  public LSFEngineStatusCallback(@Named("Delegate") EngineStatusCallback engineStatusCallback) {
    this.engineStatusCallback = engineStatusCallback;
  }

  @Inject
  public void initRabbitMq() {
    if (configuration.getBoolean("rabbitmq.callback")) {
      try {
        this.transportPluginRabbitMQ = new TransportPluginRabbitMQ(configuration);
        this.transportQueueRabbitMQ = new TransportQueueRabbitMQ(
                configuration.getString("rabbitmq.callback.exchange"),
                configuration.getString("rabbitmq.callback.exchangeType"),
                configuration.getString("rabbitmq.callback.receiveRoutingKey"));
      } catch (TransportPluginException e) {
        logger.warn("Unable to initialize RabbitMQ", e);
      }
    }
  }

  @Override
  public void onJobReady(Job job) throws EngineStatusCallbackException {
    JobResponse jobResponse = new JobResponseBuilder(job.getName(), job.getId())
            .setJobStatus(job.getStatus())
            .create();

    sendJobStatus(jobResponse);

    engineStatusCallback.onJobReady(job);
  }

  @Override
  public void onJobsReady(Set<Job> jobs, UUID rootId, UUID producedByNode) throws EngineStatusCallbackException {
    engineStatusCallback.onJobsReady(jobs, rootId, producedByNode);
  }

  @Override
  public void onJobCompleted(Job job) throws EngineStatusCallbackException {
    engineStatusCallback.onJobCompleted(job);
    cleanup(job);

    JobResponse jobResponse = new JobResponseBuilder(job.getName(), job.getId())
            .setJobStatus(job.getStatus())
            .create();

    sendJobStatus(jobResponse);
  }

  @Override
  public void onJobFailed(Job job) throws EngineStatusCallbackException {
    engineStatusCallback.onJobFailed(job);
    cleanup(job);

    JobResponse jobResponse = new JobResponseBuilder(job.getName(), job.getId())
            .setJobStatus(job.getStatus())
            .setMessage(job.getMessage())
            .setErrFilePath(ErrorFileHelper.getErrorFilePath(job, storageConfig.getWorkingDir(job)))
            .create();
    sendJobStatus(jobResponse);
  }

  @Override
  public void onJobContainerReady(UUID id, UUID rootId) throws EngineStatusCallbackException {
    engineStatusCallback.onJobContainerReady(id, rootId);
  }

  @Override
  public void onJobRootCompleted(UUID rootId) throws EngineStatusCallbackException {
    engineStatusCallback.onJobRootCompleted(rootId);

    Map<String, Object> finalOutputs = copyOutputs(rootId, Collections.emptyMap());
    JobResponse jobResponse = new JobResponseBuilder(ROOT_NAME, rootId)
            // root job's status is in RUNNING state at that point even though it's completed
            .setJobStatus(Job.JobStatus.COMPLETED)
            .setOutputs(finalOutputs)
            .create();

    sendJobStatus(jobResponse);
  }

  private void cleanup(Job job) {
    if (configuration.getBoolean("docker.images.cleanup.enabled")) {
      String dockerImagePath = LSFWorkerServiceImpl.getImagePath();

      try {
        if (hasDockerRequirement(job)) {
          if (StringUtils.isEmpty(dockerImagePath))
            logger.warn("Docker image location is empty even though there is docker requirement present. " +
                    "There might have been problem while initializing it while submitting the job");
          else {
            deleteDockerImgFile(dockerImagePath);
          }
        }
      } catch (Exception e) {
        logger.error("Unable to delete docker image file {} and it's parent folder", dockerImagePath, e);
      }
    }
  }

  private void deleteDockerImgFile(String dockerImagePath) throws IOException {
    logger.debug("Deleting docker image file {} and it's parent folder", dockerImagePath);

    Path path = Paths.get(dockerImagePath);
    Files.delete(path);

    Path parentFolder = path.getParent();
    if (isEmpty(parentFolder))
      Files.delete(parentFolder);
    else
      logger.warn("Parent folder {} or a docker image file {} is not empty and cannot be deleted",
              parentFolder, dockerImagePath);
  }

  private boolean hasDockerRequirement(Job job) throws BindingException {
    Bindings bindings = BindingsFactory.create(job);
    List<Requirement> requirements = bindings.getRequirements(job);
    requirements.addAll(bindings.getHints(job));

    return requirements.stream()
            .anyMatch(r -> r.getClass().equals(DockerContainerRequirement.class));
  }

  private boolean isEmpty(Path path) throws IOException {
    return !Files.list(path).findAny().isPresent();
  }

  private void sendJobStatus(JobResponse jobResponse) {
    if (configuration.getBoolean("rabbitmq.callback")) {
      try {
        transportPluginRabbitMQ.send(transportQueueRabbitMQ, jobResponse);
      } catch (Exception e) {
        logger.warn("Unable to send callback to queue {} for job {}", transportQueueRabbitMQ, jobResponse);
      }
    }
  }

  private Map<String, Object> copyOutputs(UUID rootId, Map<String, Object> outputs) {
    Map<String, Object> finalOutputs = new HashMap<>();

    String outputFilesDestination = configuration.getString("output.files.location");

    if (!StringUtils.isEmpty(outputFilesDestination)) {
      Path outputFilesPath = Paths.get(outputFilesDestination);

      Map<String, Object> cachedOutputs = getOutputs(rootId);
      if (!cachedOutputs.isEmpty())
        finalOutputs = (Map<String, Object>) CloneHelper.deepCopy(cachedOutputs);
      else
        finalOutputs = (Map<String, Object>) CloneHelper.deepCopy(outputs);

      for (Map.Entry<String, Object> output : finalOutputs.entrySet()) {
        List<FileValue> filesFromValue = FileValueHelper.getFilesFromValue(output.getValue());
        for (FileValue fileValue : filesFromValue) {
          copyOutputFile(outputFilesPath, fileValue, rootId);
        }
      }
    } else
      logger.warn("Output files location in configuration is empty. Outputs won't be copied over.");

    return finalOutputs;
  }

  private Map<String, Object> getOutputs(UUID rootId) {
    Job job = jobService.get(rootId);
    return job.getOutputs() == null ? Collections.emptyMap() : job.getOutputs();
  }

  private void copyOutputFile(Path outputFilesPath, FileValue fileValue, UUID rootId) {
    Path relativePath = storageConfig.getPhysicalExecutionBaseDir().toPath().relativize(Paths.get(
            (fileValue.getPath())));

    Path source = Paths.get(fileValue.getPath());
    Path destination = getDestination(outputFilesPath, rootId, relativePath);

    try {
      Files.createDirectories(destination.getParent());
      logger.debug("Copying output file from {} to {}", source, destination);

      setOutputsProperties(fileValue, destination);

      if (fileValue instanceof DirectoryValue) {
        copyDirectory(outputFilesPath, (DirectoryValue) fileValue, rootId);
      } else {
        FileUtils.copyFile(source.toFile(), destination.toFile());
      }

      copySecondaryFiles(outputFilesPath, fileValue, rootId);
    } catch (IOException e) {
      logger.warn(String.format("Unable to copy output file from %s to %s", source, destination), e);
    }
  }

  private Path getDestination(Path outputFilesPath, UUID rootId, Path relativePath) {
    Path destination = outputFilesPath.resolve(relativePath);

    Job job = jobService.get(rootId);

    if (job == null || job.getConfig() == null || !job.getConfig().containsKey(CACHED_ID))
      return destination;

    String cachedId = job.getConfig().get(CACHED_ID).toString();
    destination = Paths.get(destination.toString().replace(cachedId, rootId.toString()));

    return destination;
  }

  private void setOutputsProperties(FileValue fileValue, Path destination) {
    fileValue.setPath(destination.toString());
    fileValue.setDirname(destination.getParent().toString());
    fileValue.setLocation(destination.toUri().toString());
  }

  private void copyDirectory(Path outputFilesPath, DirectoryValue fileValue, UUID rootId) {
    List<FileValue> listing = fileValue.getListing();
    for (FileValue value : listing) {
      copyOutputFile(outputFilesPath, value, rootId);
    }
  }

  private void copySecondaryFiles(Path outputFilesPath, FileValue fileValue, UUID rootId) {
    for (FileValue value : fileValue.getSecondaryFiles()) {
      copyOutputFile(outputFilesPath, value, rootId);
    }
  }

  @Override
  public void onJobRootPartiallyCompleted(UUID rootId, Map<String, Object> outputs, UUID producedBy) throws
          EngineStatusCallbackException {
    engineStatusCallback.onJobRootPartiallyCompleted(rootId, outputs, producedBy);

    Map<String, Object> finalOutputs = copyOutputs(rootId, outputs);
    JobResponse jobResponse = new JobResponseBuilder(ROOT_NAME, rootId)
            .setOutputs(finalOutputs)
            .setJobStatus(jobService.get(rootId).getStatus())
            .create();

    sendJobStatus(jobResponse);
  }

  @Override
  public void onJobRootFailed(UUID rootId, String message) throws EngineStatusCallbackException {
    engineStatusCallback.onJobRootFailed(rootId, message);

    JobResponse jobResponse = new JobResponseBuilder(ROOT_NAME, rootId)
            .setJobStatus(Job.JobStatus.FAILED)
            .create();

    sendJobStatus(jobResponse);
  }

  @Override
  public void onJobRootAborted(UUID rootId) throws EngineStatusCallbackException {
    engineStatusCallback.onJobRootAborted(rootId);

    JobResponse jobResponse = new JobResponseBuilder(ROOT_NAME, rootId)
            .setJobStatus(Job.JobStatus.ABORTED)
            .create();

    sendJobStatus(jobResponse);
  }

  static class JobResponse {
    private String name;
    private UUID id;
    private Job.JobStatus jobStatus;
    private String message;
    private String errFilePath;
    private Map<String, Object> outputs;

    private JobResponse(String name, UUID id, Job.JobStatus jobStatus, String message, String errFilePath,
                        Map<String, Object> outputs) {
      this.name = name;
      this.id = id;
      this.jobStatus = jobStatus;
      this.message = message;
      this.errFilePath = errFilePath;
      this.outputs = outputs;
    }

    public String getErrFilePath() {
      return errFilePath;
    }

    public void setErrFilePath(String errFilePath) {
      this.errFilePath = errFilePath;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public Map<String, Object> getOutputs() {
      return outputs;
    }

    public void setOutputs(Map<String, Object> outputs) {
      this.outputs = outputs;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public UUID getId() {
      return id;
    }

    public void setId(UUID id) {
      this.id = id;
    }

    public Job.JobStatus getJobStatus() {
      return jobStatus;
    }

    public void setJobStatus(Job.JobStatus jobStatus) {
      this.jobStatus = jobStatus;
    }

    public static class JobResponseBuilder {
      private String name;
      private UUID id;
      private Job.JobStatus jobStatus;
      private String message;
      private String errFilePath;
      private Map<String, Object> outputs;

      JobResponseBuilder(String name, UUID id) {
        this.name = name;
        this.id = id;
      }

      public JobResponseBuilder setName(String name) {
        this.name = name;
        return this;
      }

      public JobResponseBuilder setId(UUID id) {
        this.id = id;
        return this;
      }

      JobResponseBuilder setJobStatus(Job.JobStatus jobStatus) {
        this.jobStatus = jobStatus;
        return this;
      }

      JobResponseBuilder setMessage(String message) {
        this.message = message;
        return this;
      }

      JobResponseBuilder setErrFilePath(String errFilePath) {
        this.errFilePath = errFilePath;
        return this;
      }

      public JobResponseBuilder setOutputs(Map<String, Object> outputs) {
        this.outputs = outputs;
        return this;
      }

      public JobResponse create() {
        return new JobResponse(name, id, jobStatus, message, errFilePath, outputs);
      }
    }
  }
}
