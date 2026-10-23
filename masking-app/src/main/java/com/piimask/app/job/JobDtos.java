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

    /**
     * 대화 문맥(선택): 통화 ID·화자 컬럼과 상담사 화자 값. 셋 다 있으면 상담사 이름 질문 다음 고객 발화에 문맥을 준다.
     * 컬럼은 작업에 포함된 컬럼(복사·마스킹) 중에서 고른다. 질문 판별은 첫 마스킹 컬럼(발화) 값으로 한다.
     */
    public record DialogSpec(String callColumn, String speakerColumn, String agentValue) {
    }

    public record JobRequest(String name, Long connectionId, String sourceTable, String pkColumn,
                             String targetTable, Integer chunkSize, String cron, Boolean enabled,
                             List<ColumnSpec> columns, Map<String, String> policies, Boolean createTarget,
                             DialogSpec dialog) {
    }

    public record JobView(long id, String name, long connectionId, String connectionName,
                          String sourceTable, String pkColumn, String targetTable, int chunkSize,
                          String cron, boolean enabled, List<ColumnSpec> columns, Map<String, String> policies,
                          String watermark, boolean running, ExecutionView lastExecution, DialogSpec dialog) {
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
