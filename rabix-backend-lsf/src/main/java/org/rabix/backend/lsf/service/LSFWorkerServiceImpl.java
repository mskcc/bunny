package org.rabix.backend.lsf.service;

import com.genestack.cluster.lsf.LSBOpenJobInfo;
import com.genestack.cluster.lsf.LSFBatch;
import com.genestack.cluster.lsf.LSFBatchException;
import com.genestack.cluster.lsf.model.*;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.NotImplementedException;
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
import org.rabix.bindings.helper.FileValueHelper;
import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.bindings.mapper.FilePathMapper;
import org.rabix.bindings.model.FileValue;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.Job.JobStatus;
import org.rabix.bindings.model.requirement.*;
import org.rabix.common.helper.ChecksumHelper;
import org.rabix.common.helper.EncodingHelper;
import org.rabix.common.json.processor.BeanProcessorException;
import org.rabix.executor.ExecutorException;
import org.rabix.executor.config.StorageConfiguration;
import org.rabix.executor.pathmapper.InputFileMapper;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LSFWorkerServiceImpl implements WorkerService {

  private final static Logger logger = LoggerFactory.getLogger(LSFWorkerServiceImpl.class);

  public final static String TYPE = "LSF";

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

  private static String imagePath = "";

  public LSFWorkerServiceImpl() {
  }

  @Override
  public void start(Backend backend) {
    try {
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

    this.scheduledTaskChecker.scheduleAtFixedRate(new Runnable() {
      @Override public void run() {
        for (Iterator<Map.Entry<Job, Long>> iterator = pendingResults.entrySet().iterator(); iterator.hasNext(); ) {
          Map.Entry<Job, Long> entry = iterator.next();
          Long lsfJobId = entry.getValue();

          LSFBatch batch = LSFBatch.getInstance();

          batch.readJobInfo(new LSFBatch.JobReader(){
            @Override public boolean readJob(JobInfoEntry jobInfoEntry) {
              jobInfo = jobInfoEntry;
              return false;
            }
          }, lsfJobId, null, null, null, null, LSBOpenJobInfo.ALL_JOB);
          if ((jobInfo.status & LSBJobStates.JOB_STAT_DONE) == LSBJobStates.JOB_STAT_DONE) {
            success(entry.getKey());
            iterator.remove();
          } else if ((jobInfo.status & LSBJobStates.JOB_STAT_EXIT) == LSBJobStates.JOB_STAT_EXIT) {
            fail(entry.getKey());
            iterator.remove();
          }

        }
      }
    }, 0, 1, TimeUnit.SECONDS);
  }

  private void success(Job job) {
    job = Job.cloneWithStatus(job, JobStatus.COMPLETED);
    Bindings bindings = null;
    try {
      bindings = BindingsFactory.create(job);
      job = bindings.postprocess(job, storageConfig.getWorkingDir(job), ChecksumHelper.HashAlgorithm.SHA1, null);
    } catch (BindingException e) {
      e.printStackTrace();
    }
    job = Job.cloneWithMessage(job, "Success");
    try {
      job = statusCallback.onJobCompleted(job);
    } catch (WorkerStatusCallbackException e1) {
      logger.warn("Failed to execute statusCallback: {}", e1);
    }
    engineStub.send(job);
  }

  public static String getImagePath() {
    return imagePath;
  }

  private void fail(Job job) {
    job = Job.cloneWithStatus(job, JobStatus.FAILED);
    try {
      job = statusCallback.onJobFailed(job);
    } catch (WorkerStatusCallbackException e) {
      logger.warn("Failed to execute statusCallback: {}", e);
    }
    engineStub.send(job);
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
  @Override
  public void submit(Job job, UUID rootId) {
    logger.info("Submitting job to LSF");

    if (System.getProperty("lsf.application.name") == null)
      System.setProperty("lsf.application.name", "default");

    LSFBatch batch = LSFBatch.getInstance();
    SubmitRequest submitRequest = new SubmitRequest();
    Bindings bindings = null;
    try {
      bindings = BindingsFactory.create(job);
      List<Requirement> combinedRequirements = getCombinedRequirements(job);
      resolveDockerRequirement(combinedRequirements, job);

      job = bindings.preprocess(job, storageConfig.getWorkingDir(job), new FilePathMapper() {
        @Override public String map(String path, Map<String, Object> config) throws FileMappingException {
          return path;
        }
      });
      if (bindings.isSelfExecutable(job)) {
        success(job);
      }
      // Environment variables
      StringBuilder envs = new StringBuilder();
      EnvironmentVariableRequirement env = getRequirement(combinedRequirements, EnvironmentVariableRequirement.class);
      if (env != null) {
        for (String varName: env.getVariables().keySet()) {
          envs.append("export ").append(varName).append("=")
              .append(EncodingHelper.shellQuote(env.getVariables().get(varName))).append(";");
        }
      }

      String singularityLocation = configuration.getString("singularity.location");
      submitRequest.command = singularityLocation + "/singularity run " + imagePath + " " + envs + bindings.buildCommandLineObject(job, storageConfig.getWorkingDir(job), new FilePathMapper() {
        @Override public String map(String path, Map<String, Object> config) throws FileMappingException {
          return path;
        }
      }).build();

      logger.debug("Command {}", submitRequest.command);

      submitRequest.cwd = storageConfig.getWorkingDir(job).getAbsolutePath();
      submitRequest.options3 = LSBSubmitOptions3.SUB3_CWD;

      submitRequest.errFile = bindings.getStandardErrorLog(job);
      if (submitRequest.errFile == null)
        submitRequest.errFile = "job.stderr.log";

      submitRequest.options = LSBSubmitOptions.SUB_ERR_FILE;

      // Resource requirements
      // TODO: Check how to insert getMemRecommendedMB() and getDiskSpaceRecommendedMB()
      ResourceRequirement jobResourceRequirement = bindings.getResourceRequirement(job);
      if (jobResourceRequirement != null) {
        logger.debug("Found resource req");
        String resReq = null;

        if (jobResourceRequirement.getMemMinMB() != null)
          resReq = "mem=" + jobResourceRequirement.getMemMinMB();
        if (jobResourceRequirement.getDiskSpaceMinMB() != null)
          resReq = (resReq != null ? ", " : "") + "swp=" + jobResourceRequirement.getDiskSpaceMinMB();

        if (resReq != null)
          resReq = "rusage[" + resReq + "]";

        if (jobResourceRequirement.getCpuMin() != null)
          resReq = "affinity[core(" + jobResourceRequirement.getCpuMin() + ")] " +
              (resReq != null ? resReq : "");

        if (resReq != null) {
          logger.debug("Generated resReq value: {}", resReq);
          submitRequest.resReq = resReq;
          submitRequest.options3 &= ~LSBSubmitOptions3.SUB3_JOB_REQUEUE;
        }

      }

      job = stageFileRequirements(combinedRequirements, job);
    } catch (BindingException e) {
      e.printStackTrace();
      fail(job);
      return;
    } catch (Exception e) {
        logger.error(String.format("Error while submitting a job %s", job), e);
        fail(job);
        return;
    }

    SubmitReply reply = null;
    try {
      reply = batch.submit(submitRequest);
      pendingResults.put(job, reply.jobID);
      logger.debug("Submitted job: " + reply.jobID);
    } catch (LSFBatchException e) {
      e.printStackTrace();
      System.out.println(submitRequest);
      fail(job);
    }
  }

  private static List<Requirement> getCombinedRequirements(Job job) throws BindingException {
    List<Requirement> combinedRequirements = new ArrayList<>();
    Bindings bindings = BindingsFactory.create(job);

      combinedRequirements.addAll(bindings.getHints(job));
    combinedRequirements.addAll(bindings.getRequirements(job));

    return combinedRequirements;
  }

  private void resolveDockerRequirement(List<Requirement> combinedRequirements, Job job) {
    DockerContainerRequirement dockerRequirement = getRequirement(combinedRequirements, DockerContainerRequirement.class);

    if(dockerRequirement != null) {
      logger.info(String.format("Resolving docker requirement: %s.", dockerRequirement));

      String dockerPull = dockerRequirement.getDockerPull();
      String singularityLocation = configuration.getString("singularity.location");
      logger.debug("Singularity location {}", singularityLocation);

      try {
        pullDockerImgWithSingularity(dockerPull, singularityLocation, job);
      } catch (Exception e) {
        throw new RuntimeException(String.format("Error while pulling docker image %s with singularity: %s", dockerPull, singularityLocation), e);
      }
    }
  }

  private void pullDockerImgWithSingularity(String dockerPull, String singularityLocation, Job job) throws IOException, InterruptedException {
    imagePath = configureDockerImgLocation(job, dockerPull);

    String command = String.format
            ("%s/singularity pull --name %s docker://%s", singularityLocation, imagePath, dockerPull);

    logger.debug("Running command {}", command);
    Process process = new ProcessBuilder().command("bash", "-c", command).start();

    int timeout = configuration.getInt("docker.image.timout");

    if(process.waitFor(timeout, TimeUnit.SECONDS)) {
      if(process.exitValue() == 0) {
        logger.info("Docker image {} successfully pulled to {}. {}", dockerPull, imagePath, IOUtils.readLines(process.getInputStream()));
      } else {
        throw new RuntimeException(String.format("Error while pulling docker image %s with singularity using command %s. Exit value: %s. Result: %s", dockerPull, command, process.exitValue(), IOUtils.readLines(process.getErrorStream())));
      }
    } else {
      process.destroy();
      throw new RuntimeException("Execution timed out: " + command);
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

      List<FileRequirement.SingleFileRequirement> fileRequirements = fileRequirementResource.getFileRequirements();
      if (fileRequirements == null) {
        return job;
      }

      Map<String, String> stagedFiles = new HashMap<>();

      for (FileRequirement.SingleFileRequirement fileRequirement : fileRequirements) {
        logger.info("Process file requirement {}", fileRequirement);
        File destinationFile = new File(workingDir, fileRequirement.getFilename());

        if (fileRequirement instanceof FileRequirement.SingleTextFileRequirement) {
          FileUtils.writeStringToFile(destinationFile, ((FileRequirement.SingleTextFileRequirement) fileRequirement).getContent());

          continue;
        }
        if (fileRequirement instanceof FileRequirement.SingleInputFileRequirement || fileRequirement instanceof FileRequirement.SingleInputDirectoryRequirement) {
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
          boolean isLinkEnabled = ((FileRequirement.SingleInputFileRequirement) fileRequirement).isLinkEnabled();
          if (file.isFile()) {
            if (isLinkEnabled) {
              Files.createLink(destinationFile.toPath(), file.toPath()); // use hard link
            } else {
              FileUtils.copyFile(file, destinationFile); // use copy
            }
          } else {
            FileUtils.copyDirectory(file, destinationFile); // use copy
          }
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

  private void recursiveSet(FileValue file, String a, Path path) {
    file.setName(a);
    file.setPath(path.resolve(a).toString());
    file.getSecondaryFiles().forEach(f -> recursiveSet(f, f.getName(), path));
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
