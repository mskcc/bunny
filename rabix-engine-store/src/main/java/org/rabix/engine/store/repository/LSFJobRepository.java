package org.rabix.engine.store.repository;

import java.util.UUID;

public interface LSFJobRepository {
    void insert(UUID jobId, long lsfId);

    Long get(UUID jobId);

    void delete(UUID jobId);
}
