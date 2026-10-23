package com.piimask.core;

import com.piimask.core.dict.NameDictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 이름 사전: 기본 성씨·예외 사전, 사용자 예외어·추가 탐지어 */
class DictionaryTest {

    private final MaskingEngine engine = MaskingEngine.withDefaults();
    /** 예외 사전을 뺀 엔진: 아래 오탐 문장이 실제로 규칙에 걸리는지 확인용 */
    private final MaskingEngine noExceptions = engine.withDictionary(NameDictionary.builtIn().withoutExceptions());

    private static List<String> names(MaskingEngine e, String text) {
        return e.detect(text, Set.of(EntityType.NAME)).stream().map(s -> text.substring(s.start(), s.end())).toList();
    }

    @Test
    void built_in_dictionary_loads() {
        NameDictionary d = NameDictionary.builtIn();
        assertTrue(d.surnames().containsAll(Set.of("김", "이", "남궁", "황보", "제갈")));
        assertTrue(d.exceptions().containsAll(Set.of("지점장", "명세서", "*점", "고객님")));
        assertTrue(d.forced().isEmpty());
        assertTrue(d.exceptions().stream().allMatch(t -> t.length() >= NameDictionary.MIN_TERM));
    }

    // ---------- 예외 사전: 오탐 제거 ----------

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "강남점 고객님 맞으시죠?",
            "서초점 고객님이세요?",
            "명동점입니다.",
            "지점장님께 전달드리겠습니다.",
            "소장님 확인 중입니다.",
            "이번 달 명세서입니다.",
            "선결제입니다.",
            "공과금입니다.",
            "구독료입니다.",
            "위약금이고요 다음 달에 안내드릴게요.",
            "연체료입니다.",
            "연회비입니다.",
            "원금이요.",
            "현금이요.",
            "금액이요.",
            "이자입니다.",
            "안내문입니다.",
            "신분증이요.",
            "여권이요.",
            "제품이요.",
            "서류입니다.",
            "손님이요.",
            "전산 오류입니다.",
            "그건 문제입니다.",
            "결제일은 매월 오일입니다.",
            "네 정상입니다.",
    })
    void exception_words_are_not_names(String text) {
        assertFalse(names(noExceptions, text).isEmpty(), "예외 사전 없이는 걸리는 문장이어야 함");
        assertEquals(List.of(), names(engine, text));
    }

    @Test
    void real_names_next_to_exception_words_stay() {
        assertEquals(List.of("김태양"), names(engine, "지점장님께 김태양 고객님 건 전달드리겠습니다."));
        assertEquals(List.of("박서준"), names(engine, "강남점 박서준 고객님 맞으시죠?"));
        assertEquals(List.of("최민서"), names(engine, "최민서 고객님 이번 달 명세서입니다."));
        assertEquals(List.of("장하늘"), names(engine, "예금주 장하늘이요, 원금이요."));
        // 이름이 예외어보다 길어 예외어 밖으로 나가면 남는다
        assertEquals(List.of("이자영"), names(engine, "이자영 고객님"));
        // 이름일 수 있는 말은 기본 예외 사전에 넣지 않았다
        assertEquals(List.of("이율"), names(engine, "이율 고객님 확인 감사합니다."));
        assertEquals(List.of("고지서"), names(engine, "고지서 고객님 확인 감사합니다."));
    }

    @Test
    void exceptions_apply_only_to_names() {
        String t = "지점장 연락처 010-4821-7736 이고 명세서입니다";
        List<Span> spans = engine.detect(t);
        assertEquals(1, spans.size());
        assertEquals(EntityType.PHONE, spans.get(0).type());
    }

    @Test
    void default_engine_equals_engine_with_built_in_dictionary() {
        MaskingEngine same = engine.withDictionary(NameDictionary.builtIn());
        for (String t : List.of("강남점 박서준 고객님", "성함은 김태양", "고,영,환", "KIM EUNMIN,회원님", "지점장님께 전달")) {
            assertEquals(engine.detect(t), same.detect(t));
        }
    }

    /** 희귀 2글자 성씨를 기본 사전에 넣지 않은 이유: 일반어가 이름으로 잡힌다 */
    @Test
    void rare_surnames_are_not_built_in() {
        assertEquals(List.of(), names(engine, "어금니예요"));
        assertEquals(List.of(), names(engine, "동방생명이요"));
    }

    @Test
    void surnames_must_be_hangul_syllables() {
        NameDictionary bad = NameDictionary.of(List.of("김", "]"), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> engine.withDictionary(bad));
    }

    // ---------- 사용자 사전 ----------

    @Test
    void user_exception_removes_rule_hit() {
        String t = "구독권입니다.";
        assertEquals(List.of("구독권"), names(engine, t));
        MaskingEngine e = engine.withDictionary(NameDictionary.builtIn().withUser(List.of("구독권"), List.of()));
        assertEquals(List.of(), names(e, t));
        assertEquals(List.of("구민서"), names(e, "구민서 고객님 구독권입니다."));
    }

    @Test
    void forced_terms_detect_without_context() {
        MaskingEngine e = engine.withDictionary(NameDictionary.builtIn().withUser(List.of(), List.of("홍길순", "TAEYANG")));
        assertEquals(List.of("홍길순"), names(e, "어제 홍길순한테 들었어요"));
        assertEquals(List.of("홍길순"), names(e, "(홍길순) 메모"));
        // 앞이 한글이면 다른 단어의 일부로 본다
        assertEquals(List.of(), names(e, "박홍길순한테"));
        // 로마자는 대소문자 무시, 앞뒤가 영숫자면 제외
        assertEquals(List.of("taeyang"), names(e, "taeyang 님"));
        assertEquals(List.of(), names(e, "XTAEYANG TAEYANGS"));
        // 추가 탐지어가 없는 기본 엔진은 잡지 않는다
        assertEquals(List.of(), names(engine, "어제 홍길순한테 들었어요"));
    }

    @Test
    void forced_terms_win_over_exceptions() {
        MaskingEngine e = engine.withDictionary(
                NameDictionary.builtIn().withUser(List.of("김태양"), List.of("김태양")));
        assertEquals(List.of("김태양"), names(e, "김태양 고객님"));
        assertEquals(List.of("김태양"), names(e, "그 사람 김태양"));
    }

    @Test
    void forced_terms_mask_with_policy() {
        MaskingEngine e = engine.withDictionary(NameDictionary.builtIn().withUser(List.of(), List.of("홍길순")));
        assertEquals("어제 [이름]한테 들었어요",
                e.mask("어제 홍길순한테 들었어요", Set.of(EntityType.values()), java.util.Map.of(), "s").masked());
    }

    @Test
    void clean_term_rules() {
        assertEquals("홍 길순", NameDictionary.cleanTerm("  홍   길순 "));
        // 붙여 넣기에 섞이는 NBSP·BOM·제로폭 문자
        assertEquals("김태양", NameDictionary.cleanTerm("﻿김태양 "));
        assertEquals("김태양", NameDictionary.cleanTerm("김​태양"));
        assertEquals("KIM TAEYANG", NameDictionary.cleanTerm("KIM 　TAEYANG"));
        for (String bad : new String[]{null, "", " 홍 ", "*점", "홍\t\u0000길", "가".repeat(51)}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> NameDictionary.cleanTerm(bad));
            if (bad != null && bad.strip().length() > 1) {
                assertFalse(ex.getMessage().contains(bad.strip()), "오류 메시지에 단어가 들어가면 안 됨");
            }
        }
    }
}
