package com.piimask.app.batch;

import com.piimask.app.connection.ConnectionService;
import com.piimask.app.connection.DataSourceRegistry;
import com.piimask.app.job.JobDef;
import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.job.JobRepository;
import com.piimask.app.job.TargetTableService;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.AuditService;
import com.piimask.app.support.Errors;
import com.piimask.app.support.Sql;
import com.piimask.core.EntityType;
import com.piimask.core.MaskingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 증분 마스킹 배치.
 *
 * 처리 방식(keyset 청크):
 * 1. 원천에서 PK > 워터마크 인 행을 PK 순으로 chunkSize 만큼 읽는다(원천은 SELECT 만).
 * 2. MASK 컬럼을 엔진으로 마스킹, COPY 컬럼은 그대로.
 * 3. 대상 테이블에 INSERT ... ON DUPLICATE KEY UPDATE 로 청크 단위 커밋.
 * 4. 커밋 후 워터마크를 청크의 마지막 PK 로 저장.
 *
 * 3과 4 사이에 중단되어도 다음 실행에서 같은 청크를 다시 upsert 하므로 중복·누락이 없다.
 * Spring Batch 6 로의 전환은 계획서 T8 에서 진행한다(구조는 reader/processor/writer 그대로 대응).
 */
@Component
public class JobRunner implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository jobs;
    private final ExecutionRepository executions;
    private final ConnectionService connections;
    private final DataSourceRegistry registry;
    private final MaskingEngine engine;
    private final AuditService audit;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    public JobRunner(JobRepository jobs, ExecutionRepository executions, ConnectionService connections,
                     DataSourceRegistry registry, MaskingEngine engine, AuditService audit) {
        this.jobs = jobs;
        this.executions = executions;
        this.connections = connections;
        this.registry = registry;
        this.engine = engine;
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
        List<ColumnSpec> maskCols = job.maskColumns();
        List<ColumnSpec> copyCols = job.copyColumns();
        List<Set<EntityType>> maskEntities = maskCols.stream().map(JobDef::entitiesOf).toList();

        List<String> selectCols = new ArrayList<>();
        selectCols.add(job.pkColumn());
        copyCols.forEach(c -> selectCols.add(c.column()));
        maskCols.forEach(c -> selectCols.add(c.column()));

        String selectFirst = "SELECT " + Sql.qList(selectCols) + " FROM " + Sql.q(job.sourceTable())
                + " ORDER BY " + Sql.q(job.pkColumn()) + " LIMIT ?";
        String selectNext = "SELECT " + Sql.qList(selectCols) + " FROM " + Sql.q(job.sourceTable())
                + " WHERE " + Sql.q(job.pkColumn()) + " > ? ORDER BY " + Sql.q(job.pkColumn()) + " LIMIT ?";

        List<String> insertCols = new ArrayList<>(selectCols);
        StringBuilder upd = new StringBuilder();
        for (int i = 1; i < insertCols.size(); i++) {
            upd.append(Sql.q(insertCols.get(i))).append(" = VALUES(").append(Sql.q(insertCols.get(i))).append("), ");
        }
        upd.append(Sql.q(TargetTableService.MASKED_AT)).append(" = NOW()");
        String insert = "INSERT INTO " + Sql.q(job.targetTable()) + " (" + Sql.qList(insertCols) + ", "
                + Sql.q(TargetTableService.MASKED_AT) + ") VALUES (" + Sql.placeholders(insertCols.size())
                + ", NOW()) ON DUPLICATE KEY UPDATE " + upd;

        long read = 0;
        long maskedRows = 0;
        long spanCount = 0;
        long written = 0;
        String watermark = jobs.watermark(job.id());

        try {
            DataSource ds = registry.get(connections.secret(job.connectionId()));
            while (true) {
                if (stopFlag.get()) {
                    executions.progress(execId, read, maskedRows, spanCount, written, watermark);
                    executions.finish(execId, "STOPPED", "사용자 요청으로 중지. 다시 실행하면 이어서 진행합니다");
                    audit.log("JOB_RUN", "job#" + job.id() + " exec#" + execId, "STOPPED");
                    return;
                }
                int fetched = 0;
                long chunkMasked = 0;
                long chunkSpans = 0;
                String lastPk = watermark;
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    try (PreparedStatement sel = c.prepareStatement(watermark == null ? selectFirst : selectNext);
                         PreparedStatement ins = c.prepareStatement(insert)) {
                        int p = 1;
                        if (watermark != null) {
                            sel.setString(p++, watermark);
                        }
                        sel.setInt(p, job.chunkSize());
                        try (ResultSet rs = sel.executeQuery()) {
                            while (rs.next()) {
                                fetched++;
                                int col = 1;
                                ins.setObject(col, rs.getObject(col));
                                lastPk = rs.getString(col);
                                col++;
                                for (int i = 0; i < copyCols.size(); i++, col++) {
                                    ins.setObject(col, rs.getObject(col));
                                }
                                boolean rowHasPii = false;
                                for (int i = 0; i < maskCols.size(); i++, col++) {
                                    String text = rs.getString(col);
                                    if (text == null) {
                                        ins.setString(col, null);
                                        continue;
                                    }
                                    MaskingEngine.Result r = engine.mask(text, maskEntities.get(i), job.policies(), job.hashSalt());
                                    if (!r.spans().isEmpty()) {
                                        rowHasPii = true;
                                        chunkSpans += r.spans().size();
                                    }
                                    ins.setString(col, r.masked());
                                }
                                if (rowHasPii) {
                                    chunkMasked++;
                                }
                                ins.addBatch();
                            }
                        }
                        if (fetched > 0) {
                            ins.executeBatch();
                        }
                        c.commit();
                    } catch (SQLException | RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                }
                if (fetched == 0) {
                    break;
                }
                read += fetched;
                written += fetched;
                maskedRows += chunkMasked;
                spanCount += chunkSpans;
                watermark = lastPk;
                jobs.saveWatermark(job.id(), watermark);
                executions.progress(execId, read, maskedRows, spanCount, written, watermark);
                if (fetched < job.chunkSize()) {
                    break;
                }
            }
            executions.finish(execId, "SUCCESS", null);
            audit.log("JOB_RUN", "job#" + job.id() + " exec#" + execId, "SUCCESS");
        } catch (Exception e) {
            String msg = Errors.safeMessage(e);
            log.error("job#{} exec#{} 실패: {}", job.id(), execId, e.getClass().getName());
            executions.progress(execId, read, maskedRows, spanCount, written, watermark);
            executions.finish(execId, "FAILED", msg);
            audit.log("JOB_RUN", "job#" + job.id() + " exec#" + execId, "FAILED");
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
