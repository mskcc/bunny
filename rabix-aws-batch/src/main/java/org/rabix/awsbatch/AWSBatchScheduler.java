package org.rabix.awsbatch;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.batch.AWSBatch;
import com.amazonaws.services.batch.AWSBatchClientBuilder;
import com.amazonaws.services.batch.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.IOUtils;
import org.rabix.backend.api.WorkerService;
import org.rabix.backend.api.callback.WorkerStatusCallback;
import org.rabix.backend.api.callback.WorkerStatusCallbackException;
import org.rabix.backend.api.engine.EngineStub;
import org.rabix.backend.api.engine.EngineStubLocal;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.helper.URIHelper;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.requirement.ResourceRequirement;
import org.rabix.common.helper.JSONHelper;
import org.rabix.transport.backend.Backend;
import org.rabix.transport.backend.impl.BackendLocal;
import org.rabix.transport.mechanism.TransportPluginException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AWSBatchScheduler implements WorkerService {

    private final static Logger logger = LoggerFactory.getLogger(AWSBatchScheduler.class);

    @BindingAnnotation
    @Target({ java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.PARAMETER,
            java.lang.annotation.ElementType.METHOD })
    @Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface AWSBatchWorker {
    }

    private static final String APP_CWL_FILENAME = "app.cwl";
    private static final String INPUTS_FILENAME = "inputs.json";

    private static final String CWL_OUT_JSON_FILENAME = "cwl.output.json";

    private AmazonS3 s3Client;
    private AWSBatch batchClient;

    private String jobQueue;
    @com.google.inject.Inject
    private WorkerStatusCallback statusCallback;
    private String worker_docker_image;

    @Inject
    private Configuration configuration;

    private String bucket;

    private EngineStub<?, ?, ?> engineStub;
    private final ScheduledExecutorService scheduledTaskChecker = Executors.newScheduledThreadPool(1);

    private final Set<Job> pending = new HashSet<>();

    public AWSBatchScheduler() {
        logger.info("AWSBatch backend created");
    }

    @Override
    public void start(Backend backend) {
        this.bucket = configuration.getString("aws.s3.bucket");

        String awsKey = configuration.getString("aws.access.key.id");
        String awsSecret = configuration.getString("aws.secret.access.key");

        this.jobQueue = configuration.getString("aws.batch.jobqueue.arn");
        this.worker_docker_image = configuration.getString("aws.batch.docker_image");

        BasicAWSCredentials credentials = new BasicAWSCredentials(awsKey, awsSecret);

        this.s3Client = AmazonS3ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withRegion(Regions.US_EAST_1).build();
        this.batchClient = AWSBatchClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials)).withRegion(Regions.US_EAST_1).build();

        try {
            engineStub = new EngineStubLocal((BackendLocal) backend, this, configuration);
            engineStub.start();
        } catch (TransportPluginException e) {
            logger.error("Failed to initialize AWS backend", e);
            throw new RuntimeException("Failed to initialize AWS backend", e);
        }

        this.scheduledTaskChecker.scheduleAtFixedRate(() -> {
            try {
                synchronized (pending) {

                    ListJobsRequest jobRequestSuccessful = new ListJobsRequest();
                    jobRequestSuccessful.setJobQueue(this.jobQueue);
                    jobRequestSuccessful.setJobStatus(com.amazonaws.services.batch.model.JobStatus.SUCCEEDED);
                    ListJobsResult successfulJobs = this.batchClient.listJobs(jobRequestSuccessful);

                    ListJobsRequest jobRequestFailed = new ListJobsRequest();
                    jobRequestFailed.setJobQueue(this.jobQueue);
                    jobRequestFailed.setJobStatus(com.amazonaws.services.batch.model.JobStatus.FAILED);
                    ListJobsResult failedJobs = this.batchClient.listJobs(jobRequestFailed);

                    checkStatus(successfulJobs, failedJobs);
                }
            } catch (Exception e) {
                logger.error("Failed to poll", e);
            }
        }, 0, 30, TimeUnit.SECONDS);

    }

    private synchronized void checkStatus(ListJobsResult successfulJobs, ListJobsResult failedJobs) {
        for (Iterator<Job> iterator = pending.iterator(); iterator.hasNext();) {
            Job job = (Job) iterator.next();

            logger.info("Polling status for {}", job.getId());

            for (JobSummary successfulJob : successfulJobs.getJobSummaryList()) {
                if (successfulJob.getJobName().equals(job.getId().toString())) {
                    String cwlOutputPath = String.format("%s/%s/%s", getJobPath(job),
                            job.getId().toString(), CWL_OUT_JSON_FILENAME);
                    try {
                        S3Object cwlObject = s3Client.getObject(bucket, cwlOutputPath);
                        String cwlResult = IOUtils.toString(cwlObject.getObjectContent());
                        Map<String, Object> cwlMap = JSONHelper.readMap(cwlResult);

                        job = Job.cloneWithOutputs(job, cwlMap);
                        job = Job.cloneWithMessage(job, "Success");
                        job = Job.cloneWithStatus(job, Job.JobStatus.COMPLETED);
                        engineStub.send(job);
                    } catch (Exception e) {
                        // failed
                        logger.info("Task {} has failed.", job.getId());
                        job = Job.cloneWithStatus(job, Job.JobStatus.FAILED);
                        engineStub.send(job);
                    }
                    iterator.remove();
                    break;
                }
            }

            for (JobSummary failedJob : failedJobs.getJobSummaryList()) {
                if (failedJob.getJobName().equals(job.getId().toString())) {
                    // failed
                    logger.info("Task {} has failed.", job.getId());
                    job = Job.cloneWithStatus(job, Job.JobStatus.FAILED);
                    engineStub.send(job);
                    iterator.remove();
                    break;
                }
            }
        }
    }

    // Refactor this - use describejobs instead
    private synchronized boolean checkIfJobIsAlreadySubmitted(Job job, String jobQueue) {
        ListJobsRequest jobRequestSubmitted = new ListJobsRequest();
        jobRequestSubmitted.setJobQueue(jobQueue);
        jobRequestSubmitted.setJobStatus(com.amazonaws.services.batch.model.JobStatus.SUBMITTED);
        ListJobsResult submittedJobs = this.batchClient.listJobs(jobRequestSubmitted);

        for (JobSummary submittedJob : submittedJobs.getJobSummaryList()) {
            if (submittedJob.getJobName().equals(job.getId().toString())) {
                logger.info("Job already in state SUBMITTED " + job.getId());
                return true;
            }
        }

        ListJobsRequest jobRequestPending = new ListJobsRequest();
        jobRequestPending.setJobQueue(jobQueue);
        jobRequestPending.setJobStatus(com.amazonaws.services.batch.model.JobStatus.PENDING);
        ListJobsResult pendingJobs = this.batchClient.listJobs(jobRequestPending);

        for (JobSummary pendingJob : pendingJobs.getJobSummaryList()) {
            if (pendingJob.getJobName().equals(job.getId().toString())) {
                logger.info("Job already in state PENDING " + job.getId());
                return true;
            }
        }

        ListJobsRequest jobRequestRunnable = new ListJobsRequest();
        jobRequestRunnable.setJobQueue(jobQueue);
        jobRequestRunnable.setJobStatus(com.amazonaws.services.batch.model.JobStatus.RUNNABLE);
        ListJobsResult runnableJobs = this.batchClient.listJobs(jobRequestRunnable);

        for (JobSummary runnableJob : runnableJobs.getJobSummaryList()) {
            if (runnableJob.getJobName().equals(job.getId().toString())) {
                logger.info("Job already in state RUNNABLE " + job.getId());
                return true;
            }
        }

        ListJobsRequest jobRequestStarting = new ListJobsRequest();
        jobRequestStarting.setJobQueue(jobQueue);
        jobRequestStarting.setJobStatus(com.amazonaws.services.batch.model.JobStatus.STARTING);
        ListJobsResult startingJobs = this.batchClient.listJobs(jobRequestStarting);

        for (JobSummary startingJob : startingJobs.getJobSummaryList()) {
            if (startingJob.getJobName().equals(job.getId().toString())) {
                logger.info("Job already in state STARTING " + job.getId());
                return true;
            }
        }

        ListJobsRequest jobRequestRunning = new ListJobsRequest();
        jobRequestRunning.setJobQueue(jobQueue);
        jobRequestRunning.setJobStatus(com.amazonaws.services.batch.model.JobStatus.RUNNING);
        ListJobsResult runningJobs = this.batchClient.listJobs(jobRequestRunning);

        for (JobSummary runningJob : runningJobs.getJobSummaryList()) {
            if (runningJob.getJobName().equals(job.getId().toString())) {
                logger.info("Job already in state RUNNING " + job.getId());
                return true;
            }
        }

        ListJobsRequest jobRequestSuccessful = new ListJobsRequest();
        jobRequestSuccessful.setJobQueue(jobQueue);
        jobRequestSuccessful.setJobStatus(com.amazonaws.services.batch.model.JobStatus.SUCCEEDED);
        ListJobsResult successfulJobs = this.batchClient.listJobs(jobRequestSuccessful);

        for (JobSummary successfulJob : successfulJobs.getJobSummaryList()) {
            if (successfulJob.getJobName().equals(job.getId().toString())) {
                logger.info("Job already in state SUCCEEDED " + job.getId());
                return true;
            }
        }
        return false;
    }

    private synchronized String getJobPath(Job job) {
        return "jobs/" + job.getRootId().toString() + "/" + job.getName().toString();
    }

    private synchronized String getAppPath(Job job) {
        return String.format("%s/%s.%s", getJobPath(job), job.getId().toString(), APP_CWL_FILENAME);
    }

    private synchronized String getInputsPath(Job job) {
        return String.format("%s/%s.%s", getJobPath(job), job.getId().toString(), INPUTS_FILENAME);
    }

    @Override
    public void submit(Job job, UUID rootId) {
        try {
            String jobPath = getJobPath(job);
            String appPath = getAppPath(job);

            // Check for cwl.output.json before submitting
            if (checkIfJobIsAlreadySubmitted(job, this.jobQueue)) {
                job = Job.cloneWithStatus(job, Job.JobStatus.RUNNING);
                this.pending.add(job);
                return;
            }

            String cwlOutputPath = String.format("%s/%s/%s", getJobPath(job),
                    job.getId().toString(), CWL_OUT_JSON_FILENAME);

            s3Client.putObject(bucket, appPath, URIHelper.getData(job.getApp()));
            logger.info("{} uploaded.", appPath);

            String inputsPath = getInputsPath(job);
            s3Client.putObject(bucket, inputsPath, JSONHelper.writeObject(job.getInputs()));
            logger.info("{} uploaded.", inputsPath);


            String jobDefinitionName = job.getId().toString();

            String jobArn = describeJob(job, jobDefinitionName, jobPath);

            SubmitJobRequest jobRequest = new SubmitJobRequest();
            jobRequest.withJobName(jobDefinitionName);
            jobRequest.withJobDefinition(jobArn);

            String queue = this.jobQueue;
            logger.info("Sending job to queue: " + queue);

            jobRequest.withJobQueue(queue);
            SubmitJobResult submittedJob = batchClient.submitJob(jobRequest);
            job = Job.cloneWithStatus(job, Job.JobStatus.RUNNING);
            engineStub.send(job);
            this.pending.add(job);
        } catch (Exception e) {
            logger.error("Failed to submit job " + job.getName(), e);
            job = Job.cloneWithStatus(job, Job.JobStatus.FAILED);
            try {
                statusCallback.onJobFailed(job);
            } catch (WorkerStatusCallbackException e1) {
                logger.error("Failed to update status of the job " + job.getName(), e);
            }
            engineStub.send(job);
        }
    }

    public synchronized String describeJob(Job job, String jobDefinitionName, String jobPath) {

        String appPath = getAppPath(job);
        String inputsPath = getInputsPath(job);

        Long cpu = (long) 2;
        Long mem = (long) 2048;

        try {
            Bindings binding = BindingsFactory.create(job.getApp());
            ResourceRequirement resource = binding.getResourceRequirement(job);
            cpu = resource.getCpuMin();
            mem = resource.getMemMinMB();
        } catch (Exception e) {
            logger.warn("Failed to extract resource requirements for job: " + job.getId());
        }

        RegisterJobDefinitionRequest jobDefinition = new RegisterJobDefinitionRequest();
        jobDefinition.setJobDefinitionName(jobDefinitionName);
        ContainerProperties containerProperties = new ContainerProperties();
        containerProperties.setImage(this.worker_docker_image);

        List<String> command = new ArrayList<String>();
        command.add("/opt/rabix-cli-1.0.3/rabix");
        command.add("--basedir");
        command.add("/tmp");
        command.add("--directory-name");
        command.add(job.getId().toString());
        command.add("s3://" + this.bucket + "/" + appPath);
        command.add("s3://" + this.bucket + "/" +inputsPath);
        command.add("--aws-output-location");
        command.add(this.bucket + "/" + jobPath);
        command.add("-v");

        containerProperties.setCommand(command);
        containerProperties.setVcpus(Math.toIntExact(cpu));
        containerProperties.setMemory(Math.toIntExact(mem));

        Volume dockerVolume = new Volume();
        dockerVolume.setName("docker_sock");
        Host dockerHost = new Host();
        dockerHost.setSourcePath("/var/run/docker.sock");
        dockerVolume.setHost(dockerHost);
        Volume tmpVolume = new Volume();
        tmpVolume.setName("tmp");
        Host tmpHost = new Host();
        tmpHost.setSourcePath("/tmp");
        tmpVolume.setHost(tmpHost);
        List<Volume> volumes = new ArrayList<Volume>();
        volumes.add(dockerVolume);
        volumes.add(tmpVolume);
        containerProperties.setVolumes(volumes);

        MountPoint dockerMountPoint = new MountPoint();
        dockerMountPoint.setContainerPath("/var/run/docker.sock");
        dockerMountPoint.setSourceVolume("docker_sock");

        MountPoint tmpMountPoint = new MountPoint();
        tmpMountPoint.setContainerPath("/tmp");
        tmpMountPoint.setSourceVolume("tmp");

        List<MountPoint> mountPoints = new ArrayList<MountPoint>();
        mountPoints.add(dockerMountPoint);
        mountPoints.add(tmpMountPoint);
        containerProperties.setMountPoints(mountPoints);

        // Put retry strategy in configuration
        RetryStrategy retryStrategy = new RetryStrategy();
        retryStrategy.setAttempts(5);

        jobDefinition.setContainerProperties(containerProperties);
        jobDefinition.setRetryStrategy(retryStrategy);

        jobDefinition.setType(JobDefinitionType.Container);
        RegisterJobDefinitionResult result = batchClient.registerJobDefinition(jobDefinition);
        return result.getJobDefinitionArn();
    }

    @Override
    public void cancel(List<UUID> ids, UUID rootId) {

    }

    @Override
    public void freeResources(UUID rootId, Map<String, Object> config) {

    }

    @Override
    public void shutdown(Boolean stopEverything) {

    }

    @Override
    public boolean isRunning(UUID id, UUID rootId) {
        return false;
    }

    @Override
    public Map<String, Object> getResult(UUID id, UUID rootId) {
        return null;
    }

    @Override
    public boolean isStopped() {
        return false;
    }

    @Override
    public Job.JobStatus findStatus(UUID id, UUID rootId) {
        return null;
    }

    @Override
    public String getType() {
        return "AWS";
    }
}
