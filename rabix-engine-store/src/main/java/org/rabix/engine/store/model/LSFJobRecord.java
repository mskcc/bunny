package org.rabix.engine.store.model;

import java.time.LocalDateTime;
import java.util.UUID;

public class LSFJobRecord extends TimestampedModel {
    private UUID jobId;
    private long lsfId;

    public LSFJobRecord(LocalDateTime createdAt, LocalDateTime modifiedAt, UUID jobId, long lsfId) {
        super(createdAt, modifiedAt);
        this.jobId = jobId;
        this.lsfId = lsfId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public long getLsfId() {
        return lsfId;
    }

    public void setLsfId(long lsfId) {
        this.lsfId = lsfId;
    }

    @Override
    public String toString() {
        return "LSFJobRecord{" +
                "jobId=" + jobId +
                ", lsfId=" + lsfId +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LSFJobRecord that = (LSFJobRecord) o;

        if (lsfId != that.lsfId) return false;
        return jobId.equals(that.jobId);
    }

    @Override
    public int hashCode() {
        int result = jobId.hashCode();
        result = 31 * result + (int) (lsfId ^ (lsfId >>> 32));
        return result;
    }
}
