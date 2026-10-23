package com.piimask.app.it;

import com.piimask.app.batch.ExecutionRepository;
import com.piimask.app.batch.ExecutionView;
import com.piimask.app.batch.JobRunner;
import com.piimask.app.connection.ConnectionDtos.ConnectionRequest;
import com.piimask.app.connection.ConnectionService;
import com.piimask.app.dict.EngineProvider;
import com.piimask.app.job.JobDef;
import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.job.JobDtos.JobRequest;
import com.piimask.app.job.JobRepository;
import com.piimask.app.job.JobService;
import com.piimask.core.MaskingEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 배치 통합 테스트 공통 기반. 하위 클래스는 각자
 * {@code @SpringBootTest(webEnvironment = NONE, properties = "piimask.data-dir=./build/it-data/<이름>")}
 * 로 메타 DB(H2)·마스터키를 분리한다.
 */
@Tag("integration")
abstract class BatchItSupport {

    static final Duration TIMEOUT = Duration.ofMinutes(10);
    static ItDb db;

    @Autowired
    ConnectionService connections;
    @Autowired
    JobService jobService;
    @Autowired
    JobRepository jobRepo;
    @Autowired
    JobRunner runner;
    @Autowired
    ExecutionRepository executions;
    /** 배치와 같은 엔진(사용자 사전 반영). IT 동안 사전은 바뀌지 않는다 */
    MaskingEngine engine;

    @Autowired
    void engines(EngineProvider engines) {
        this.engine = engines.current();
    }

    private static String seedSum;

    @BeforeAll
    static void requireDb() {
        db = ItDb.fromEnv();
        seedSum = db.seedChecksum();
    }

    @AfterAll
    static void seedUntouched() {
        assertEquals(seedSum, db.seedChecksum(), "시드 테이블이 바뀌었습니다");
    }

    long registerConnection(String name) {
        return connections.create(new ConnectionRequest(name, db.host, db.port, db.database, db.user, db.password, null)).id();
    }

    /** stt_call_history 구조: utterance 만 MASK(전 유형), 나머지 COPY. 대상 테이블은 앱이 생성 */
    long createJob(String name, long connId, String src, String tgt, int chunk) {
        List<ColumnSpec> cols = List.of(
                new ColumnSpec("call_id", JobDef.COPY, List.of()),
                new ColumnSpec("seq", JobDef.COPY, List.of()),
                new ColumnSpec("speaker", JobDef.COPY, List.of()),
                new ColumnSpec("utterance", JobDef.MASK, List.of()),
                new ColumnSpec("created_at", JobDef.COPY, List.of()));
        return jobService.create(new JobRequest(name, connId, src, "id", tgt, chunk, null, true, cols, null, true, null)).id();
    }

    /** 실행하고 끝까지 기다린다. 실행 전후 원천 체크섬이 같아야 한다(원천은 SELECT 만) */
    ExecutionView runToEnd(long jobId) {
        String src = jobRepo.get(jobId).sourceTable();
        String before = db.checksum(src);
        ExecutionView e = await(runner.start(jobId, "MANUAL"), jobId);
        assertEquals(before, db.checksum(src), "배치가 원천 테이블을 바꿨습니다");
        return e;
    }

    /** 실행이 끝나고(RUNNING 아님) 러너에서도 빠질 때까지 기다린다 */
    ExecutionView await(long execId, long jobId) {
        return awaitUntil(execId, jobId, e -> false);
    }

    /** cond 를 만족하거나 실행이 끝나면 그 시점의 이력을 돌려준다 */
    ExecutionView awaitUntil(long execId, long jobId, Predicate<ExecutionView> cond) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ExecutionView e = executions.get(execId);
            if (e != null && (cond.test(e) || (!"RUNNING".equals(e.status()) && !runner.isRunning(jobId)))) {
                return e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                fail("대기 중 인터럽트");
            }
        }
        fail("job#" + jobId + " exec#" + execId + " 가 " + TIMEOUT + " 안에 끝나지 않았습니다");
        return null;
    }

    long watermark(long jobId) {
        String w = jobRepo.watermark(jobId);
        return w == null ? 0 : Long.parseLong(w);
    }

    /** 원천과 대상의 PK 집합이 같은지(양방향 차집합 0) */
    void assertSamePks(String src, String tgt) {
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM " + ItDb.t(src) + " s LEFT JOIN " + ItDb.t(tgt)
                + " g ON g.id = s.id WHERE g.id IS NULL"), "대상에 누락된 원천 행");
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM " + ItDb.t(tgt) + " g LEFT JOIN " + ItDb.t(src)
                + " s ON s.id = g.id WHERE s.id IS NULL"), "원천에 없는 대상 행");
        assertEquals(db.count(src), db.count(tgt), "원천·대상 건수");
    }

    /**
     * 전체 검증: PK 집합, COPY 컬럼 동일, MASK 컬럼은 엔진으로 다시 계산한 결과와 동일.
     * 불일치는 id 만 보고한다(원문 미출력).
     */
    void assertFullyMasked(long jobId, String src, String tgt) {
        assertSamePks(src, tgt);
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM " + ItDb.t(src) + " s JOIN " + ItDb.t(tgt) + " g ON g.id = s.id "
                + "WHERE NOT (s.call_id <=> g.call_id AND s.seq <=> g.seq AND s.speaker <=> g.speaker "
                + "AND s.created_at <=> g.created_at)"), "COPY 컬럼 불일치");
        // 엔진이 아무것도 바꾸지 않아도 아래 비교는 통과하므로, 실제로 바뀐 행이 있는지 따로 본다
        assertTrue(db.scalar("SELECT COUNT(*) FROM " + ItDb.t(src) + " s JOIN " + ItDb.t(tgt) + " g ON g.id = s.id "
                + "WHERE NOT (s.utterance <=> g.utterance)") > 0, "마스킹으로 바뀐 행이 하나도 없음");

        JobDef job = jobRepo.get(jobId);
        var entities = JobDef.entitiesOf(job.maskColumns().get(0));
        List<String> mismatched = new ArrayList<>();
        for (Object[] row : db.pairs(src, tgt)) {
            String original = (String) row[1];
            String actual = (String) row[2];
            String expected = original == null ? null
                    : engine.mask(original, entities, job.policies(), job.hashSalt()).masked();
            if (expected == null ? actual != null : !expected.equals(actual)) {
                mismatched.add((String) row[0]);
            }
        }
        assertTrue(mismatched.isEmpty(), "마스킹 결과 불일치 " + mismatched.size() + "건, 앞쪽 id: "
                + mismatched.subList(0, Math.min(10, mismatched.size())));
    }
}
