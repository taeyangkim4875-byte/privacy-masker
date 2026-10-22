package com.piimask.app.it;

import com.piimask.app.batch.ExecutionView;
import com.piimask.app.support.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T8: Spring Batch 메타 상태가 실행 이력과 짝이 맞고, 실패 메시지에 원인 예외가 남지 않는다 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "piimask.data-dir=./build/it-data/metadata")
class BatchMetadataIT extends BatchItSupport {

    private static final String SRC = "it_src_meta";
    private static final String TGT = "it_tgt_meta";
    private static final String TGT_FAIL = "it_tgt_meta_fail";
    private static final String SRC_STR = "it_src_strpk";
    private static final String TGT_STR = "it_tgt_strpk";

    @Autowired
    JdbcTemplate meta;

    @AfterEach
    void cleanup() {
        db.dropUnlessKept(SRC, TGT, TGT_FAIL, SRC_STR, TGT_STR);
    }

    /** job_execution 이력 하나에 대응하는 Batch 잡·스텝 실행 */
    private Map<String, Object> batchJob(long execId) {
        return meta.queryForMap("SELECT e.STATUS, e.EXIT_CODE, s.STATUS AS STEP_STATUS, s.EXIT_MESSAGE AS STEP_EXIT_MESSAGE "
                + "FROM BATCH_JOB_EXECUTION e JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
                + "JOIN BATCH_STEP_EXECUTION s ON s.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
                + "WHERE p.PARAMETER_NAME = 'execId' AND p.PARAMETER_VALUE = ?", String.valueOf(execId));
    }

    @Test
    void batch_status_matches_execution_history(CapturedOutput output) {
        db.drop(TGT);
        db.drop(TGT_FAIL);
        db.createSource(SRC, 3);
        long connId = registerConnection("it-meta");

        // 성공
        long okJob = createJob("it-meta-ok", connId, SRC, TGT, 500);
        ExecutionView ok = runToEnd(okJob);
        assertEquals("SUCCESS", ok.status());
        assertEquals("COMPLETED", batchJob(ok.id()).get("STATUS"));

        // 실행 중 재시작 요청은 409, 중지하면 STOPPED (청크를 작게 해 시간을 번다)
        long stopJob = createJob("it-meta-stop", connId, SRC, TGT + "_s", 20);
        long stopExec = runner.start(stopJob, "MANUAL");
        ApiException dup = assertThrows(ApiException.class, () -> runner.start(stopJob, "MANUAL"));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        assertTrue(runner.requestStop(stopJob));
        ExecutionView stopped = await(stopExec, stopJob);
        assertEquals("STOPPED", stopped.status());
        assertEquals("STOPPED", batchJob(stopExec).get("STATUS"));
        db.drop(TGT + "_s");

        // 실패: 마스킹 컬럼이 너무 짧은 대상 → FAILED. 메타 DB 에 드라이버 예외(원인)가 남지 않아야 한다
        db.exec("CREATE TABLE " + ItDb.t(TGT_FAIL) + " (id BIGINT NOT NULL PRIMARY KEY, call_id VARCHAR(32), seq INT, "
                + "speaker VARCHAR(10), utterance VARCHAR(5), created_at DATETIME, "
                + "masked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) DEFAULT CHARSET=utf8mb4");
        long failJob = createJob("it-meta-fail", connId, SRC, TGT_FAIL, 500);
        ExecutionView failed = runToEnd(failJob);
        assertEquals("FAILED", failed.status());
        assertNotNull(failed.errorMessage());
        assertTrue(failed.errorMessage().contains("SQLState=22001"), "실행 이력 오류는 기존 형식(safeMessage): " + failed.errorMessage());
        Map<String, Object> b = batchJob(failed.id());
        assertEquals("FAILED", b.get("STATUS"));
        // EXIT_MESSAGE 는 2500자에서 잘리므로 원인 체인 전체는 로그로 본다
        String exit = String.valueOf(b.get("STEP_EXIT_MESSAGE"));
        assertTrue(exit.startsWith("org.springframework.batch.core.step.FatalStepExecutionException"), "스텝 실패 기록 형식");
        assertFalse(exit.contains("org.mariadb.jdbc"), "드라이버 예외가 Batch 메타 DB 에 남음");
        String logs = output.getAll();
        assertTrue(logs.contains("Caused by: com.piimask.app.batch.SanitizedBatchException"), "Batch 로그의 원인은 정화된 예외여야 함");
        assertFalse(logs.contains("Caused by: java.sql") || logs.contains("Caused by: org.mariadb"), "드라이버 예외 원문이 로그에 남음");
    }

