package org.rabix.awsbatch.mapper;

import com.google.inject.Inject;
import org.apache.commons.configuration.Configuration;
import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.bindings.mapper.FilePathMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class LocalToS3PathMapper implements FilePathMapper {

    private final static Logger logger = LoggerFactory.getLogger(LocalToS3PathMapper.class);

    private final Configuration configuration;

    @Inject
    private LocalToS3PathMapper(Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    public String map(String path, Map<String, Object> config) throws FileMappingException {
        logger.info("Map local path {} to S3 path.", path);

        String baseExecutionDir = configuration.getString("backend.execution.directory");
        String awsOutputLocation = configuration.getString("rabix.aws.output.location");
        return Paths.get(awsOutputLocation).resolve(relativePath(baseExecutionDir, path)).toString();
    }

    private static String relativePath(String basePath, String localPath) {
        if (localPath.startsWith(basePath)) {
            Path bp = Paths.get(basePath);
            Path lp = Paths.get(localPath);
            return bp.relativize(lp).toString();
        }
        return localPath;
    }
}
