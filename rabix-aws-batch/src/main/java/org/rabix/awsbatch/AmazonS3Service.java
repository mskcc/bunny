package org.rabix.awsbatch;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;
import com.google.inject.Inject;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.IOUtils;
import org.rabix.awsbatch.mapper.LocalToS3PathMapper;
import org.rabix.awsbatch.mapper.S3ToLocalPathMapper;
import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.common.service.download.DownloadService;
import org.rabix.common.service.download.DownloadServiceException;
import org.rabix.common.service.upload.UploadService;
import org.rabix.common.service.upload.UploadServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

public class AmazonS3Service implements DownloadService, UploadService {

    private final static Logger logger = LoggerFactory.getLogger(AmazonS3Service.class);

    private final String bucket;
    private final AmazonS3 s3Client;
    public final static String S3Prefix = "s3://";


    private final S3ToLocalPathMapper s3ToLocalPathMapper;
    private final LocalToS3PathMapper localToS3PathMapper;

    @Inject
    private AmazonS3Service(Configuration configuration, S3ToLocalPathMapper s3ToLocalPathMapper, LocalToS3PathMapper localToS3PathMapper) {
        this.bucket = configuration.getString("aws.s3.bucket");
        this.s3ToLocalPathMapper = s3ToLocalPathMapper;
        this.localToS3PathMapper = localToS3PathMapper;

        String awsKey = configuration.getString("aws.access.key.id");
        String awsSecret = configuration.getString("aws.secret.access.key");
        BasicAWSCredentials credentials = new BasicAWSCredentials(awsKey, awsSecret);

        this.s3Client = AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_1).withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
    }

    @Override
    public void download(File workingDir, DownloadResource downloadResource, Map<String, Object> config) throws DownloadServiceException {
        logger.info("Downloading {}", downloadResource.getPath());
        try {
            String localPath = s3ToLocalPathMapper.map(downloadResource.getPath(), config);

            String s3Location = downloadResource.getPath();

            if (!s3Location.startsWith(S3Prefix)) {
                return;
            }
            String bucket = getBucket(s3Location);
            String s3Path = getS3Path(s3Location);

            S3Object o = s3Client.getObject(new GetObjectRequest(bucket, s3Path));

            File file = new File(localPath);

            try (S3ObjectInputStream s3is = o.getObjectContent(); FileOutputStream fos = new FileOutputStream(file)) {
                IOUtils.copy(s3is, fos);
            } catch (IOException e) {
                logger.error("Failed to download {}", downloadResource.getPath());
                throw new DownloadServiceException("Failed to download " + downloadResource.getPath(), e);
            }
        } catch (FileMappingException e) {
            logger.error("Failed to translate S3 to local path", e);
            throw new DownloadServiceException("Failed to translate S3 to local path", e);
        }
    }

    @Override
    public void download(File workingDir, Set<DownloadResource> downloadResources, Map<String, Object> config) throws DownloadServiceException {
        for (DownloadResource path : downloadResources) {
            download(workingDir, path, config);
        }
    }

    @Override
    public void upload(File file, File executionDirectory, boolean wait, boolean create, Map<String, Object> config) throws UploadServiceException {
        logger.info("Uploading {}", file.getAbsolutePath());
        try {
            String s3Location = localToS3PathMapper.map(file.getAbsolutePath(), config);
            String bucket = getBucket(s3Location);
            String s3Path = getS3Path(s3Location);
            TransferManager tm = TransferManagerBuilder.standard().withS3Client(s3Client).build();
            Upload upload = tm.upload(bucket, s3Path, file);
            upload.waitForCompletion();
        } catch (AmazonServiceException e) {
            throw new UploadServiceException(e);
        } catch (SdkClientException e) {
            throw new UploadServiceException(e);
        } catch (AmazonClientException e) {
            throw new UploadServiceException(e);
        } catch (InterruptedException e) {
            throw new UploadServiceException(e);
        } catch (FileMappingException e) {
            throw new UploadServiceException(e);
        }
    }

    @Override
    public void upload(Set<File> files, File executionDirectory, boolean wait, boolean create, Map<String, Object> config)
            throws UploadServiceException {
        for (File file : files) {
            upload(file, executionDirectory, wait, create, config);
        }
    }

    private String getBucket(String location) {
        String bucket = location.replace(S3Prefix, "");
        bucket = bucket.split("/")[0];
        return bucket;
    }

    private String getS3Path(String location) {
        String path = location.replace(S3Prefix, "");
        path = String.join("/", Arrays.copyOfRange(path.split("/"), 1, path.split("/").length));
        return path;
    }

}

