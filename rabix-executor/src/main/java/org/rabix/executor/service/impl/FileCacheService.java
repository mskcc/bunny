package org.rabix.executor.service.impl;

import com.google.inject.Inject;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FileUtils;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.helper.FileValueHelper;
import org.rabix.bindings.model.FileValue;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.Job.JobStatus;
import org.rabix.common.helper.ChecksumHelper;
import org.rabix.common.json.BeanSerializer;
import org.rabix.executor.config.FileConfiguration;
import org.rabix.executor.config.StorageConfiguration;
import org.rabix.executor.service.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class FileCacheService implements CacheService {

  private final static Logger logger = LoggerFactory.getLogger(CacheService.class);

  public final static String JOB_FILE = "job.json";
  private File cacheDirectory;

  private Configuration configuration;
  private FileConfiguration fileConfiguration;
  private StorageConfiguration storageConfig;
  private boolean mockWorker;

  @Inject
  public FileCacheService(StorageConfiguration storageConfig, Configuration configuration,
                          FileConfiguration fileConfiguration) {
    this.storageConfig = storageConfig;
    this.configuration = configuration;
    this.fileConfiguration = fileConfiguration;
    if(isCacheEnabled()) {
      this.cacheDirectory = new File(configuration.getString("cache.directory"));
    }
    this.mockWorker = configuration.getBoolean("executor.mock_worker", false);
  }

  @Override
  public boolean isCacheEnabled() {
    return configuration.getBoolean("cache.enabled", false);
  }

  @Override
  public boolean isMockWorkerEnabled() {
    return configuration.getBoolean("executor.mock_worker", false);
  }

  @Override
  public void cache(Job job) {
    File workingDir = storageConfig.getWorkingDir(job);

    File cacheDir = new File(workingDir.getParentFile(), getCacheName(workingDir.getName()));
    if (!cacheDir.exists()) {
      cacheDir.mkdirs();
    }
    File jobFile = new File(cacheDir, JOB_FILE);

    job = fillCacheProperties(job);
    try {
      FileUtils.writeStringToFile(jobFile, BeanSerializer.serializePartial(job), "UTF-8");
    } catch (IOException e) {
      logger.warn("Failed to cache Job " + job.getId(), e);
    }
  }

  private String getCacheName(String filename) {
    return "." + filename + ".meta";
  }

  /**
   * Cache properties are required parameters to compare essential inputs to ones
   * that are cached in an earlier execution. These include App hash, input files' checksum, etc.
   * @param job : Job that is being analyzed for cache results.
   * @return Job : a modified job instance.
   */
  private Job fillCacheProperties(Job job) {

    Map<String, Object> inputs = job.getInputs();
    for (String inputPortKey : inputs.keySet()){
      List<FileValue> inputFiles = FileValueHelper.getFilesFromValue(inputs.get(inputPortKey));

      if (inputFiles.isEmpty()) {
        continue;
      }

      FileValue fileValue;
      for (int i=0; i<inputFiles.size(); i++) {
        fileValue = inputFiles.get(i);
        if (fileValue.getChecksum() == null || fileValue.getChecksum().isEmpty()) {

          File inputFile = new File(fileValue.getPath());
          if (inputFile.exists() && inputFile.isFile()) {
            String value = ChecksumHelper.checksum(inputFile, fileConfiguration.checksumAlgorithm());
            fileValue.setChecksum(value);
            inputFiles.set(i, fileValue);
          }
        }
      }
      inputs.put(inputPortKey, inputFiles);
    }

    return Job.cloneWithInputs(job, inputs);
  }

  @Override
  public Map<String, Object> find(Job job) {
    try {
      File cacheDir = storageConfig.getWorkingDirWithoutRoot(job);
      cacheDir = new File(cacheDirectory, cacheDir.getPath());
      cacheDir = new File(cacheDir.getParentFile(), getCacheName(cacheDir.getName()));

      logger.info("Trying to find cached results in the directory {}", cacheDir);
      if (!cacheDir.exists()) {
        logger.info("Cache directory doesn't exist. Directory {}", cacheDir);
        return null;
      }
      logger.info("Cache directory exists. Directory {}", cacheDir);

      Bindings bindings = BindingsFactory.create(job);

      File jobFile = new File(cacheDir, JOB_FILE);
      if (!jobFile.exists()) {
        logger.info("Cached Job file not found");
        return null;
      }

      Job cachedJob = BeanSerializer.deserialize(FileUtils.readFileToString(jobFile, "UTF-8"), Job.class);

      if (!cachedJob.getStatus().equals(JobStatus.COMPLETED)) {
        return null;
      }

      if (!mockWorker) {
        job = fillCacheProperties(job);
      }

      if (!CacheService.jobsEqual(job, cachedJob, !mockWorker, fileConfiguration)) {
        logger.warn("Cached job is different. Doing dry run");
        return null;
      }

      File workingDir = storageConfig.getWorkingDir(job);
      File destinationCacheDir = new File(workingDir.getParentFile(), cacheDir.getName());
      destinationCacheDir.mkdirs();
      FileUtils.copyDirectory(cacheDir, destinationCacheDir);
      return cachedJob.getOutputs();
    } catch (BindingException e) {
      logger.error("Failed to find Bindings", e);
    } catch (IOException e) {
      logger.error("Failed to read result", e);
    }
    return null;
  }

}
