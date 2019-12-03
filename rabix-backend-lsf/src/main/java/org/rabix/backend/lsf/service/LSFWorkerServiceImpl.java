package org.rabix.backend.lsf.service;

import com.genestack.cluster.lsf.LSBOpenJobInfo;
import com.genestack.cluster.lsf.LSFBatch;
import com.genestack.cluster.lsf.LSFBatchException;
import com.genestack.cluster.lsf.model.*;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;
import org.rabix.backend.api.WorkerService;
import org.rabix.backend.api.callback.WorkerStatusCallback;
import org.rabix.backend.api.callback.WorkerStatusCallbackException;
import org.rabix.backend.api.engine.EngineStub;
import org.rabix.backend.api.engine.EngineStubActiveMQ;
import org.rabix.backend.api.engine.EngineStubLocal;
import org.rabix.backend.api.engine.EngineStubRabbitMQ;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.CommandLine;
import org.rabix.bindings.helper.ErrorFileHelper;
import org.rabix.bindings.helper.FileValueHelper;
import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.bindings.mapper.FilePathMapper;
import org.rabix.bindings.model.FileValue;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.Job.JobStatus;
import org.rabix.bindings.model.dag.DAGLinkPort;
import org.rabix.bindings.model.requirement.*;
import org.rabix.common.helper.ChecksumHelper;
import org.rabix.common.helper.EncodingHelper;
import org.rabix.common.json.processor.BeanProcessorException;
import org.rabix.engine.service.VariableRecordService;
import org.rabix.engine.store.model.JobRecord;
import org.rabix.engine.store.model.VariableRecord;
import org.rabix.engine.store.repository.JobRecordRepository;
import org.rabix.engine.store.repository.JobRepository;
import org.rabix.engine.store.repository.LSFJobRepository;
import org.rabix.executor.ExecutorException;
import org.rabix.executor.config.FileConfiguration;
import org.rabix.executor.config.StorageConfiguration;
import org.rabix.executor.container.ContainerException;
import org.rabix.executor.container.ContainerHandler;
import org.rabix.executor.container.ContainerHandlerFactory;
import org.rabix.executor.container.impl.DockerContainerHandler;
import org.rabix.executor.handler.JobHandler;
import org.rabix.executor.pathmapper.InputFileMapper;
import org.rabix.executor.service.CacheService;
import org.rabix.transport.backend.Backend;
import org.rabix.transport.backend.impl.BackendActiveMQ;
import org.rabix.transport.backend.impl.BackendLocal;
import org.rabix.transport.backend.impl.BackendRabbitMQ;
import org.rabix.transport.mechanism.TransportPluginException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class LSFWorkerServiceImpl implements WorkerService {

  public final static String TYPE = "LSF";
  public static final String ERR_FILE_PATH = "ERR_FILE_PATH";
  private static final String SINGULARITY_MOUNT_DELIMITER = ",";
  private final static Logger logger = LoggerFactory.getLogger(LSFWorkerServiceImpl.class);
  public static String DEFAULT_RESOURCE_REQUIREMENTS = "affinity[core(2)] rusage[mem=4]"; //TODO move to properties
  private static String imagePath = "";
  @Inject
  private JobRecordRepository jobRecordRepository;
  @Inject
  private JobRepository jobRepository;
  @Inject
  private Configuration configuration;
  private EngineStub<?, ?, ?> engineStub;
  private ScheduledExecutorService scheduledTaskChecker = Executors.newScheduledThreadPool(1);
  private Map<Job, Long> pendingResults = new ConcurrentHashMap<>();
  @com.google.inject.Inject
  private WorkerStatusCallback statusCallback;
  @com.google.inject.Inject
  private StorageConfiguration storageConfig;
  private JobInfoEntry jobInfo;
  @Inject
  @InputFileMapper
  private FilePathMapper inputFileMapper;
  @Inject
  private LSFJobRepository lsfJobRepository;

  @Inject
  private FileConfiguration fileConfiguration;

  @Inject
  private CacheService cacheService;

  @com.google.inject.Inject
  private VariableRecordService variableService;

  @Inject
  private ContainerHandlerFactory containerHandlerFactory;
  private ContainerHandler containerHandler;

  public static String getImagePath() {
    return imagePath;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Requirement> T getRequirement(List<Requirement> requirements, Class<T> clazz) {
    for (Requirement requirement : requirements) {
      if (requirement.getClass().equals(clazz)) {
        return (T) requirement;
      }
    }
    return null;
  }

  private static List<Requirement> getCombinedRequirements(Job job) throws BindingException {
    List<Requirement> combinedRequirements = new ArrayList<>();
    Bindings bindings = BindingsFactory.create(job);

    combinedRequirements.addAll(bindings.getHints(job));
    combinedRequirements.addAll(bindings.getRequirements(job));

    return combinedRequirements;
  }

  @Override
  public void start(Backend backend) {
    try {
      DEFAULT_RESOURCE_REQUIREMENTS = String.format("affinity[core(%d)] rusage[mem=%d]", configuration.getInt("lsf" +
              ".default.cores"), configuration.getInt("lsf.default.cpu.gb"));

      switch (backend.getType()) {
        case LOCAL:
          engineStub = new EngineStubLocal((BackendLocal) backend, this, configuration);
          break;
        case RABBIT_MQ:
          engineStub = new EngineStubRabbitMQ((BackendRabbitMQ) backend, this, configuration);
          break;
        case ACTIVE_MQ:
          engineStub = new EngineStubActiveMQ((BackendActiveMQ) backend, this, configuration);
        default:
          break;
      }
      engineStub.start();

    } catch (TransportPluginException e) {
      logger.error("Failed to initialize Executor", e);
      throw new RuntimeException("Failed to initialize Executor", e);
    } catch (BeanProcessorException e) {
      logger.error("Failed to initialize Executor", e);
    }

    Map<Job, Long> notCompletedNorFailedJobs = getNotCompletedNorFailedJobs();
    pendingResults.putAll(notCompletedNorFailedJobs);

    this.scheduledTaskChecker.scheduleAtFixedRate(() -> {
      for (Iterator<Map.Entry<Job, Long>> iterator = pendingResults.entrySet().iterator(); iterator.hasNext(); ) {
        Map.Entry<Job, Long> entry = iterator.next();
        Long lsfJobId = entry.getValue();

        LSFBatch batch = LSFBatch.getInstance();

        batch.readJobInfo(jobInfoEntry -> {
          jobInfo = jobInfoEntry;
          return false;
        }, lsfJobId, null, null, null, null, LSBOpenJobInfo.ALL_JOB);

        if ((jobInfo.status & LSBJobStates.JOB_STAT_DONE) == LSBJobStates.JOB_STAT_DONE) {
          try {
            success(entry.getKey(), true);
          } catch (BindingException e) {
            fail(entry.getKey());
          } finally {
            iterator.remove();
          }
        } else if ((jobInfo.status & LSBJobStates.JOB_STAT_EXIT) == LSBJobStates.JOB_STAT_EXIT) {
          fail(entry.getKey());
          iterator.remove();
        }

      }
    }, 0, 1, TimeUnit.SECONDS);
  }

  private Map<Job, Long> getNotCompletedNorFailedJobs() {
    List<JobRecord> allJobs = jobRecordRepository.get();
    Map<Job, Long> notCompletedNorFailedJobs = allJobs.stream()
            .filter(j -> j.getState() != JobRecord.JobState.COMPLETED && j.getState() != JobRecord.JobState.FAILED)
            .filter(j -> lsfJobRepository.get(j.getExternalId()) != null)
            .collect(Collectors.toMap(j -> jobRepository.get(j.getExternalId()), j -> lsfJobRepository.get(j
                    .getExternalId())));

    logger.info("Not completed nor failed jobs from database which will be processed: {}",
            notCompletedNorFailedJobs);

    return notCompletedNorFailedJobs;
  }

  private Job success(Job job, boolean shouldPostProcess) throws BindingException {
    job = Job.cloneWithStatus(job, JobStatus.COMPLETED);
    Bindings bindings = BindingsFactory.create(job);

    if (shouldPostProcess)
      job = bindings.postprocess(job, storageConfig.getWorkingDir(job), ChecksumHelper.HashAlgorithm.SHA1, null);
    job = Job.cloneWithMessage(job, "Success");

    try {
      if (containerHandler != null)
        containerHandler.dumpCommandLine();
      job = statusCallback.onJobCompleted(job);
    } catch (WorkerStatusCallbackException e1) {
      logger.warn("Failed to execute statusCallback: {}", e1);
    } catch (ContainerException e) {
      logger.warn("Failed to execute dump cmd: {}", e);
    }
    engineStub.send(job);

    return job;
  }

  private void fail(Job job) {
    job = Job.cloneWithStatus(job, JobStatus.FAILED);
    try {
      if (containerHandler != null)
        containerHandler.dumpCommandLine();
      job = statusCallback.onJobFailed(job);
    } catch (WorkerStatusCallbackException e) {
      logger.warn("Failed to execute statusCallback: {}", e);
    } catch (ContainerException e) {
      logger.warn("Failed to dump cmd: {}", e);
    }
    engineStub.send(job);
  }

  @Override
  public void submit(Job job, UUID rootId) {
    try {
      Bindings bindings = BindingsFactory.create(job);
      job = bindings.preprocess(job, storageConfig.getWorkingDir(job), (path, config) -> path);

      List<VariableRecord> variableRecords = variableService.find(job.getName().toString(), DAGLinkPort.LinkPortType
              .INPUT, rootId);

      for (VariableRecord variableRecord : variableRecords) {
        variableRecord.setValue(job.getInputs().get(variableRecord.getPortId()));
        variableService.update(variableRecord);
      }

      if (cacheService.isCacheEnabled()) {
        Map<String, Object> cachedOutputs = cacheService.find(job);
        if (!cachedOutputs.isEmpty()) {
          job = Job.cloneWithOutputs(job, cachedOutputs);

          logger.debug("Cached outputs found. Those outputs will be used for current job.");
          success(job, false);
        } else {
          job = processJob(job, rootId);
        }
      } else
        processJob(job, rootId);
    } catch (Exception e) {
      logger.error(String.format("Error while submitting a job %s", job.getId()), e);
      fail(job);
      return;
    }
  }

  private Job processJob(Job job, UUID rootId) throws BindingException {
    Bindings bindings = BindingsFactory.create(job);

    if (bindings.isSelfExecutable(job)) {
      logger.info("Job {} is self executable. It won't be submitted to LSF", job.getId());
      job = success(job, true);
    } else {
      SubmitRequest submitRequest = new SubmitRequest();
      try {
        if (isAlreadySubmitted(job)) {
          logger.info("Job {} (root: {}) has already been submitted to LSF. It won't be submitted " +
                          "again",

                  job.getId(), rootId);
          return job;
        }

        logger.info("Submitting job {} to LSF", job.getId());

        configureLsfAppName();

        List<Requirement> combinedRequirements = getCombinedRequirements(job);

        resolveDockerRequirement(combinedRequirements, job);

        job = bindings.preprocess(job, storageConfig.getWorkingDir(job), (path, config) -> path);

        configureCommand(job, bindings, submitRequest, combinedRequirements);
        configureWorkingDir(job, submitRequest);
        configureErrFile(job, submitRequest);
        configureResourceRequirements(job, bindings, submitRequest);
        submitRequest.jobName = String.valueOf(job.getId());
        configureSla(submitRequest);

        configureTerminationTime(submitRequest, job);

        job = stageFileRequirements(combinedRequirements, job);
      } catch (Exception e) {
        logger.error(String.format("Error while submitting a job %s", job), e);
        fail(job);
        return job;
      }

      submitAndGetLsfReply(job, submitRequest);
    }

    return job;
  }

  public void dumpCommandLine(String commandLine, Job job) {
    try {
      File errorFolder = ErrorFileHelper.getErrorFolderPath(storageConfig.getWorkingDir(job)).toFile();
      File commandLineFile = new File(errorFolder, JobHandler.COMMAND_LOG);
      FileUtils.writeStringToFile(commandLineFile, commandLine + "\n" + job);
    } catch (IOException e) {
      logger.error("Failed to dump command line into " + JobHandler.COMMAND_LOG);
    }
  }

  private void configureSla(SubmitRequest submitRequest) {
    String slaName = configuration.getString("lsf.sla.name");
    if (!StringUtils.isEmpty(slaName)) {
      logger.debug("Configuring SLA {}", slaName);
      submitRequest.sla = slaName;
      submitRequest.options2 |= LSBSubmitOptions2.SUB2_SLA;
    }
  }

  private boolean isAlreadySubmitted(Job job) {
    return lsfJobRepository.get(job.getId()) != null;
  }

  private void configureTerminationTime(SubmitRequest submitRequest, Job job) {
    int maxTimeMinutes = configuration.getInt("lsf.time.limit.minutes");
    submitRequest.termTime = Instant.now().plus(maxTimeMinutes, ChronoUnit.MINUTES).toEpochMilli();

    logger.debug("Termination time for job {}: {}", job.getId(), Instant.ofEpochSecond(submitRequest.termTime));
  }

  private void configureCommand(Job job, Bindings bindings, SubmitRequest submitRequest, List<Requirement>
          combinedRequirements) throws BindingException {
    StringBuilder envs = getEnvironmentVariables(combinedRequirements);
    String singularityLocation = configuration.getString("singularity.location");

    String command = getCommand(job, bindings);
    String binds = getBinds(job, combinedRequirements);
    submitRequest.command = String.format("%s/singularity run %s %s %s %s /bin/sh -c \"%s\"", singularityLocation,
            !StringUtils.isEmpty(binds) ? "-B" : "", binds, imagePath, envs, command);

    dumpCommandLine(submitRequest.command, job);
    logger.debug("Command {}", submitRequest.command);
  }

  private void configureWorkingDir(Job job, SubmitRequest submitRequest) {
    submitRequest.cwd = storageConfig.getWorkingDir(job).getAbsolutePath();
    submitRequest.options3 = LSBSubmitOptions3.SUB3_CWD;
  }

  private String getBinds(Job job, List<Requirement> combinedRequirements) {
    File workingDir = storageConfig.getWorkingDir(job);
    Path rootWorkingDir = DockerContainerHandler.findRoot(workingDir.toPath());
    Set<String> binds = new HashSet<>();
    Set<FileValue> flat = new HashSet<>();

    try {
      for (FileValue f : FileValueHelper.getInputFiles(job)) {
        flat.add(f);
        flat.addAll(f.getSecondaryFiles());
      }
      for (FileValue f : flat) {
        Path location = Paths.get(URI.create(f.getLocation()));
        if (Files.isSymbolicLink(location)) {
          getToTheBottomOfThis(location, binds);
        }
        if (location.startsWith(rootWorkingDir)) {
          continue;
        }
        String path = f.getPath();
        if (path.equals(location.toString())) {
          binds.add(location.getParent().toString() + ":" + location.getParent()
                  .toString());
        } else {
          binds.add(location.toString() + ":" + path);
        }
      }

      DockerContainerRequirement dockerRequirement = getRequirement(combinedRequirements,
              DockerContainerRequirement.class);

      if (dockerRequirement != null && dockerRequirement.getDockerOutputDirectory() != null) {
        binds.add(workingDir.getAbsolutePath() + ":" + dockerRequirement
                .getDockerOutputDirectory());
      }

      binds.add(rootWorkingDir.toString() + ":" + rootWorkingDir.toString());
    } catch (BindingException e) {
      logger.warn(String.format("Unable to get singularity binds for job %s", job.getId()), e);
    }

    return StringUtils.join(binds, SINGULARITY_MOUNT_DELIMITER);
  }

  private void getToTheBottomOfThis(Path p, Set<String> binds) {
    if (Files.isSymbolicLink(p)) {
      try {
        Path linked = Files.readSymbolicLink(p);
        if (!linked.isAbsolute()) {
          linked = p.resolveSibling(linked).normalize().toAbsolutePath();
        }
        binds.add(linked.toString() + ":" + linked.toString());
        getToTheBottomOfThis(linked, binds);
      } catch (IOException e) {
        logger.error(e.getMessage(), e);
      }
    }
  }

  private void submitAndGetLsfReply(Job job, SubmitRequest submitRequest) {
    LSFBatch batch = LSFBatch.getInstance();

    SubmitReply reply;
    try {
      reply = batch.submit(submitRequest);
      saveLSFJobId(job, reply);

      logger.debug("Submitted job {} with LSF id {}", job.getId(), reply.jobID);

      pendingResults.put(job, reply.jobID);
      job = Job.cloneWithStatus(job, JobStatus.RUNNING);
      engineStub.send(job);
    } catch (LSFBatchException e) {
      logger.error(String.format("Error while submitting job to LSF with request %s. Job: %s",
              submitRequest, job), e);

      fail(job);
    }
  }

  private void configureErrFile(Job job, SubmitRequest submitRequest) throws BindingException {
    submitRequest.options = LSBSubmitOptions.SUB_ERR_FILE;
    submitRequest.errFile = ErrorFileHelper.getErrorFilePath(job, storageConfig.getWorkingDir(job));

    if (job.getConfig() == null)
      job = Job.cloneWithConfig(job, new HashMap<>());
    job.getConfig().put(ERR_FILE_PATH, submitRequest.errFile);
  }

  private String getCommand(Job job, Bindings bindings) throws BindingException {
    CommandLine commandLine = bindings.buildCommandLineObject(job, storageConfig.getWorkingDir(job), (path,
                                                                                                      config) ->
            path);
    return commandLine.build();
  }

  private void configureLsfAppName() {
    if (System.getProperty("lsf.application.name") == null) {
      System.setProperty("lsf.application.name", "default");
    }
  }

  private void configureResourceRequirements(Job job, Bindings bindings, SubmitRequest submitRequest) throws
          BindingException {
    // TODO: Check how to insert getMemRecommendedMB() and getDiskSpaceRecommendedMB()
    ResourceRequirement jobResourceRequirement = bindings.getResourceRequirement(job);
    String resReq = DEFAULT_RESOURCE_REQUIREMENTS;

    if (jobResourceRequirement != null) {
      logger.debug("Found resource req");

      if (jobResourceRequirement.getMemMinMB() != null) {
        resReq = "mem=" + getRecalculatedRequirement(jobResourceRequirement.getMemMinMB());
      }
      if (jobResourceRequirement.getDiskSpaceMinMB() != null)
        resReq = (resReqExists(resReq) ? ", " : "") + "swp=" + getRecalculatedRequirement(jobResourceRequirement
                .getDiskSpaceMinMB());

      if (resReqExists(resReq))
        resReq = "rusage[" + resReq + "]";

      if (jobResourceRequirement.getCpuMin() != null)
        resReq = "affinity[core(" + jobResourceRequirement.getCpuMin() + ")] " +
                (resReqExists(resReq) ? resReq : "");

      if (resReqExists(resReq)) {
        logger.debug("Generated resReq value: {}", resReq);
      }
    } else {
      logger.debug("Using default resource requirements {}", DEFAULT_RESOURCE_REQUIREMENTS);
    }

    turnOnResReq(submitRequest);
    submitRequest.resReq = resReq;
    submitRequest.options3 &= ~LSBSubmitOptions3.SUB3_JOB_REQUEUE;
  }

  private boolean resReqExists(String resReq) {
    return !Objects.equals(resReq, DEFAULT_RESOURCE_REQUIREMENTS);
  }

  private Long getRecalculatedRequirement(long requirement) {
    int memoryDivisor = configuration.getInt("lsf.memory.unit");

    return requirement / memoryDivisor;
  }

  private void turnOnResReq(SubmitRequest submitRequest) {
    submitRequest.options |= LSBSubmitOptions.SUB_RES_REQ;
  }

  private StringBuilder getEnvironmentVariables(List<Requirement> combinedRequirements) {
    StringBuilder envs = new StringBuilder();
    EnvironmentVariableRequirement env = getRequirement(combinedRequirements, EnvironmentVariableRequirement.class);
    if (env != null) {
      for (String varName : env.getVariables().keySet()) {
        envs.append("export ").append(varName).append("=")
                .append(EncodingHelper.shellQuote(env.getVariables().get(varName))).append(";");
      }
    }
    return envs;
  }

  private void saveLSFJobId(Job job, SubmitReply reply) {
    lsfJobRepository.insert(job.getId(), reply.jobID);
  }

  private void resolveDockerRequirement(List<Requirement> combinedRequirements, Job job) {
    DockerContainerRequirement dockerRequirement = getRequirement(combinedRequirements,
            DockerContainerRequirement.class);

    if (dockerRequirement != null) {
      logger.info(String.format("Resolving docker requirement: %s.", dockerRequirement));

      String dockerPull = dockerRequirement.getDockerPull();
      String singularityLocation = configuration.getString("singularity.location");
      logger.debug("Singularity location {}", singularityLocation);

      try {
        pullDockerImgWithSingularity(dockerPull, singularityLocation, job);
      } catch (Exception e) {
        throw new RuntimeException(String.format("Error while pulling docker image %s with singularity: %s",
                dockerPull, singularityLocation), e);
      }
    }
  }

  private void pullDockerImgWithSingularity(String dockerPull, String singularityLocation, Job job) throws
          IOException, InterruptedException {
    imagePath = configureDockerImgLocation(job, dockerPull);

    if (!Files.exists(Paths.get(imagePath))) {
      String command = String.format
              ("%s/singularity pull --name %s docker://%s", singularityLocation, imagePath, dockerPull);

      logger.debug("Running command {}", command);
      Process process = new ProcessBuilder().command("bash", "-c", command).start();

      int timeout = configuration.getInt("docker.image.timout.seconds");

      if (process.waitFor(timeout, TimeUnit.SECONDS)) {
        if (process.exitValue() == 0) {
          logger.info("Docker image {} successfully pulled to {}. {}", dockerPull, imagePath, IOUtils
                  .readLines(process.getInputStream()));

        } else {
          throw new RuntimeException(String.format("Error while pulling docker image %s with singularity " +
                          "using command %s. Exit value: %s. Result: %s", dockerPull, command, process
                          .exitValue(),
                  IOUtils.readLines(process.getErrorStream())));
        }
      } else {
        process.destroy();
        throw new RuntimeException("Execution timed out: " + command);
      }
    }
  }

  private String configureDockerImgLocation(Job job, String dockerPull) throws IOException {
    String dockerImgLocation = configuration.getString("docker.images.location");
    String jobDockerImgLocation = String.format("%s/%s", dockerImgLocation, job.getId());
    logger.debug("Docker image location {}", jobDockerImgLocation);

    Files.createDirectories(Paths.get(jobDockerImgLocation));
    return String.format("%s/%s.img", jobDockerImgLocation, dockerPull.replaceAll("/", "_"));
  }

  private Job stageFileRequirements(List<Requirement> requirements, Job job) throws Exception {
    try {
      File workingDir = storageConfig.getWorkingDir(job);

      FileRequirement fileRequirementResource = getRequirement(requirements, FileRequirement.class);
      if (fileRequirementResource == null) {
        return job;
      }

      List<FileRequirement.SingleFileRequirement> fileRequirements = fileRequirementResource
              .getFileRequirements();
      if (fileRequirements == null) {
        return job;
      }

      Map<String, String> stagedFiles = new HashMap<>();

      for (FileRequirement.SingleFileRequirement fileRequirement : fileRequirements) {
        logger.info("Process file requirement {}", fileRequirement);
        File destinationFile = new File(workingDir, fileRequirement.getFilename());

        if (fileRequirement instanceof FileRequirement.SingleTextFileRequirement) {
          FileUtils.writeStringToFile(destinationFile, ((FileRequirement.SingleTextFileRequirement)
                  fileRequirement).getContent());

          continue;
        }
        if (fileRequirement instanceof FileRequirement.SingleInputFileRequirement || fileRequirement
                instanceof FileRequirement.SingleInputDirectoryRequirement) {
          FileValue content = ((FileRequirement.SingleInputFileRequirement) fileRequirement).getContent();
          if (FileValue.isLiteral(content)) {
            if (fileRequirement instanceof FileRequirement.SingleInputDirectoryRequirement) {
              destinationFile.mkdirs();
            } else {
              destinationFile.createNewFile();
            }
            return job;
          }

          URI location = URI.create(content.getLocation());
          String path = location.getScheme() != null ? Paths.get(location).toString() : content.getPath();
          String mappedPath = inputFileMapper.map(path, job.getConfig());
          stagedFiles.put(path, destinationFile.getPath());
          File file = new File(mappedPath);

          if (!file.exists()) {
            continue;
          }
          if (file.isFile()) FileUtils.copyFile(file, destinationFile);
          else FileUtils.copyDirectory(file, destinationFile);
        }
      }

      try {
        job = FileValueHelper.updateInputFiles(job, fileValue -> {
          if (stagedFiles.containsKey(fileValue.getPath())) {
            String path = stagedFiles.get(fileValue.getPath());
            fileValue.setPath(path);
            fileValue.setLocation(Paths.get(path).toUri().toString());
          }

          return fileValue;
        });
      } catch (BindingException e) {
        throw new FileMappingException(e);
      }
    } catch (IOException e) {
      throw new ExecutorException("Failed to process file requirements.", e);
    }

    return job;
  }

  @Override
  public void cancel(List<UUID> ids, UUID contextId) {
    throw new NotImplementedException("This method is not implemented");
  }

  @Override
  public void freeResources(UUID rootId, Map<String, Object> config) {
    throw new NotImplementedException("This method is not implemented");
  }

  @Override
  public void shutdown(Boolean stopEverything) {
    throw new NotImplementedException("This method is not implemented");
  }

  @Override
  public boolean isRunning(UUID id, UUID contextId) {
    throw new NotImplementedException("This method is not implemented");
  }

  @Override
  public Map<String, Object> getResult(UUID id, UUID contextId) {
    throw new NotImplementedException("This method is not implemented");
  }

  @Override
  public boolean isStopped() {
    throw new NotImplementedException("This method is not implemented");
  }

  @Override
  public JobStatus findStatus(UUID id, UUID contextId) {
    throw new NotImplementedException("This method is not implemented");
  }

  @Override
  public String getType() {
    return TYPE;
  }

}
