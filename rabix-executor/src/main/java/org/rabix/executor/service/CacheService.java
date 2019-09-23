package org.rabix.executor.service;

import org.rabix.bindings.BindingException;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.model.DirectoryValue;
import org.rabix.bindings.model.FileValue;
import org.rabix.bindings.model.Job;
import org.rabix.common.helper.ChecksumHelper;
import org.rabix.executor.config.FileConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;

public interface CacheService {
    Logger logger = LoggerFactory.getLogger(CacheService.class);

    static boolean jobsEqual(Job job, Job cachedJob, FileConfiguration
            fileConfiguration) throws BindingException {
        return jobsEqual(job, cachedJob, true, fileConfiguration);

    }

    /**
     * Checks whether a job is equal to another job that is cached
     *
     * @param job
     * @param cachedJob
     * @param compareInputs
     * @param fileConfiguration
     * @return true if jobs are equal
     */
    static boolean jobsEqual(Job job, Job cachedJob, boolean compareInputs, FileConfiguration
            fileConfiguration) throws BindingException {
        if (!BindingsFactory.create(job).loadAppObject(job.getApp()).equals(BindingsFactory.create(cachedJob)
                .loadAppObject(cachedJob.getApp()))) {
            logger.debug("Current job's app {} and cached job's app {} is different", job.getApp(), cachedJob.getApp());
            return false;
        }

        if (compareInputs) {
            if (!areInputsSame(job, cachedJob)) {
                logger.debug("Current job's inputs {} and cached job's inputs {} are different", job.getInputs(),
                        cachedJob.getInputs());
                return false;
            }
        /*
         * Integrity check is necessary for output files.
         * Cache directory might be corrupted
         */
            Map<String, Object> cachedOutputs = cachedJob.getOutputs();
            for (String outputPortKey : cachedOutputs.keySet()) {
                Object cachedValue = cachedOutputs.get(outputPortKey);
                if (cachedValue instanceof FileValue &&
                        !checkFileIntegrity((FileValue) cachedValue, fileConfiguration)) {
                    return false;
                }
            }

        }
        return true;
    }

    static boolean areInputsSame(Job job, Job cachedJob) {
        // FileValue equality is different for caching.
        Map<String, Object> inputs = job.getInputs();
        Map<String, Object> cachedInputs = cachedJob.getInputs();

        for (String inputPortKey : inputs.keySet()) {
            if (!cachedInputs.containsKey(inputPortKey)) {
                return false;
            }

            Object value = inputs.get(inputPortKey);
            Object cachedValue = cachedInputs.get(inputPortKey);
            if (value == null && cachedValue == null) {
                continue;
            }

            if (!cacheValuesEqual(value, cachedValue)) {
                return false;
            }
        }
        return true;
    }

    static boolean cacheValuesEqual(Object value, Object cachedValue) {
        try {
            if (value instanceof List && cachedValue instanceof List) {
                for (int i = 0; i < ((List) value).size(); i++) {
                    if (!cacheValuesEqual(((List) value).get(i), ((List) cachedValue).get(i))) {
                        return false;
                    }
                }
                return true;
            } else if (value instanceof Map && cachedValue instanceof Map) {
                for (Object key : ((Map<?, ?>) value).keySet()) {
                    if (!cacheValuesEqual(((Map) value).get(key), ((Map) cachedValue).get(key))) {
                        return false;
                    }
                }
                return true;
            } else if(value instanceof DirectoryValue && cachedValue instanceof DirectoryValue) {
                List<FileValue> listing = ((DirectoryValue) value).getListing();

                for (int i = 0; i < listing.size(); i++) {
                    List<FileValue> cachedListing = ((DirectoryValue) cachedValue).getListing();
                    if(!checkFileEquals(listing.get(i), cachedListing.get(i)))
                        return false;
                }
                return true;
            } else if (value instanceof FileValue && cachedValue instanceof FileValue) {
                return checkFileEquals((FileValue) value, (FileValue) cachedValue);
            } else {
                return value.equals(cachedValue);
            }
        } catch (Exception e) {
            logger.warn("Cache values are not equal. Exception thrown {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check whether input files are equal. For backwards compatibility,
     * if {@code cachedValue} is missing some of the necessary fields, it does
     * favor equality of the files.
     *
     * @param value
     * @param cachedValue
     * @return
     */
    static boolean checkFileEquals(FileValue value, FileValue cachedValue) {
        try {
            if (cachedValue.getSize() != 0 &&
                    !value.getSize().equals(cachedValue.getSize()))
                return false;
            if (cachedValue.getChecksum() != null &&
                    !value.getChecksum().equals(cachedValue.getChecksum()))
                return false;
            // This control does not apply to the opposite situation for the reason explained in the method's doc.
            if (value.getSecondaryFiles() != null && cachedValue.getSecondaryFiles() == null)
                return false;
            else if (value.getSecondaryFiles() != null && cachedValue.getSecondaryFiles() != null) {
                for (int i = 0; i < value.getSecondaryFiles().size(); i++) {
                    if (!checkFileEquals(value.getSecondaryFiles().get(i), cachedValue.getSecondaryFiles().get(i))) {
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    static boolean checkFileIntegrity(FileValue value, FileConfiguration fileConfiguration) {
        try {
            if(value instanceof DirectoryValue) {
                for (FileValue fileValue : ((DirectoryValue) value).getListing()) {
                    if(!checkFileIntegrity(fileValue, fileConfiguration))
                        return false;
                }
            } else {
                File file = new File(value.getPath());
                if (file.exists() && file.isFile()) {
                    if (value.getSize() != 0 &&
                            !value.getSize().equals(file.length())) {
                        return false;
                    }
                    if (value.getChecksum() != null) {
                        String checksumValue = ChecksumHelper.checksum(file, fileConfiguration.checksumAlgorithm());
                        if (!checksumValue.equals(value.getChecksum())) {
                            return false;
                        }
                    }
                    if (value.getSecondaryFiles() != null) {
                        for (int i = 0; i < value.getSecondaryFiles().size(); i++) {
                            if (!checkFileIntegrity(value.getSecondaryFiles().get(i), fileConfiguration)) {
                                return false;
                            }
                        }
                    }
                } else {
                    return false;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    void cache(Job job);

    Map<String, Object> find(Job job);

    boolean isCacheEnabled();

    boolean isMockWorkerEnabled();
}
