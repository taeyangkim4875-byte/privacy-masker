package com.piimask.app.batch;

public record ExecutionView(long id, long jobId, String jobName, String trigger, String status,
                            String startedAt, String endedAt, long readCount, long maskedRows,
                            long spanCount, long writtenCount, String startPk, String endPk,
                            String errorMessage) {
}
