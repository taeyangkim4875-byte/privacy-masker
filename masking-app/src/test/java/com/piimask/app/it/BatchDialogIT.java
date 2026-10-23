package com.piimask.app.it;

import com.piimask.app.batch.ExecutionView;
import com.piimask.app.job.JobDef;
import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.job.JobDtos.DialogSpec;
import com.piimask.app.job.JobDtos.JobRequest;
import com.piimask.core.DetectionContext;
import com.piimask.core.dialog.DialogTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 화자 문맥: 배치 결과 = 엔진을 통화별 문맥과 함께 다시 계산한 결과, 실행 경계에 걸친 통화도 이어 붙인다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "piimask.data-dir=./build/it-data/dialog")
class BatchDialogIT extends BatchItSupport {

    private static final String SRC = "it_src_dialog";
    private static final String TGT = "it_tgt_dialog";

    @AfterEach
    void cleanup() {
        db.dropUnlessKept(SRC, TGT);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.piimask.app.job.PreviewService previews;

    private long createDialogJob(String name, long connId, int chunk) {
        List<ColumnSpec> cols = List.of(
                new ColumnSpec("call_id", JobDef.COPY, List.of()),
                new ColumnSpec("seq", JobDef.COPY, List.of()),
                new ColumnSpec("speaker", JobDef.COPY, List.of()),
                new ColumnSpec("utterance", JobDef.MASK, List.of()),
                new ColumnSpec("created_at", JobDef.COPY, List.of()));
        return jobService.create(new JobRequest(name, connId, SRC, "id", TGT, chunk, null, true, cols, null, true,
                new DialogSpec("call_id", "speaker", "AGENT"))).id();
    }

    /** 대상 utterance 가 문맥 포함 재계산 결과와 같은지. 문맥 때문에 달라진 행 수를 돌려준다(id 만 보고) */
    private int assertMaskedWithContext(long jobId) {
        JobDef job = jobRepo.get(jobId);
        var entities = JobDef.entitiesOf(job.maskColumns().get(0));
        DialogTracker tracker = new DialogTracker(10_000);
        List<String> mismatched = new ArrayList<>();
        int contextual = 0;
        for (Object[] r : db.dialogPairs(SRC, TGT)) {
            String original = (String) r[3];
            DetectionContext ctx = tracker.next((String) r[1], "AGENT".equalsIgnoreCase((String) r[2]), original);
            String expected = original == null ? null : engine.mask(original, entities, job.policies(), job.hashSalt(), ctx).masked();
            if (original != null && !expected.equals(engine.mask(original, entities, job.policies(), job.hashSalt()).masked())) {
                contextual++;
            }
            if (expected == null ? r[4] != null : !expected.equals(r[4])) {
                mismatched.add((String) r[0]);
            }
        }
        assertTrue(mismatched.isEmpty(), "문맥 마스킹 불일치 " + mismatched.size() + "건, 앞쪽 id: "
                + mismatched.subList(0, Math.min(10, mismatched.size())));
        return contextual;
    }

    @Test
    void full_run_applies_dialog_context() {
        db.drop(TGT);
        db.createSource(SRC, 1);
        long jobId = createDialogJob("it-dialog", registerConnection("it-dialog"), 300);
        ExecutionView e = runToEnd(jobId);
        assertEquals("SUCCESS", e.status(), () -> "실행 실패: " + e.errorMessage());
        assertSamePks(SRC, TGT);
        int contextual = assertMaskedWithContext(jobId);
        assertTrue(contextual > 0, "시드의 \"네 {이름}\" 답이 문맥으로 가려져야 함");
    }

    /** 미리보기도 같은 문맥 규칙: 앞쪽 행을 통화별 문맥과 함께 다시 계산한 결과와 같다. 설정 저장·해제 왕복 */
    @Test
    void preview_uses_context_and_settings_round_trip() {
        db.drop(TGT);
        db.createSource(SRC, 1);
        long jobId = createDialogJob("it-dialog-p", registerConnection("it-dialog-p"), 300);
        JobDef job = jobRepo.get(jobId);
        assertEquals(new DialogSpec("call_id", "speaker", "AGENT"), job.dialog());

        var preview = previews.preview(job, 200);
        var entities = JobDef.entitiesOf(job.maskColumns().get(0));
        DialogTracker tracker = new DialogTracker(10_000);
        List<Object[]> src = db.dialogPairsSource(SRC, 200);
        assertEquals(src.size(), preview.rows().size());
        int contextual = 0;
        for (int i = 0; i < src.size(); i++) {
            Object[] r = src.get(i);
            DetectionContext ctx = tracker.next((String) r[1], "AGENT".equalsIgnoreCase((String) r[2]), (String) r[3]);
            String expected = engine.mask((String) r[3], entities, job.policies(), job.hashSalt(), ctx).masked();
            assertEquals(expected, preview.rows().get(i).cells().get(0).masked(), "미리보기 불일치 id=" + r[0]);
            contextual += ctx.nameExpected() ? 1 : 0;
        }
        assertTrue(contextual > 0, "앞쪽 200행에 이름 질문 다음 답이 있어야 함");

        // 설정 해제: 모두 비우면 사용 안 함
        List<ColumnSpec> cols = job.columns();
        jobService.update(jobId, new JobRequest(job.name(), job.connectionId(), SRC, "id", TGT, 300, null, true, cols, null, false,
                new DialogSpec("", "", "")));
        assertEquals(null, jobRepo.get(jobId).dialog());
    }

    @Test
    void context_carries_over_run_boundary_via_source_lookup() {
        db.drop(TGT);
        db.createSource(SRC, 1);
        String call = "C-DIALOG-BOUNDARY";
        db.exec("INSERT INTO " + ItDb.t(SRC) + " (call_id, seq, speaker, utterance, created_at) VALUES (?, 1, 'AGENT', ?, NOW())",
                call, "본인 확인을 위해 성함 말씀해 주시겠어요?");
        long jobId = createDialogJob("it-dialog-b", registerConnection("it-dialog-b"), 500);
        assertEquals("SUCCESS", runToEnd(jobId).status());

        // 다음 실행의 첫 행: 같은 통화의 고객 답(질문은 이전 실행에서 처리됨)
        db.exec("INSERT INTO " + ItDb.t(SRC) + " (call_id, seq, speaker, utterance, created_at) VALUES (?, 2, 'CUSTOMER', ?, NOW())",
                call, "네 김가짜");
        ExecutionView second = runToEnd(jobId);
        assertEquals("SUCCESS", second.status(), () -> "실행 실패: " + second.errorMessage());
        assertEquals(1, second.readCount());
        long masked = db.scalar("SELECT COUNT(*) FROM " + ItDb.t(TGT) + " WHERE call_id = ? AND seq = 2 AND utterance LIKE '%가짜%'", call);
        assertEquals(0, masked, "실행 경계 너머의 이름 질문 문맥으로 답의 이름을 가려야 함");
        assertFalse(db.scalar("SELECT COUNT(*) FROM " + ItDb.t(TGT) + " WHERE call_id = ? AND seq = 2", call) == 0);
        assertMaskedWithContext(jobId);
    }
}
