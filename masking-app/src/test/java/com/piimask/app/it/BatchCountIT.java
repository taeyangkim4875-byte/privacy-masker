package com.piimask.app.it;

import com.piimask.app.batch.ExecutionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1) 마스킹 작업 실행 후 대상 테이블 건수·내용이 원천과 일치한다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "piimask.data-dir=./build/it-data/count")
class BatchCountIT extends BatchItSupport {

    private static final String SRC = "it_src_count";
    private static final String TGT = "it_tgt_count";

    @AfterEach
    void cleanup() {
        db.dropUnlessKept(SRC, TGT);
    }

    @Test
    void full_run_copies_every_row_and_masks_it() {
        db.drop(TGT);
        db.createSource(SRC, 1);
        long total = db.count(SRC);

        long jobId = createJob("it-count", registerConnection("it-count"), SRC, TGT, 500);
        ExecutionView e = runToEnd(jobId);

        assertEquals("SUCCESS", e.status(), () -> "실행 실패: " + e.errorMessage());
        assertEquals(total, e.readCount(), "read_count");
        assertTrue(e.maskedRows() > 0 && e.spanCount() > 0, "개인정보가 있는 행·스팬이 집계되어야 함");
        assertEquals(String.valueOf(db.maxId(SRC)), e.endPk(), "실행 이력 end_pk");
        assertEquals(db.maxId(SRC), watermark(jobId), "워터마크 = 원천 MAX(id)");
        assertFullyMasked(jobId, SRC, TGT);
    }
}
