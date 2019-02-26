package org.rabix.awsbatch.mapper;

import com.google.inject.Inject;
import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.bindings.mapper.FilePathMapper;
import org.rabix.executor.config.StorageConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class S3ToLocalPathMapper implements FilePathMapper {

    private final static Logger logger = LoggerFactory.getLogger(S3ToLocalPathMapper.class);

    private final StorageConfiguration storageConfig;

    @Inject
    private S3ToLocalPathMapper(StorageConfiguration storageConfig) {
        this.storageConfig = storageConfig;
    }

    @Override
    public String map(String path, Map<String, Object> config) throws FileMappingException {
        logger.info("Map S3 path {} to physical path.", path);
        try {
            if (path.startsWith("/")) {
                new File(path).getParentFile().mkdirs();
                return path;
            }
            File file = new File(storageConfig.getPhysicalExecutionBaseDir(), path);
            file.getParentFile().mkdirs();
            return file.getCanonicalPath();
        } catch (IOException e) {
            throw new FileMappingException(e);
        }
    }
}
