package com.piimask.app.batch;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 실행 한 번 동안 reader·writer·listener 가 공유하는 상태.
 * 건수는 대상 커밋이 끝난 청크만 더한다. 스텝은 한 스레드에서 돌므로 동기화는 중지 플래그만 필요하다.
 */
final class RunState {

    /** 원천 한 행: PK 문자열과 SELECT 순서대로의 값(PK, COPY, MASK) */
    record SourceRow(String pk, Object[] values) {
    }

    /** 마스킹을 마친 한 행: INSERT 순서대로의 값과 탐지 결과 요약(원문 없음) */
    record MaskedRow(String pk, Object[] values, boolean hasPii, int spans) {
    }

    final long jobId;
    final long execId;
    final AtomicBoolean stopFlag;

    long read;
    long maskedRows;
    long spanCount;
    long written;
    String watermark;

    RunState(long jobId, long execId, AtomicBoolean stopFlag, String watermark) {
        this.jobId = jobId;
        this.execId = execId;
        this.stopFlag = stopFlag;
        this.watermark = watermark;
    }
}
