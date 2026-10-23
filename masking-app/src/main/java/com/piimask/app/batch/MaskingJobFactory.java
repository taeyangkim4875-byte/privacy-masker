package com.piimask.app.batch;

import com.piimask.app.batch.RunState.MaskedRow;
import com.piimask.app.batch.RunState.SourceRow;
import com.piimask.app.job.JobDef;
import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.job.JobRepository;
import com.piimask.core.EntityType;
import com.piimask.app.dict.EngineProvider;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 작업 정의(JobDef)로 실행마다 Spring Batch Job 을 만든다.
 * 단일 chunk 스텝: KeysetSourceReader → MaskingProcessor → UpsertTargetWriter, 청크 크기 = 작업의 chunkSize.
 * 스텝 트랜잭션은 메타 DB(H2) 것이고, 대상 MariaDB 커밋은 writer 가 청크마다 직접 한다.
 */
@Component
class MaskingJobFactory {

    private final org.springframework.batch.core.repository.JobRepository batchRepository;
    private final PlatformTransactionManager metaTx;
    private final JobRepository jobs;
    private final ExecutionRepository executions;
    private final EngineProvider engines;

    MaskingJobFactory(org.springframework.batch.core.repository.JobRepository batchRepository,
                      // 메타 DB(H2) 트랜잭션 매니저. 나중에 다른 매니저가 생겨도 이것에 묶이도록 이름으로 받는다
                      @Qualifier("transactionManager") PlatformTransactionManager metaTx,
                      JobRepository jobs, ExecutionRepository executions,
                      EngineProvider engines) {
        this.batchRepository = batchRepository;
        this.metaTx = metaTx;
        this.jobs = jobs;
        this.executions = executions;
        this.engines = engines;
    }

    static String jobName(long jobId) {
        return "mask-job-" + jobId;
    }

    Job create(JobDef job, RunState state, DataSource source, boolean integerPk) {
        List<ColumnSpec> copyCols = job.copyColumns();
        List<ColumnSpec> maskCols = job.maskColumns();
        List<Set<EntityType>> maskEntities = maskCols.stream().map(JobDef::entitiesOf).toList();

        // 값 순서: [PK, COPY..., MASK...] — SELECT 와 INSERT 가 같은 순서를 쓴다
        List<String> cols = new ArrayList<>();
        cols.add(job.pkColumn());
        copyCols.forEach(c -> cols.add(c.column()));
        maskCols.forEach(c -> cols.add(c.column()));
        int firstMaskIndex = 1 + copyCols.size();

        KeysetSourceReader reader = new KeysetSourceReader(source, job.sourceTable(), job.pkColumn(), cols,
                firstMaskIndex, integerPk, job.chunkSize(), state.watermark);
        // 실행을 만들 때의 엔진(사용자 사전 포함)을 끝까지 쓴다. 도중에 사전이 바뀌어도 한 실행 안의 결과는 섞이지 않는다
        MaskingProcessor processor = new MaskingProcessor(engines.current(), copyCols.size(), maskEntities,
                job.policies(), job.hashSalt());
        UpsertTargetWriter writer = new UpsertTargetWriter(source, job.targetTable(), cols, state);
        ProgressListener progress = new ProgressListener(state, jobs, executions);

        Step step = new StepBuilder("mask", batchRepository)
                .<SourceRow, MaskedRow>chunk(job.chunkSize())
                .transactionManager(metaTx)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(progress)
                .interruptionPolicy(progress)
                .build();
        return new JobBuilder(jobName(job.id()), batchRepository).start(step).build();
    }
}