    /** T10: 배치 시작·완료 감사 기록은 실행한 사용자로, 스케줄 실행은 system:scheduler 로 남는다 */
    @Test
    void job_run_audit_records_the_actor() {
        db.drop(TGT);
        db.createSource(SRC, 1);
        long jobId = createJob("it-meta-actor", registerConnection("it-meta-actor"), SRC, TGT, 500);

        var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "it-operator", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
        long manual;
        try {
            manual = runner.start(jobId, "MANUAL");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        await(manual, jobId);
        assertEquals(java.util.List.of("it-operator", "it-operator"), actors(jobId, manual), "시작·완료 모두 실행한 사용자");

        runner.startIfIdle(jobId, "CRON");
        ExecutionView cron = executions.last(jobId);
        await(cron.id(), jobId);
        assertEquals(java.util.List.of("system:scheduler", "system:scheduler"), actors(jobId, cron.id()));
    }

    private java.util.List<String> actors(long jobId, long execId) {
        return meta.queryForList("SELECT actor FROM audit_log WHERE action = 'JOB_RUN' AND target LIKE ? ORDER BY id",
                String.class, "job#" + jobId + "%exec#" + execId + "%");
    }

    /** 여러 작업 동시 시작: Batch 인스턴스 생성이 서로 막지 않고 모두 성공 */
    @Test
    void concurrent_starts_all_succeed() {
        db.createSource(SRC, 1);
        long connId = registerConnection("it-meta-conc");
        int n = 4;
        long[] jobIds = new long[n];
        long[] execIds = new long[n];
        for (int i = 0; i < n; i++) {
            db.drop(TGT + "_c" + i);
            jobIds[i] = createJob("it-meta-conc-" + i, connId, SRC, TGT + "_c" + i, 200);
        }
        for (int i = 0; i < n; i++) {
            execIds[i] = runner.start(jobIds[i], "MANUAL");
        }
        try {
            for (int i = 0; i < n; i++) {
                ExecutionView e = await(execIds[i], jobIds[i]);
                assertEquals("SUCCESS", e.status(), "동시 실행 " + i + ": " + e.errorMessage());
                assertSamePks(SRC, TGT + "_c" + i);
            }
        } finally {
            for (int i = 0; i < n; i++) {
                db.dropUnlessKept(TGT + "_c" + i);
            }
        }
    }

    /**
     * 2^53 근처 BIGINT PK 가 숫자 바인딩 경로로 빠짐없이 돈다.
     * 참고: MariaDB 11.4 는 정수 컬럼과 문자열도 정확히 비교하므로 문자열 바인딩으로도 이 테스트는 통과한다(확인함).
     */
    @Test
    void bigint_pk_near_2_pow_53_is_processed_without_gaps() {
        db.drop(TGT);
        db.drop(SRC);
        db.exec("CREATE TABLE " + ItDb.t(SRC) + " LIKE " + ItDb.SEED_TABLE);
        long base = 9_007_199_254_740_980L; // 2^53 = 9007199254740992
        for (int i = 0; i < 35; i++) {
            db.exec("INSERT INTO " + ItDb.t(SRC) + " (id, call_id, seq, speaker, utterance, created_at) "
                    + "VALUES (?, 'IT-BIG', ?, 'CUSTOMER', ?, NOW())", base + i, i, "연락처는 010-1234-" + (5000 + i) + "이요.");
        }
        long jobId = createJob("it-bigpk", registerConnection("it-bigpk"), SRC, TGT, 10);
        ExecutionView e = runToEnd(jobId);
        assertEquals("SUCCESS", e.status(), () -> "실행 실패: " + e.errorMessage());
        assertEquals(35, e.readCount(), "모든 행을 정확히 한 번씩");
        assertEquals(String.valueOf(base + 34), e.endPk());
        assertFullyMasked(jobId, SRC, TGT);
    }

    /** 문자열 PK: 문자열 순서(K10 < K9)로도 keyset 이 빠짐없이 돈다 */
    @Test
    void string_pk_is_processed_in_string_order_without_gaps() {
        db.drop(TGT_STR);
        db.drop(SRC_STR);
        db.exec("CREATE TABLE " + ItDb.t(SRC_STR) + " (id VARCHAR(20) NOT NULL PRIMARY KEY, call_id VARCHAR(32) NOT NULL, "
                + "seq INT NOT NULL, speaker VARCHAR(10) NOT NULL, utterance TEXT NULL, created_at DATETIME NOT NULL) "
                + "DEFAULT CHARSET=utf8mb4");
        db.exec("INSERT INTO " + ItDb.t(SRC_STR) + " SELECT CONCAT('K', id), call_id, seq, speaker, utterance, created_at FROM "
                + ItDb.SEED_TABLE + " WHERE call_id NOT LIKE '%-B%'");
        long jobId = createJob("it-strpk", registerConnection("it-strpk"), SRC_STR, TGT_STR, 128);
        ExecutionView e = runToEnd(jobId);
        assertEquals("SUCCESS", e.status(), () -> "실행 실패: " + e.errorMessage());
        assertEquals(db.count(SRC_STR), e.readCount());
        assertFullyMasked(jobId, SRC_STR, TGT_STR);
    }
}
