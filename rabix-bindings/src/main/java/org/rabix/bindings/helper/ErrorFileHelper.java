package org.rabix.bindings.helper;

import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ErrorFileHelper {
    public static final String DEFAULT_JOB_STDERR_LOG = "job.stderr.log";
    private final static Logger logger = LoggerFactory.getLogger(ErrorFileHelper.class);

    public static String getErrorFilePath(Job job) {
        try {
            Bindings bindings = BindingsFactory.create(job);
            String standardErrorLog = bindings.getStandardErrorLog(job);
            if (standardErrorLog == null)
                return DEFAULT_JOB_STDERR_LOG;

            return standardErrorLog;
        } catch (BindingException e) {
            logger.warn("Exception thrown while retrieving error file path for job {}. Default path will be used {}",
                    job.getId(), DEFAULT_JOB_STDERR_LOG);
            return DEFAULT_JOB_STDERR_LOG;
        }
    }
}
