package org.rabix.engine.status;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.commons.lang3.StringUtils;
import org.rabix.backend.lsf.service.LSFWorkerServiceImpl;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.requirement.DockerContainerRequirement;
import org.rabix.bindings.model.requirement.Requirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LSFEngineStatusCallback implements EngineStatusCallback {
    private final static Logger logger = LoggerFactory.getLogger(LSFEngineStatusCallback.class);
    private EngineStatusCallback engineStatusCallback;

    @Inject
    public LSFEngineStatusCallback(@Named("Delegate") EngineStatusCallback engineStatusCallback) {
        this.engineStatusCallback = engineStatusCallback;
    }

    @Override
    public void onJobReady(Job job) throws EngineStatusCallbackException {
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
    }

    @Override
    public void onJobFailed(Job job) throws EngineStatusCallbackException {
        engineStatusCallback.onJobFailed(job);
        cleanup(job);
    }

    private void cleanup(Job job) {
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

    @Override
    public void onJobContainerReady(UUID id, UUID rootId) throws EngineStatusCallbackException {
        engineStatusCallback.onJobContainerReady(id, rootId);
    }

    @Override
    public void onJobRootCompleted(UUID rootId) throws EngineStatusCallbackException {
        engineStatusCallback.onJobRootCompleted(rootId);
    }

    @Override
    public void onJobRootPartiallyCompleted(UUID rootId, Map<String, Object> outputs, UUID producedBy) throws
            EngineStatusCallbackException {
        engineStatusCallback.onJobRootPartiallyCompleted(rootId, outputs, producedBy);
    }

    @Override
    public void onJobRootFailed(UUID rootId, String message) throws EngineStatusCallbackException {
        engineStatusCallback.onJobRootFailed(rootId, message);
    }

    @Override
    public void onJobRootAborted(UUID rootId) throws EngineStatusCallbackException {
        engineStatusCallback.onJobRootAborted(rootId);
    }
}
