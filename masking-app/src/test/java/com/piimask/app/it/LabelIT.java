package com.piimask.app.it;

import com.piimask.app.dict.EngineProvider;
import com.piimask.app.label.LabelService;
import com.piimask.app.label.LabelService.AddRequest;
import com.piimask.app.label.LabelService.LabelView;
import com.piimask.app.label.LabelService.QueryRequest;
import com.piimask.app.support.ApiException;
import com.piimask.core.EntityType;
import com.piimask.core.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 교정 라벨: 원천 재조회로 위치 검증, 원문 미저장(메타 DB 덤프), 중복·범위·탐지기 검증, 원천 변경 시 무효, 예외 사전 추가 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "piimask.data-dir=./build/it-data/label")
class LabelIT extends BatchItSupport {

    private static final String SRC = "it_src_label";

    @Autowired
    LabelService labels;
    @Autowired
    JdbcTemplate meta;
    @Autowired
    EngineProvider engines;

    @AfterEach
    void cleanup() {
        db.dropUnlessKept(SRC);
        meta.update("DELETE FROM span_label");
        meta.update("DELETE FROM name_dictionary");
        engines.reload();
    }

    private AddRequest req(long conn, String pk, int s, int e, String label, String detector, boolean addException) {
        return new AddRequest(conn, SRC, "id", pk, "utterance", s, e, label, "NAME", detector, null, addException);
    }

    @Test
    void labels_store_positions_only_and_detect_source_changes() {
        db.createSource(SRC, 1);
        long conn = registerConnection("it-label");
        List<Object[]> rows = db.dialogPairsSource(SRC, 400);

        // 이름(규칙)으로 잡힌 행 하나, 단서 없는 "네 {이름}" 고객 행 하나
        Object[] hit = null;
        Span span = null;
        Object[] miss = null;
        for (Object[] r : rows) {
            String t = (String) r[3];
            List<Span> names = engine.detect(t, Set.of(EntityType.NAME));
            if (hit == null && !names.isEmpty() && names.get(0).end() - names.get(0).start() >= 3) {
                hit = r;
                span = names.get(0);
            }
            if (miss == null && "CUSTOMER".equals(r[2]) && t.startsWith("네 ") && names.isEmpty() && t.length() >= 5) {
                miss = r;
            }
        }
        assertNotNull(hit, "시드에서 이름 탐지 행을 찾지 못함");
        assertNotNull(miss, "시드에서 단서 없는 답 행을 찾지 못함");
        String hitText = (String) hit[3];
        String name = hitText.substring(span.start(), span.end());
        String missText = (String) miss[3];

        LabelView a0 = labels.add(req(conn, (String) hit[0], span.start(), span.end(), LabelService.NOT_PII, span.detector(), false));
        LabelView b = labels.add(new AddRequest(conn, SRC, "id", (String) miss[0], "utterance", 2, missText.length(),
                LabelService.MISSED, "NAME", "이름(규칙)", 7L, false));
        assertFalse(a0.stale() || b.stale());
        assertEquals(null, b.detector(), "놓침 라벨은 탐지기를 저장하지 않음");
        assertEquals(0, meta.queryForObject("SELECT COUNT(*) FROM name_dictionary", Integer.class), "체크하지 않으면 예외 사전에 넣지 않음");
        assertEquals(7L, meta.queryForObject("SELECT job_id FROM span_label WHERE id = ?", Long.class, b.id()));
        assertEquals(null, meta.queryForObject("SELECT job_id FROM span_label WHERE id = ?", Long.class, a0.id()));

        // 원문(라벨 위치의 글자)은 메타 DB 어디에도 없다
        String dump = String.join("\n", meta.queryForList("SCRIPT", String.class));
        assertFalse(dump.contains(name), "메타 DB 에 오탐 스팬 원문이 있음");
        assertFalse(dump.contains(missText.substring(2)), "메타 DB 에 놓침 스팬 원문이 있음");
        List<String> audit = meta.queryForList("SELECT target FROM audit_log WHERE action = 'LABEL_ADD' ORDER BY id DESC LIMIT 2", String.class);
        assertEquals(2, audit.size());

        // 검증: 중복, 범위, 모르는 탐지기, 없는 행, 이름 외 유형
        String hitPk = (String) hit[0];
        int s0 = span.start();
        int e0 = span.end();
        String det = span.detector();
        assertEquals(409, assertThrows(ApiException.class, () -> labels.add(req(conn, hitPk, s0, e0, LabelService.NOT_PII, det, false))).status().value());
        assertEquals(409, assertThrows(ApiException.class, () -> labels.add(req(conn, hitPk, s0, e0, LabelService.MISSED, null, false))).status().value(),
                "같은 자리에 다른 종류 라벨 불가");
        assertThrows(ApiException.class, () -> labels.add(new AddRequest(conn, SRC, "id", hitPk, "no_such_col", 0, 2,
                LabelService.MISSED, "NAME", null, null, false)));
        assertThrows(ApiException.class, () -> labels.add(new AddRequest(conn, "x`; DROP", "id", hitPk, "utterance", 0, 2,
                LabelService.MISSED, "NAME", null, null, false)));
        assertThrows(ApiException.class, () -> labels.add(new AddRequest(conn, SRC, "id", hitPk, null, 0, 2,
                LabelService.MISSED, "NAME", null, null, false)), "컬럼 없음은 400");
        assertThrows(ApiException.class, () -> labels.forRows(new QueryRequest(conn, SRC, "id",
                java.util.Collections.nCopies(201, "1"))));
        assertEquals(List.of(), labels.forRows(new QueryRequest(conn, SRC, "id", List.of())));
        assertEquals(404, assertThrows(ApiException.class, () -> labels.delete(987654)).status().value());
        assertThrows(ApiException.class, () -> labels.add(req(conn, hitPk, 0, hitText.length() + 1, LabelService.MISSED, null, false)));
        assertThrows(ApiException.class, () -> labels.add(req(conn, hitPk, s0, e0, LabelService.NOT_PII, "임의 문자열 김태양", false)));
        assertEquals(404, assertThrows(ApiException.class, () -> labels.add(req(conn, "99999999", 0, 2, LabelService.MISSED, null, false))).status().value());
        assertThrows(ApiException.class, () -> labels.add(new AddRequest(conn, SRC, "id", hitPk, "utterance", s0, e0,
                LabelService.MISSED, "PHONE", null, null, false)));
        assertThrows(ApiException.class, () -> labels.add(new AddRequest(conn, SRC, "call_id", hitPk, "utterance", s0, e0,
                LabelService.MISSED, "NAME", null, null, false)), "PK 가 아닌 컬럼");

        // 원천 값이 바뀌면 그 행의 라벨은 무효(stale)
        db.exec("UPDATE " + ItDb.t(SRC) + " SET utterance = CONCAT(utterance, ' ') WHERE id = ?", Long.parseLong(hitPk));
        List<LabelView> now = labels.forRows(new QueryRequest(conn, SRC, "id", List.of(hitPk, (String) miss[0])));
        assertEquals(2, now.size());
        assertTrue(now.stream().filter(l -> l.pk().equals(hitPk)).allMatch(LabelView::stale));
        assertTrue(now.stream().filter(l -> !l.pk().equals(hitPk)).noneMatch(LabelView::stale));
        var summary = labels.summary(conn, SRC);
        assertEquals(2, summary.stream().mapToInt(LabelService.SummaryRow::count).sum());
        assertEquals(1, summary.stream().mapToInt(LabelService.SummaryRow::stale).sum());
        // 범위를 정하지 않으면 메타 DB 건수만(원천을 읽지 않음)
        assertTrue(labels.summary(null, null).stream().allMatch(x -> x.stale() == null));

        // 무효가 된 자리에 다시 달면 새 라벨로 바뀐다(409 아님)
        LabelView a = labels.add(req(conn, hitPk, s0, e0, LabelService.NOT_PII, det, false));
        assertFalse(a.stale());
        assertFalse(labels.forRows(new QueryRequest(conn, SRC, "id", List.of(hitPk))).stream().anyMatch(LabelView::stale));

        labels.delete(a.id());
        assertEquals(1, labels.forRows(new QueryRequest(conn, SRC, "id", List.of(hitPk, (String) miss[0]))).size());
    }

