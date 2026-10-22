package com.piimask.app.it;

import com.piimask.app.batch.ExecutionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2) 실행 중 중지 → 재실행 시 중복·누락이 없다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "piimask.data-dir=./build/it-data/restart")
class BatchRestartIT extends BatchItSupport {

    private static final String SRC = "it_src_restart";
    private static final String TGT = "it_tgt_restart";

    @AfterEach
    void cleanup() {
        db.dropUnlessKept(SRC, TGT);
    }

    /** 약 10만 건. 30% 지점에서 사용자 중지 → 재실행 */
    @Test
    void stop_then_rerun_completes_without_duplicates_or_gaps() {
        db.drop(TGT);
        db.createSource(SRC, 31);
        long total = db.count(SRC);
        assertTrue(total >= 95_000, "10만 건 근처여야 합니다: " + total);

        long jobId = createJob("it-restart", registerConnection("it-restart"), SRC, TGT, 200);
        String srcSum = db.checksum(SRC);
        long exec1 = runner.start(jobId, "MANUAL");
        awaitUntil(exec1, jobId, e -> e.readCount() >= total * 3 / 10);
        assertTrue(runner.requestStop(jobId), "중지 요청 전에 실행이 이미 끝남(데이터를 늘리거나 chunk 를 줄일 것)");
        ExecutionView first = await(exec1, jobId);
        assertEquals(srcSum, db.checksum(SRC), "배치가 원천 테이블을 바꿨습니다");

        assertEquals("STOPPED", first.status(), "30% 지점에서 중지되어야 합니다(먼저 끝났다면 데이터가 너무 적음)");
        long wm = watermark(jobId);
        long written = db.count(TGT);
        assertTrue(written > 0 && written < total, "중지 시점에 일부만 처리: " + written + "/" + total);
        assertEquals(db.scalar("SELECT COUNT(*) FROM " + ItDb.t(SRC) + " WHERE id <= ?", wm), written,
                "중지 시점 대상 건수 = 원천에서 id <= 워터마크 건수");
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM " + ItDb.t(TGT) + " WHERE id > ?", wm),
                "워터마크 뒤의 행이 대상에 있으면 안 됨");
        assertEquals(written, first.readCount(), "1차 read_count");
        assertEquals(String.valueOf(wm), first.endPk(), "1차 end_pk = 워터마크");

        ExecutionView second = runToEnd(jobId);
        assertEquals("SUCCESS", second.status(), () -> "재실행 실패: " + second.errorMessage());
        assertEquals(String.valueOf(wm), second.startPk(), "재실행은 워터마크부터");
        assertEquals(total, first.readCount() + second.readCount(), "두 실행의 read_count 합 = 원천 건수(겹침·누락 없음)");
        assertFullyMasked(jobId, SRC, TGT);
    }

    /** 2b) 청크 커밋 후 워터마크 저장 전에 죽은 상황: 워터마크를 되감고 다시 돌려도 중복 없이 같은 결과 */
    @Test
    void rewound_watermark_reprocesses_idempotently() throws InterruptedException {
        db.drop(TGT);
        db.createSource(SRC, 1);
        long total = db.count(SRC);
        long jobId = createJob("it-rewind", registerConnection("it-rewind"), SRC, TGT, 500);
        assertEquals("SUCCESS", runToEnd(jobId).status());

        long mid = db.scalar("SELECT id FROM " + ItDb.t(SRC) + " ORDER BY id LIMIT 1 OFFSET ?", total / 2);
        long expectedRedo = db.scalar("SELECT COUNT(*) FROM " + ItDb.t(SRC) + " WHERE id > ?", mid);
        jobRepo.saveWatermark(jobId, String.valueOf(mid));
        Timestamp t0 = db.maxMaskedAt(TGT);
        Thread.sleep(1_100); // masked_at 은 초 단위: 이후 기록은 t0 보다 커진다

        ExecutionView redo = runToEnd(jobId);
        assertEquals("SUCCESS", redo.status(), () -> "재처리 실패: " + redo.errorMessage());
        assertEquals(expectedRedo, redo.readCount(), "되감은 지점 뒤만 다시 읽음");
        assertEquals(expectedRedo, db.scalar("SELECT COUNT(*) FROM " + ItDb.t(TGT) + " WHERE masked_at > ?", t0),
                "다시 쓴 행 = 되감은 지점 뒤 행");
        assertFullyMasked(jobId, SRC, TGT);
    }
}
