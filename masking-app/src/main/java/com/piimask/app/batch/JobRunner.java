package com.piimask.app.batch;

import com.piimask.app.connection.ConnectionDtos.ColumnInfo;
import com.piimask.app.connection.ConnectionService;
import com.piimask.app.connection.DataSourceRegistry;
import com.piimask.app.connection.SchemaService;
import com.piimask.app.job.JobDef;
import com.piimask.app.job.JobRepository;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.AuditService;
import com.piimask.app.support.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 증분 마스킹 배치 실행기(Spring Batch 6).
 *
 * 처리 방식(keyset 청크, {@link MaskingJobFactory}):
 * 1. 원천에서 PK > 워터마크 인 행을 PK 순으로 chunkSize 만큼 읽는다(원천은 SELECT 만).
 * 2. MASK 컬럼을 엔진으로 마스킹, COPY 컬럼은 그대로.
 * 3. 대상 테이블에 INSERT ... ON DUPLICATE KEY UPDATE 로 청크 단위 커밋.
 * 4. 커밋 후 워터마크를 청크의 마지막 PK 로 저장(Batch 메타와 같은 H2 트랜잭션).
 *
 * 3과 4 사이에 중단되어도 다음 실행에서 같은 청크를 다시 upsert 하므로 중복·누락이 없다.
 * 실행마다 새 JobInstance(jobId, execId)를 만든다. "이어서 실행"은 Batch 재시작이 아니라 워터마크로 한다.
 * 화면이 보는 실행 이력은 계속 job_execution 테이블이다.
 */
@Component
public class JobRunner implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository jobs;
    private final ExecutionRepository executions;
    private final ConnectionService connections;
    private final DataSourceRegistry registry;
    private final SchemaService schema;
    private final MaskingJobFactory jobFactory;
    private final JobOperator jobOperator;
    private final AuditService audit;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    public JobRunner(JobRepository jobs, ExecutionRepository executions, ConnectionService connections,
                     DataSourceRegistry registry, SchemaService schema, MaskingJobFactory jobFactory,
                     JobOperator jobOperator, AuditService audit) {
        this.jobs = jobs;
        this.executions = executions;
        this.connections = connections;
        this.registry = registry;
        this.schema = schema;
        this.jobFactory = jobFactory;
        this.jobOperator = jobOperator;
        this.audit = audit;
    }

    public boolean isRunning(long jobId) {
        return running.containsKey(jobId);
    }

    /** 실행 시작. 이미 실행 중이면 409 */
    public long start(long jobId, String trigger) {
        JobDef job = jobs.get(jobId);
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        if (running.putIfAbsent(jobId, stopFlag) != null) {
            throw ApiException.conflict("이미 실행 중입니다");
        }
        long execId;
        try {
            execId = executions.start(jobId, trigger, jobs.watermark(jobId));
        } catch (RuntimeException e) {
            running.remove(jobId);
            throw e;
        }
        audit.log("JOB_RUN", "job#" + jobId + " " + job.name() + " exec#" + execId + " (" + trigger + ")", "STARTED");
        pool.submit(() -> {
            try {
                execute(job, execId, stopFlag);
            } finally {
                running.remove(jobId);
            }
        });
        return execId;
    }

    /** 스케줄러용: 실행 중이면 건너뛴다 */
    public void startIfIdle(long jobId, String trigger) {
        if (isRunning(jobId)) {
            log.info("job#{} 실행 중이라 이번 스케줄은 건너뜁니다", jobId);
            return;
        }
        try {
            start(jobId, trigger);
        } catch (ApiException e) {
            log.warn("job#{} 스케줄 실행 실패: {}", jobId, e.getMessage());
        }
    }

    public boolean requestStop(long jobId) {
        AtomicBoolean f = running.get(jobId);
        if (f == null) {
            return false;
        }
        f.set(true);
        audit.log("JOB_STOP", "job#" + jobId, "REQUESTED");
        return true;
    }

    private void execute(JobDef job, long execId, AtomicBoolean stopFlag) {
        RunState state = new RunState(job.id(), execId, stopFlag, jobs.watermark(job.id()));
        try {
            DataSource ds = registry.get(connections.secret(job.connectionId()));
            JobParameters params = new JobParametersBuilder()
                    .addLong("jobId", job.id())
                    .addLong("execId", execId)
                    .toJobParameters();
            JobExecution je = jobOperator.start(jobFactory.create(job, state, ds, integerPk(job)), params);
            executions.progress(execId, state.read, state.maskedRows, state.spanCount, state.written, state.watermark);
            if (je.getStatus() == BatchStatus.COMPLETED) {
                executions.finish(execId, "SUCCESS", null);
                audit.log("JOB_RUN", "job#" + job.id() + " exec#" + execId, "SUCCESS");
            } else if (je.getStatus() == BatchStatus.STOPPED) {
                executions.finish(execId, "STOPPED", "사용자 요청으로 중지. 다시 실행하면 이어서 진행합니다");
                audit.log("JOB_RUN", "job#" + job.id() + " exec#" + execId, "STOPPED");
            } else {
                fail(job, execId, state, failureMessage(je));
            }
        } catch (Exception e) {
            fail(job, execId, state, Errors.safeMessage(e));
        }
    }

    private void fail(JobDef job, long execId, RunState state, String msg) {
        log.error("job#{} exec#{} 실패", job.id(), execId);
        executions.progress(execId, state.read, state.maskedRows, state.spanCount, state.written, state.watermark);
        executions.finish(execId, "FAILED", msg);
        audit.log("JOB_RUN", "job#" + job.id() + " exec#" + execId, "FAILED");
    }

    /** 실행 이력용 메시지: SanitizedBatchException 이 들고 온 safeMessage(기존 형식) */
    private static String failureMessage(JobExecution je) {
        for (Throwable t : je.getAllFailureExceptions()) {
            for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
                if (c instanceof SanitizedBatchException s) {
                    return s.historyMessage();
                }
            }
        }
        List<Throwable> all = je.getAllFailureExceptions();
        return all.isEmpty() ? "배치 실패(" + je.getStatus() + ")" : Errors.safeMessage(all.get(0));
    }

    /** 정수형 PK 면 워터마크를 숫자로 바인딩한다. 조회에 실패하면 기존 방식(문자열)으로 */
    private boolean integerPk(JobDef job) {
        try {
            return schema.columns(job.connectionId(), job.sourceTable()).stream()
                    .filter(c -> c.name().equalsIgnoreCase(job.pkColumn()))
                    .map(ColumnInfo::dataType)
                    .anyMatch(KeysetSourceReader.INTEGER_TYPES::contains);
        } catch (RuntimeException e) {
            log.warn("job#{} PK 타입 조회 실패, 문자열로 비교합니다: {}", job.id(), Errors.codeOnly(e));
            return false;
        }
    }

    @Override
    public void destroy() {
        running.values().forEach(f -> f.set(true));
        pool.shutdown();
        try {
            // 진행 중인 청크가 커밋까지 끝나도록 잠시 기다린다
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
