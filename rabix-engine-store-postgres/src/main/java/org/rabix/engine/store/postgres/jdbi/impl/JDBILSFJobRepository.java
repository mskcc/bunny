package org.rabix.engine.store.postgres.jdbi.impl;

import org.rabix.engine.store.repository.LSFJobRepository;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.sqlobject.stringtemplate.UseStringTemplate3StatementLocator;

import java.util.UUID;

@UseStringTemplate3StatementLocator
public interface JDBILSFJobRepository extends LSFJobRepository {
    @Override
    @SqlUpdate("insert into lsf_job (job_id, lsf_id) values (:jobId,:lsfId)")
    void insert(@Bind("jobId") UUID jobId, @Bind("lsfId") long lsfId);

    @Override
    @SqlQuery("select lsf_id from lsf_job where job_id=:jobId")
    Long get(@Bind("jobId") UUID jobId);

    @Override
    @SqlUpdate("delete from lsf_job where job_id=:jobId")
    void delete(@Bind("jobId") UUID jobId);
}
