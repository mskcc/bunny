package org.rabix.engine.store.postgres.jdbi;

import org.rabix.engine.store.postgres.jdbi.impl.*;
import org.rabix.engine.store.repository.TransactionHelper;
import org.skife.jdbi.v2.sqlobject.CreateSqlObject;
import org.skife.jdbi.v2.sqlobject.Transaction;

public abstract class JDBIRepositoryRegistry extends TransactionHelper {

  @CreateSqlObject
  public abstract JDBIAppRepository applicationRepository();

  @CreateSqlObject
  public abstract JDBIBackendRepository backendRepository();

  @CreateSqlObject
  public abstract JDBIDAGRepository dagRepository();

  @CreateSqlObject
  public abstract JDBIJobRepository jobRepository();

  @CreateSqlObject
  public abstract JDBIJobRecordRepository jobRecordRepository();

  @CreateSqlObject
  public abstract JDBILinkRecordRepository linkRecordRepository();

  @CreateSqlObject
  public abstract JDBIVariableRecordRepository variableRecordRepository();

  @CreateSqlObject
  public abstract JDBIContextRecordRepository contextRecordRepository();

  @CreateSqlObject
  public abstract JDBIJobStatsRecordRepository jobStatsRecordRepository();

  @CreateSqlObject
  public abstract JDBIEventRepository eventRepository();

  @CreateSqlObject
  public abstract JDBIIntermediaryFilesRepository intermediaryFilesRepository();

  @CreateSqlObject
  public abstract JDBILSFJobRepository lsfJobRepository();

  @Transaction
  public <Result> Result doInTransaction(TransactionCallback<Result> callback) throws Exception {
    return callback.call();
  }

}
