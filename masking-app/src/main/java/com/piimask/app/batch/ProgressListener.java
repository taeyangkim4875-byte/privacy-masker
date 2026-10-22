package com.piimask.app.batch;

import com.piimask.app.batch.RunState.MaskedRow;
import com.piimask.app.batch.RunState.SourceRow;
import com.piimask.app.job.JobRepository;
import org.springframework.batch.core.job.JobInterruptedException;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.StepInterruptionPolicy;
import org.springframework.batch.infrastructure.item.Chunk;

/**
 * 청크 경계 처리.
 * - afterChunk: 대상 커밋(writer) 직후, Batch 메타 커밋과 같은 H2 트랜잭션 안에서 워터마크·진행 건수를 저장
 * - checkInterrupted: 각 청크 시작 전 중지 요청을 확인해 Batch 표준 방식(terminateOnly)으로 멈춘다 → STOPPED
 */
final class ProgressListener implements ChunkListener<SourceRow, MaskedRow>, StepInterruptionPolicy {

    private final RunState state;
    private final JobRepository jobs;
    private final ExecutionRepository executions;

    ProgressListener(RunState state, JobRepository jobs, ExecutionRepository executions) {
        this.state = state;
        this.jobs = jobs;
        this.executions = executions;
    }

    @Override
    public void afterChunk(Chunk<MaskedRow> chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        try {
            jobs.saveWatermark(state.jobId, state.watermark);
            executions.progress(state.execId, state.read, state.maskedRows, state.spanCount, state.written, state.watermark);
        } catch (RuntimeException e) {
            // H2 오류 메시지에는 PK 값이 들어갈 수 있다
            throw SanitizedBatchException.of(e);
        }
    }

    @Override
    public void checkInterrupted(StepExecution stepExecution) throws JobInterruptedException {
        if (state.stopFlag.get()) {
            stepExecution.setTerminateOnly();
            throw new JobInterruptedException("stop requested");
        }
    }
}
