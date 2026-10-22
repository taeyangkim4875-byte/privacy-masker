package com.piimask.core;

import com.piimask.core.detect.Validators;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectorTest {

    private final MaskingEngine engine = MaskingEngine.withDefaults();

    private List<String> found(String text, EntityType type) {
        return engine.detect(text, Set.of(type)).stream()
                .map(s -> text.substring(s.start(), s.end()))
                .toList();
    }

    // ---------- 전화번호 ----------

    @Test
    void phone_formats() {
        assertEquals(List.of("010-1234-5678"), found("연락처는 010-1234-5678이요", EntityType.PHONE));
        assertEquals(List.of("01012345678"), found("01012345678 으로 주세요", EntityType.PHONE));
        assertEquals(List.of("010 1234 5678"), found("010 1234 5678 맞아요", EntityType.PHONE));
        assertEquals(List.of("02-123-4567"), found("회사 번호 02-123-4567", EntityType.PHONE));
        assertEquals(List.of("031-987-6543"), found("집은 031-987-6543 입니다", EntityType.PHONE));
    }

    @Test
    void phone_rejects_longer_digit_runs_and_dates() {
        assertTrue(found("주문번호 9010123456781234", EntityType.PHONE).isEmpty());
        assertTrue(found("결제일은 2026-10-25 입니다", EntityType.PHONE).isEmpty());
    }

    // ---------- 카드번호 ----------

    @Test
    void card_luhn_valid_detected() {
        assertEquals(List.of("4111-1111-1111-1111"), found("카드번호 4111-1111-1111-1111 입니다", EntityType.CARD));
        assertEquals(List.of("4111 1111 1111 1111"), found("4111 1111 1111 1111", EntityType.CARD));
        assertEquals(List.of("378282246310005"), found("아멕스 378282246310005", EntityType.CARD));
    }

    @Test
    void card_luhn_invalid_rejected() {
        assertTrue(found("4111-1111-1111-1112", EntityType.CARD).isEmpty());
        assertFalse(Validators.luhn("1234567812345678"));
    }

    // ---------- 주민·외국인등록번호 ----------

    @Test
    void rrn_detected_with_or_without_hyphen() {
        assertEquals(List.of("900101-1234567"), found("주민번호는 900101-1234567입니다", EntityType.RRN));
        assertEquals(List.of("0501154123456"), found("0501154123456", EntityType.RRN));
        assertEquals(List.of("880315-5123456"), found("외국인등록번호 880315-5123456", EntityType.RRN));
    }

    @Test
    void rrn_invalid_date_or_gender_rejected() {
        assertTrue(found("900230-1234567", EntityType.RRN).isEmpty());
        assertTrue(found("900101-9234567", EntityType.RRN).isEmpty());
        assertTrue(found("900101-0234567", EntityType.RRN).isEmpty());
    }

    // ---------- 계좌번호 ----------

    @Test
    void account_hyphen_and_context() {
        assertEquals(List.of("110-123-456789"), found("국민은행 110-123-456789 로 환불", EntityType.ACCOUNT));
        assertEquals(List.of("12345678901234"), found("환불 계좌는 12345678901234 입니다", EntityType.ACCOUNT));
    }

    @Test
    void account_plain_digits_without_context_rejected() {
        assertTrue(found("접수번호 12345678901234 확인", EntityType.ACCOUNT).isEmpty());
        assertTrue(found("2026-10-22 접수", EntityType.ACCOUNT).isEmpty());
    }

    // ---------- 이메일 ----------

    @Test
    void email() {
        assertEquals(List.of("kim.ty@example.com"), found("메일은 kim.ty@example.com 으로", EntityType.EMAIL));
    }

    // ---------- 이름(규칙) ----------

    @Test
    void name_with_context() {
        assertEquals(List.of("김태양"), found("김태양이에요", EntityType.NAME));
        assertEquals(List.of("박민서"), found("박민서 고객님 확인 감사합니다", EntityType.NAME));
        assertEquals(List.of("이하늘"), found("상담원 이하늘입니다", EntityType.NAME));
        assertEquals(List.of("최지우"), found("성함은 최지우요", EntityType.NAME));
        assertEquals(List.of("남궁민수"), found("예금주 남궁민수 맞습니다", EntityType.NAME));
    }

    @Test
    void name_hard_negatives() {
        assertTrue(found("이번 달 결제일은 25일입니다", EntityType.NAME).isEmpty());
        assertTrue(found("고객님 확인 감사합니다", EntityType.NAME).isEmpty());
        assertTrue(found("선생님 안녕하세요", EntityType.NAME).isEmpty());
        assertTrue(found("성함이 어떻게 되세요", EntityType.NAME).isEmpty());
    }

    // ---------- 병합 ----------

    @Test
    void phone_inside_account_pattern_resolves_to_phone() {
        List<Span> spans = engine.detect("010-1234-5678");
        assertEquals(1, spans.size());
        assertEquals(EntityType.PHONE, spans.get(0).type());
    }

    @Test
    void overlapping_spans_use_union_range() {
        List<Span> merged = SpanMerger.merge(List.of(
                new Span(0, 5, EntityType.NAME, "a"),
                new Span(3, 10, EntityType.PHONE, "b")));
        assertEquals(1, merged.size());
        assertEquals(0, merged.get(0).start());
        assertEquals(10, merged.get(0).end());
        assertEquals(EntityType.PHONE, merged.get(0).type());
    }
}
