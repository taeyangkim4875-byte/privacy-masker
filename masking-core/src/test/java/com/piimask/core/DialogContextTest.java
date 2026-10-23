package com.piimask.core;

import com.piimask.core.detect.NameRuleDetector;
import com.piimask.core.dialog.DialogTracker;
import com.piimask.core.dialog.NameQuestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 화자 문맥: 이름 질문 판별, 통화별 상태, 이름 질문에 대한 답의 이름 탐지 */
class DialogContextTest {

    private final MaskingEngine engine = MaskingEngine.withDefaults();

    private List<String> answer(String text) {
        return engine.detect(text, Set.of(EntityType.NAME), DetectionContext.NAME_ANSWER).stream()
                .map(s -> text.substring(s.start(), s.end())).toList();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "본인 확인을 위해 성함 말씀해 주시겠어요?",
            "고객님 성함이 어떻게 되세요?",
            "예금주 성함 알려 주시겠어요?",
            "명의자분 성함 확인 부탁드립니다.",
            "존함이 어떻게 되십니까?",
            "성함이요?",
            "이름 불러 주시겠어요",
            "성함 여쭤봐도 될까요",
            "명의자 성함 여쭙겠습니다",
            "보호자분 성함이요",
            "예금주님 성함은요",
            "이름이 뭐예요",
            "실례지만 누구세요",
    })
    void name_questions(String text) {
        assertTrue(NameQuestion.isNameQuestion(text));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "카드 번호 말씀해 주시겠어요?",
            "다른 문의 있으실까요?",
            "성함 확인되었습니다 감사합니다.",
            "고객님 이름으로 발송됩니다.",
            "주소 확인 부탁드립니다.",
            "이름 변경은 어떻게 도와드릴까요?",
            "예금주 명의로 되어 있는지 확인해 보겠습니다",
            "명의자가 다르면 확인이 어렵습니다",
            "제 이름은 김민수입니다 무엇을 도와드릴까요?",
            "상담원 김수진입니다 무엇을 도와드릴까요",
            "",
    })
    void not_name_questions(String text) {
        assertFalse(NameQuestion.isNameQuestion(text));
    }

    @Test
    void tracker_applies_only_to_the_next_customer_utterance_of_the_same_call() {
        DialogTracker t = new DialogTracker(100);
        assertEquals(DetectionContext.NONE, t.next("c1", true, "성함 말씀해 주시겠어요?"));
        // 다른 통화의 고객 발화에는 적용하지 않는다(통화가 섞여 들어와도)
        assertEquals(DetectionContext.NONE, t.next("c2", false, "네 박지훈"));
        assertEquals(DetectionContext.NAME_ANSWER, t.next("c1", false, "네 김태양"));
        // 한 번 쓰면 지운다
        assertEquals(DetectionContext.NONE, t.next("c1", false, "네 맞아요"));
        // 질문이 아닌 상담사 발화가 끼면 지운다
        t.next("c1", true, "성함 말씀해 주시겠어요?");
        t.next("c1", true, "잠시만요 다시 여쭤볼게요.");
        assertEquals(DetectionContext.NONE, t.next("c1", false, "네 김태양"));
        assertEquals(DetectionContext.NONE, t.next(null, false, "네 김태양"));
    }

    @Test
    void tracker_forgets_oldest_calls_and_can_be_primed() {
        DialogTracker t = new DialogTracker(2);
        t.next("a", true, "성함 말씀해 주세요.");
        t.next("b", true, "성함 말씀해 주세요.");
        t.next("c", true, "성함 말씀해 주세요.");
        assertFalse(t.seen("a"));
        assertEquals(DetectionContext.NONE, t.next("a", false, "네 김태양"));
        // 실행 경계: 원천의 직전 행으로 상태를 채운다
        DialogTracker u = new DialogTracker(10);
        assertFalse(u.seen("x"));
        u.prime("x", true, "성함이 어떻게 되세요?");
        assertTrue(u.seen("x"));
        assertEquals(DetectionContext.NAME_ANSWER, u.next("x", false, "네 김태양"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "네 김태양       | 김태양",
            "김태양          | 김태양",
            "김태양요.       | 김태양",
            "예 박지훈       | 박지훈",
            "아 네 최수아    | 최수아",
            "음 조민         | 조민",
            "네, 권지아.     | 권지아",
            "저는 서민준     | 서민준",
            "임하윤 이요     | 임하윤",
            "배하율 입니다   | 배하율",
            "네 네 강서현    | 강서현",
            "남궁민수        | 남궁민수",
            "이도현이에요    | 이도현",
            "네 이서연이요   | 이서연",
            "한예준예요      | 한예준",
            "저 송유나       | 송유나",
            "응 정하윤       | 정하윤",
            "네 조현         | 조현",
            "윤도하이고요    | 윤도하",
            "네네 문가온     | 문가온",
    })
    void answers_to_a_name_question(String text, String expected) {
        assertEquals(List.of(expected.strip()), answer(text.strip()));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "잠시만요.",
            "왜요?",
            "네?",
            "네 잠깐만요",
            "아 그게요",
            "네 알겠습니다",
            "제 이름이요?",
            "고객님이요?",
            "남편이요.",
            "정말요?",
            "하나카드요",
            "신한카드요?",
            "회사 명의예요.",
            "법인 명의요",
            "네 맞아요",
            "이따가 할게요",
            "고생 많으세요",
            "지금 운전 중이라서요",
            "서울 강남구요",
            "공일공이요",

            "이삼사요",
            "네 정상이요",
            "여보세요",
            "아 여보세요",
            "이따가요",
            "이상해요",
            "고민중이요",
            "유지요",
            "모두요",
            "손자요",
            "이모요",
            "고모요",
            "장모요",
    })
    void non_name_answers(String text) {
        assertEquals(List.of(), answer(text));
    }

    /** 숫자 읽기 음절로만 된 진짜 이름은 강한 단서로 잡는다(공 이 든 번호 읽기만 뺀다) */
    @Test
    void digit_syllable_names_still_detected() {
        for (String t : List.of("이영 고객님", "성함은 이영입니다", "오영일 고객님", "이영구 고객님", "구영 회원님", "이일 씨")) {
            assertEquals(1, engine.detect(t, Set.of(EntityType.NAME)).size(), "이름을 놓침");
        }
        assertEquals(List.of(), engine.detect("공일공이요", Set.of(EntityType.NAME)));
    }

    @Test
    void without_context_nothing_changes() {
        for (String t : List.of("네 김태양", "김태양", "음 조민")) {
            assertEquals(List.of(), engine.detect(t, Set.of(EntityType.NAME)));
            assertEquals(engine.detect(t), engine.detect(t, Set.of(EntityType.values()), DetectionContext.NONE));
        }
        // 다른 이름 규칙이 이미 잡았으면 문맥 규칙은 더하지 않는다
        List<Span> s = engine.detect("김태양 고객님", Set.of(EntityType.NAME), DetectionContext.NAME_ANSWER);
        assertEquals(1, s.size());
        assertEquals("이름(규칙)", s.get(0).detector());
        assertEquals(NameRuleDetector.CONTEXT_DETECTOR,
                engine.detect("네 김태양", Set.of(EntityType.NAME), DetectionContext.NAME_ANSWER).get(0).detector());
    }

    @Test
    void context_mask_and_other_types_unchanged() {
        MaskingEngine.Result r = engine.mask("네 김태양 010-1234-5678", Set.of(EntityType.values()), java.util.Map.of(), "s",
                DetectionContext.NAME_ANSWER);
        // 번호가 붙은 발화는 "이름 하나뿐"이 아니므로 문맥 규칙을 쓰지 않는다(번호는 그대로 가림)
        assertEquals(1, r.spans().size());
        assertEquals(EntityType.PHONE, r.spans().get(0).type());
    }
}
