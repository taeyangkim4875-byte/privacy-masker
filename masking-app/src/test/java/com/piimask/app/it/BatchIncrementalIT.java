package com.piimask.app.it;

import com.piimask.app.batch.ExecutionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 3) 같은 작업을 다시 실행하면 워터마크 뒤에 새로 들어온 행만 처리한다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "piimask.data-dir=./build/it-data/incremental")
class BatchIncrementalIT extends BatchItSupport {

    private static final String SRC = "it_src_incr";
    private static final String TGT = "it_tgt_incr";
    private static final int NEW_ROWS = 250;

    @AfterEach
    void cleanup() {
        db.dropUnlessKept(SRC, TGT);
    }

    @Test
    void rerun_processes_only_new_rows() throws InterruptedException {
        db.drop(TGT);
        db.createSource(SRC, 1);
        long jobId = createJob("it-incr", registerConnection("it-incr"), SRC, TGT, 500);
        assertEquals("SUCCESS", runToEnd(jobId).status());
        long maxBefore = db.maxId(SRC);
        assertEquals(maxBefore, watermark(jobId));

        Timestamp t0 = db.maxMaskedAt(TGT);
        Thread.sleep(1_100); // masked_at 은 초 단위: 이후 기록은 t0 보다 커진다
        db.exec("INSERT INTO " + ItDb.t(SRC) + " (call_id, seq, speaker, utterance, created_at) "
                + "SELECT CONCAT(call_id, '-N'), seq, speaker, utterance, NOW() FROM " + ItDb.t(SRC)
                + " WHERE utterance IS NOT NULL ORDER BY id LIMIT " + NEW_ROWS);
        long maxAfter = db.maxId(SRC);

        ExecutionView second = runToEnd(jobId);
        assertEquals("SUCCESS", second.status(), () -> "증분 실행 실패: " + second.errorMessage());
        assertEquals(NEW_ROWS, second.readCount(), "새 행만 읽음");
        assertEquals(String.valueOf(maxBefore), second.startPk(), "start_pk = 이전 워터마크");
        assertEquals(String.valueOf(maxAfter), second.endPk(), "end_pk = 새 MAX(id)");
        assertEquals(maxAfter, watermark(jobId));
        assertEquals(NEW_ROWS, db.scalar("SELECT COUNT(*) FROM " + ItDb.t(TGT) + " WHERE masked_at > ?", t0),
                "기존 행은 다시 쓰지 않음");
        assertFullyMasked(jobId, SRC, TGT);

        ExecutionView third = runToEnd(jobId);
        assertEquals("SUCCESS", third.status());
        assertEquals(0, third.readCount(), "새 행이 없으면 0건");
        assertEquals(maxAfter, watermark(jobId), "워터마크 그대로");
    }
}
