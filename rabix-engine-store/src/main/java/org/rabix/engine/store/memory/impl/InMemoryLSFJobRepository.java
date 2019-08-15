package org.rabix.engine.store.memory.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import org.rabix.common.helper.JSONHelper;
import org.rabix.engine.store.repository.LSFJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryLSFJobRepository implements LSFJobRepository {
    private final static Logger logger = LoggerFactory.getLogger(InMemoryLSFJobRepository.class);

    private Map<UUID, Long> jobId2lsfJobId;

    public InMemoryLSFJobRepository() {
        jobId2lsfJobId = new ConcurrentHashMap<>();

        String jobRepo = "lsfRepo";

        deserialize(jobRepo);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            try {
                if(!jobId2lsfJobId.isEmpty())
                    serialize(jobRepo);
            }
            catch(IOException ex) {
                ex.printStackTrace();
            }
        }
        ));
    }

    private void serialize(String jobRepo) throws IOException {
        logger.info("Serializing ({}): {}", this, jobId2lsfJobId);
        String s1 = JSONHelper.writeObject(jobId2lsfJobId);
        Files.write(Paths.get(jobRepo), s1.getBytes());
    }

    private void deserialize(String jobRepo)  {
        try {
            if(Files.exists(Paths.get(jobRepo))) {
                byte[] bytes = Files.readAllBytes(Paths.get(jobRepo));
                String s = new String(bytes);

                jobId2lsfJobId = JSONHelper.readObject(s, new TypeReference<Map<UUID, Long>>(){});
            }
            else {
                System.out.println("File doesn't exist: " + jobRepo);

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void insert(UUID jobId, long lsfId) {
        jobId2lsfJobId.put(jobId, lsfId);
    }

    @Override
    public Long get(UUID jobId) {
        return jobId2lsfJobId.get(jobId);
    }

    @Override
    public void delete(UUID jobId) {
        jobId2lsfJobId.remove(jobId);
    }
}
