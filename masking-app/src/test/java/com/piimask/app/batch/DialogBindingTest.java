package com.piimask.app.batch;

import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.job.JobDtos.DialogSpec;
import com.piimask.app.job.JobService;
import com.piimask.app.support.ApiException;
import com.piimask.core.DetectionContext;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 배치 대화 문맥: 행 값 위치, 섞여 들어오는 통화, 실행 경계의 직전 행 조회(범위·상한), 값 정규화, 설정 검증 */
class DialogBindingTest {

    /** 값 순서 [pk, call_id, speaker, utterance] */
    private static Object[] row(Object call, Object speaker, String text) {
        return new Object[]{"0", call, speaker, text};
    }

    @Test
    void interleaved_calls_and_lookup_only_for_first_seen_customer_rows() {
        List<String> lookups = new ArrayList<>();
        DialogBinding b = new DialogBinding(1, 2, 3, "AGENT", (call, pk, upTo) -> {
            lookups.add(call + "@" + pk + "<=" + upTo);
            return call.equals("old") ? Optional.of(new Object[]{"agent", "성함 말씀해 주시겠어요?"}) : Optional.empty();
        }, "100");
        assertEquals(DetectionContext.NONE, b.contextFor("101", row("c1", "AGENT", "성함 말씀해 주시겠어요?")));
        assertEquals(DetectionContext.NONE, b.contextFor("102", row("c2", "AGENT", "카드 번호 말씀해 주세요")));
        assertEquals(DetectionContext.NAME_ANSWER, b.contextFor("103", row("c1", "CUSTOMER", "네 김태양")));
        assertEquals(DetectionContext.NONE, b.contextFor("104", row("c2", "CUSTOMER", "네 김태양")));
        // 처음 보는 통화의 첫 고객 발화: 시작 워터마크 이하의 원천 직전 행으로 문맥을 잇는다(화자 값은 대소문자·공백 무시)
        assertEquals(DetectionContext.NAME_ANSWER, b.contextFor("105", row("old", " customer ", "네 박지훈")));
        assertEquals(DetectionContext.NONE, b.contextFor("106", row("new", "CUSTOMER", "네 박지훈")));
        assertEquals(List.of("old@105<=100", "new@106<=100"), lookups, "이미 본 통화·상담사 발화는 조회하지 않음");
    }

    @Test
    void no_lookup_on_first_run_and_lookups_are_capped() {
        List<String> lookups = new ArrayList<>();
        DialogBinding first = new DialogBinding(1, 2, 3, "AGENT", (c, p, u) -> {
            lookups.add(c);
            return Optional.empty();
        }, null);
        first.contextFor("1", row("a", "CUSTOMER", "네"));
        assertEquals(List.of(), lookups, "첫 실행(워터마크 없음)은 경계 너머 행이 없으므로 조회하지 않음");

        DialogBinding later = new DialogBinding(1, 2, 3, "AGENT", (c, p, u) -> Optional.empty(), "1");
        for (int i = 0; i < DialogBinding.MAX_LOOKUPS + 50; i++) {
            later.contextFor(String.valueOf(i + 2), row("call-" + i, "CUSTOMER", "네"));
        }
        assertEquals(DialogBinding.MAX_LOOKUPS, later.lookups());
    }

    @Test
    void null_values_do_not_touch_state_and_values_are_normalized() {
        DialogBinding b = new DialogBinding(1, 2, 3, "1", (c, p, u) -> Optional.empty(), null);
        // TINYINT(1) 화자: 드라이버가 Boolean 으로 줘도 "1" 과 맞는다
        assertEquals(DetectionContext.NONE, b.contextFor("1", row("c", Boolean.TRUE, "성함 말씀해 주세요")));
        // 화자 NULL 행은 질문 상태를 쓰지도 지우지도 않는다
        assertEquals(DetectionContext.NONE, b.contextFor("2", row("c", null, "네 김태양")));
        assertEquals(DetectionContext.NAME_ANSWER, b.contextFor("3", row("c", Boolean.FALSE, "네 김태양")));
        assertEquals(DetectionContext.NONE, b.contextFor("4", row(null, "0", "네 김태양")));
        assertEquals("0a0b", DialogValues.str(new byte[]{10, 11}));
        assertEquals("7", DialogValues.str(7L));
    }

    @Test
    void source_lookup_errors_carry_no_values() {
        DataSource failing = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, m, args) -> {
                    throw new SQLException("Data truncation: value '홍길순 고객님' too long", "22001");
                });
        SourcePreviousRow r = new SourcePreviousRow(failing, "t", "id", "call_id", "speaker", "utterance", true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> r.find("c1", "10", "5"));
        assertFalse(e.getMessage().contains("홍길순"));
        assertNull(e.getCause());
    }

    @Test
    void dialog_spec_validation() {
        List<ColumnSpec> cols = List.of(new ColumnSpec("call_id", "COPY", List.of()), new ColumnSpec("speaker", "COPY", List.of()),
                new ColumnSpec("utterance", "MASK", List.of()));
        assertNull(JobService.dialogOf(null, cols));
        assertNull(JobService.dialogOf(new DialogSpec("", " ", null), cols));
        assertEquals(new DialogSpec("call_id", "speaker", "AGENT"), JobService.dialogOf(new DialogSpec(" call_id ", "speaker", " AGENT "), cols));
        assertThrows(ApiException.class, () -> JobService.dialogOf(new DialogSpec("call_id", "", "AGENT"), cols));
        assertThrows(ApiException.class, () -> JobService.dialogOf(new DialogSpec("call_id", "other", "AGENT"), cols));
        assertThrows(ApiException.class, () -> JobService.dialogOf(new DialogSpec("call_id", "call_id", "AGENT"), cols));
        assertThrows(ApiException.class, () -> JobService.dialogOf(new DialogSpec("call_id", "speaker", "A".repeat(51)), cols));
    }
}
