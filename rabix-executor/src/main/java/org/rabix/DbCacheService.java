package org.rabix;

import com.google.inject.Inject;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.rabix.bindings.model.Job;
import org.rabix.engine.store.repository.JobRepository;
import org.rabix.executor.config.FileConfiguration;
import org.rabix.executor.config.StorageConfiguration;
import org.rabix.executor.service.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

//TODO think of a place to put it
public class DbCacheService implements CacheService {
    public static final String CACHED_ID = "cachedId";
    private final static Logger logger = LoggerFactory.getLogger(DbCacheService.class);
    @Inject
    private Configuration configuration;

    @Inject
    private JobRepository jobRepository;

    @Inject
    private FileConfiguration fileConf;

    @Inject
    private StorageConfiguration storageConfig;

    @Override
    public void cache(Job job) {
        jobRepository.update(job);
    }

    @Override
    public Map<String, Object> find(Job job) {
        UUID cachedUUID = null;
        try {
            Job rootJob = jobRepository.get(job.getRootId());
            if (rootJob == null || rootJob.getConfig() == null || !rootJob.getConfig().containsKey(CACHED_ID))
                return Collections.emptyMap();

            String cachedId = String.valueOf(rootJob.getConfig().getOrDefault(CACHED_ID, ""));
            if (StringUtils.isEmpty(cachedId)) {
                logger.debug("Cached id provided is empty. Outputs won't be collected from cached jobs");
                return Collections.emptyMap();
            }

            cachedUUID = UUID.fromString(cachedId);
            Set<Job> jobsByRootId = jobRepository.getByRootIdAndName(cachedUUID, job.getName());
            jobsByRootId = jobsByRootId.stream()
                    .filter(j -> !j.isRoot())
                    .collect(Collectors.toSet());

            if (jobsByRootId.size() == 0) {
                logger.debug("No jobs found for root id {} and name {}", cachedUUID, job.getName());
                return Collections.emptyMap();
            }
            if (jobsByRootId.size() > 1) {
                logger.debug("Multiple jobs found for root id {} and name {}. It should be unambiguous, outputs won't" +
                        " be retrieved from cached job. {}", cachedUUID, job.getName(), jobsByRootId);
                return Collections.emptyMap();
            }

            Job cachedJob = jobsByRootId.iterator().next();
            UUID cachedJobId = cachedJob.getId();
            if (cachedJob.getStatus() != Job.JobStatus.COMPLETED) {
                logger.debug("Cached job {} is not completed. It's state is {}", cachedJobId, cachedJob.getStatus
                        ());
                return Collections.emptyMap();
            }

            if (!CacheService.jobsEqual(job, cachedJob, fileConf)) {
                logger.debug("Cached job {} is not equal to current job {}", cachedJobId, job.getId());
                return Collections.emptyMap();
            }

            return cachedJob.getOutputs();
        } catch (Exception e) {
            logger.warn(String.format("Unable to copy cached outputs for job cached id %s", cachedUUID), e);
        }

        return Collections.emptyMap();
    }

    @Override
    public boolean isCacheEnabled() {
        return configuration.getBoolean("cache.enabled", false);
    }

    @Override
    public boolean isMockWorkerEnabled() {
        return configuration.getBoolean("executor.mock_worker", false);
    }
}
