package org.rabix.bindings.helper;

import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class ErrorFileHelper {
  private static final String DEFAULT_JOB_STDERR_LOG = "job.stderr.log";
  private final static Logger logger = LoggerFactory.getLogger(ErrorFileHelper.class);

  public static String getErrorFilePath(Job job, File workingDir) {
    try {
      Bindings bindings = BindingsFactory.create(job);
      String standardErrorLog = bindings.getStandardErrorLog(job);
      if (standardErrorLog == null) {
        Path errorFolderPath = getErrorFolderPath(workingDir);
        return errorFolderPath.resolve(DEFAULT_JOB_STDERR_LOG).toString();
      }

      return standardErrorLog;
    } catch (BindingException e) {
      logger.warn("Exception thrown while retrieving error file path for job {}. Default path will be used {}",
              job.getId(), DEFAULT_JOB_STDERR_LOG);
      return DEFAULT_JOB_STDERR_LOG;
    }
  }

  public static Path getErrorFolderPath(File workingDir) {
    try {
      Path fileName = workingDir.toPath().getFileName();
      Path path = workingDir.toPath().resolveSibling("." + fileName.toString());
      Files.createDirectories(path);

      return path;
    } catch (Exception e) {
      logger.warn(String.format("Unable to retrieve error folder path for working dir %s", workingDir), e);
    }

    return null;
  }
}