    @Test
    void score_and_context_detector_names_are_accepted() {
        db.createSource(SRC, 1);
        long conn = registerConnection("it-label-d");
        Object[] r = db.dialogPairsSource(SRC, 5).get(0);
        String t = (String) r[3];
        labels.add(req(conn, (String) r[0], 0, Math.min(3, t.length()), LabelService.NOT_PII, "이름(점수)", false));
        labels.add(req(conn, (String) r[0], 1, Math.min(4, t.length()), LabelService.NOT_PII, "이름(문맥)", false));
        assertEquals(2, labels.summary(conn, SRC).stream().mapToInt(LabelService.SummaryRow::count).sum());
    }

    @Test
    void not_pii_can_also_add_exception_word() {
        db.createSource(SRC, 1);
        long conn = registerConnection("it-label-x");
        Object[] hit = null;
        Span span = null;
        for (Object[] r : db.dialogPairsSource(SRC, 400)) {
            List<Span> names = engine.detect((String) r[3], Set.of(EntityType.NAME));
            if (!names.isEmpty()) {
                hit = r;
                span = names.get(0);
                break;
            }
        }
        assertNotNull(hit);
        String word = ((String) hit[3]).substring(span.start(), span.end());
        labels.add(req(conn, (String) hit[0], span.start(), span.end(), LabelService.NOT_PII, span.detector(), true));
        assertEquals(1, meta.queryForObject("SELECT COUNT(*) FROM name_dictionary WHERE kind = 'EXCEPT'", Integer.class));
        // 예외어가 바로 엔진에 반영된다
        String t = (String) hit[3];
        int s0 = span.start();
        int e0 = span.end();
        assertTrue(engines.current().detect(t, Set.of(EntityType.NAME)).stream().noneMatch(x -> x.start() == s0 && x.end() == e0));
        // 사전 단어는 사용자가 고른 평문으로 저장되지만(설계), 감사 로그에는 남지 않는다
        assertFalse(String.join("\n", meta.queryForList("SELECT target FROM audit_log", String.class)).contains(word));
        assertThrows(ApiException.class, () -> labels.add(new AddRequest(conn, SRC, "id", "1", "utterance", 0, 2,
                LabelService.MISSED, "NAME", null, null, true)), "놓침 라벨에는 예외 사전 추가 불가");
    }
}
