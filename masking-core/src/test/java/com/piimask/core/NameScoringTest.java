package com.piimask.core;

import com.piimask.core.detect.NameRuleDetector;
import com.piimask.core.dict.NameHints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 이름 점수화(약한 단서): 정탐·오탐, 강한 단서 규칙과의 관계 */
class NameScoringTest {

    private final MaskingEngine engine = MaskingEngine.withDefaults();

    private List<String> names(MaskingEngine e, String text) {
        return e.detect(text, Set.of(EntityType.NAME)).stream().map(s -> text.substring(s.start(), s.end())).toList();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "김태양인데요, 분실 신고요        | 김태양",
            "박지훈한테 보내 주세요            | 박지훈",
            "최수아에게 전해 주세요            | 최수아",
            "신유나께서 신청하셨어요           | 신유나",
            "조아인께 안부 전해 주세요         | 조아인",
            "고승우라는 분이 전화했어요        | 고승우",
            "강하늘이란 사람 아세요            | 강하늘",
            "장건우이랑 같이 왔어요            | 장건우",
            "윤도하 명의로 가입했어요          | 윤도하",
            "강민서 앞으로 청구서 보내 주세요  | 강민서",
            "송민재 본인 맞습니다              | 송민재",
            "받는 분 정하윤으로 해 주세요      | 정하윤",
            "남편 임준호 카드도 정지해 주세요  | 임준호",
            "저는 한서율.                      | 한서율",
            "담당자 문가온 연결해 주세요       | 문가온",
            "명의자 조민혁으로 등록해 주세요   | 조민혁",
            "남궁민수한테 보냈어요             | 남궁민수",
            "이상민한테 물어볼게요             | 이상민",
            "수신인 박다온, 주소는 그대로요    | 박다온",
    })
    void weak_cues_are_detected(String text, String expected) {
        assertEquals(List.of(expected.strip()), names(engine, text.strip()));
        assertEquals(List.of(), names(engine.withoutNameScoring(), text.strip()), "강한 단서 규칙만으로는 안 잡히는 문장이어야 함");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "지금 강남인데요.",
            "여기 성남인데요.",
            "안양인데 지점이 어디죠?",
            "요즘 고생인데요.",
            "하루인데요 걸리나요?",
            "이틀인데요.",
            "지난달인데요.",
            "이사인데요.",
            "소포인데요.",
            "고장인데요.",
            "차량인데요.",
            "선물인데요.",
            "마감인데요.",
            "지하철인데요.",
            "주유소인데요.",
            "고객한테 안내드렸어요.",
            "우리 엄마한테 줬어요.",
            "남편 고생했어요.",
            "동생 이사 갔어요.",
            "받는 분 주소가 바뀌었어요.",
            "본인인데요.",
            "이삼한테 줘요",
            "담당자 연결 부탁드려요.",
            "담당자 변경해 주세요.",
            "아버지 연세가 많으세요.",
            "저는 하나도 몰라요.",
            "저는 정기 결제요.",
            "저는 공동 명의예요.",
            "저는 주부인데요.",
            "지원인데요.",
            "정지라는 말을 들었어요.",
            "엄마 지인한테 받았어요.",
            "아내 명의예요.",
            "배우자 명의로 할게요.",
            "신한이라는 카드요.",
            "국민에게 물어보세요.",
            "현대한테 물어볼게요.",
            "남편 하나 아들 하나예요.",
    })
    void non_names_with_weak_cues_are_not_detected(String text) {
        assertEquals(List.of(), names(engine, text));
    }

    @Test
    void strong_rules_win_and_scoring_only_adds() {
        // 강한 단서 스팬이 있으면 점수 스팬을 만들지 않는다
        List<Span> s = engine.detect("김태양 고객님한테 보냈어요", Set.of(EntityType.NAME));
        assertEquals(1, s.size());
        assertEquals("이름(규칙)", s.get(0).detector());
        List<Span> t = engine.detect("박지훈한테 보내 주세요", Set.of(EntityType.NAME));
        assertEquals(NameRuleDetector.SCORE_DETECTOR, t.get(0).detector());
    }

    /** 두 글자 이름은 점수화하지 않는다(일반어가 너무 많음). 강한 단서로만 잡힌다 */
    @Test
    void two_syllable_names_need_strong_cues() {
        assertEquals(List.of(), names(engine, "김민한테 줬어요"));
        assertEquals(List.of("김민"), names(engine, "김민 고객님한테 줬어요"));
    }

    @Test
    void text_boundaries() {
        assertEquals(List.of("김민준"), names(engine, "남편 김민준"));
        assertEquals(List.of(), names(engine, "남편 김"));
        assertEquals(List.of(), names(engine, "저는"));
        assertEquals(List.of("박지훈"), names(engine, "박지훈한테"));
    }

    @Test
    void user_exception_removes_scored_span_and_switch_survives_dictionary_change() {
        String t = "고승우라는 분이 전화했어요";
        MaskingEngine e = engine.withDictionary(com.piimask.core.dict.NameDictionary.builtIn().withUser(List.of("고승우"), List.of()));
        assertEquals(List.of(), names(e, t));
        MaskingEngine off = engine.withoutNameScoring().withDictionary(com.piimask.core.dict.NameDictionary.builtIn());
        assertEquals(List.of(), names(off, t), "사전을 바꿔도 점수화 꺼짐이 유지돼야 함");
    }

    @Test
    void name_hint_lists_do_not_overlap() {
        NameHints h = NameHints.builtIn();
        Set<String> both = new HashSet<>(h.givenSyllables());
        both.retainAll(h.nonNameSyllables());
        assertTrue(both.isEmpty(), "흔한 이름 음절과 비이름 음절이 겹침: " + both);
        assertTrue(h.places().contains("강남"));
    }
}
