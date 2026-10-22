package com.piimask.app.job;

import com.piimask.app.batch.ExecutionView;

import java.util.List;
import java.util.Map;

public final class JobDtos {

    private JobDtos() {
    }

    /** mode: MASK(탐지·마스킹) / COPY(그대로 복사). entities: MASK 일 때 탐지할 유형 */
    public record ColumnSpec(String column, String mode, List<String> entities) {
    }

    public record JobRequest(String name, Long connectionId, String sourceTable, String pkColumn,
                             String targetTable, Integer chunkSize, String cron, Boolean enabled,
                             List<ColumnSpec> columns, Map<String, String> policies, Boolean createTarget) {
    }

    public record JobView(long id, String name, long connectionId, String connectionName,
                          String sourceTable, String pkColumn, String targetTable, int chunkSize,
                          String cron, boolean enabled, List<ColumnSpec> columns, Map<String, String> policies,
                          String watermark, boolean running, ExecutionView lastExecution) {
    }

    public record SpanView(int start, int end, String type, String label, String detector) {
    }

    public record PreviewCell(String column, String original, String masked, List<SpanView> spans) {
    }

    public record PreviewRow(String pk, List<PreviewCell> cells) {
    }

    public record PreviewResult(List<PreviewRow> rows, long elapsedMs, Map<String, Integer> countsByType) {
    }

    public record DdlView(String ddl, boolean exists, List<String> missingColumns) {
    }
}
