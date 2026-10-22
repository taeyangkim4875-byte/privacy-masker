package com.piimask.core;

import com.piimask.core.mask.Masker;
import com.piimask.core.mask.MaskingPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ITN: 한글로 읽은 숫자 정탐·오탐, 원문 위치 마스킹 */
class ItnDetectionTest {

    private final MaskingEngine engine = MaskingEngine.withDefaults();
    private static final Set<EntityType> NUMERIC = Set.of(EntityType.PHONE, EntityType.CARD, EntityType.RRN, EntityType.ACCOUNT);

    private List<String> found(String text, EntityType type) {
        return engine.detect(text, Set.of(type)).stream().map(s -> text.substring(s.start(), s.end())).toList();
    }

    // ---------- 정탐 ----------

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "연락처는 공일공 사팔이일 칠칠삼육 입니다     | 공일공 사팔이일 칠칠삼육",
            "공일공에 사팔이일에 칠칠삼육                 | 공일공에 사팔이일에 칠칠삼육",
            "번호는 공일공사팔이일칠칠삼육                | 공일공사팔이일칠칠삼육",
            "영일영 사팔이일 칠칠삼육이요                 | 영일영 사팔이일 칠칠삼육",
            "공일공 사팔이일 칠칠삼육이에요               | 공일공 사팔이일 칠칠삼육",
            "공일공 사팔이일 칠칠삼육입니다               | 공일공 사팔이일 칠칠삼육",
            "공일공-사팔이일-칠칠삼육으로 주세요          | 공일공-사팔이일-칠칠삼육",
            "공일공, 사팔이일, 칠칠삼육                   | 공일공, 사팔이일, 칠칠삼육",
            "010에 사팔이일 칠칠삼육                      | 010에 사팔이일 칠칠삼육",
            "공일공 육륙구육 팔칠팔일                     | 공일공 육륙구육 팔칠팔일",
            "공일공 사팔이 칠칠삼육                       | 공일공 사팔이 칠칠삼육",
            "끝자리가 이라서 공일공 사팔이일 칠칠삼이요   | 공일공 사팔이일 칠칠삼이",
            "회사는 공이 삼사오 육칠팔구 입니다           | 공이 삼사오 육칠팔구",
            "공삼일 구팔칠 육오사삼                       | 공삼일 구팔칠 육오사삼",
    })
    void phone_read_in_korean(String text, String expected) {
        assertEquals(List.of(expected.trim()), found(text.trim(), EntityType.PHONE));
    }

    @Test
    void card_read_in_korean_passes_luhn() {
        assertEquals(List.of("사일일일 일일일일 일일일일 일일일일"), found("카드는 사일일일 일일일일 일일일일 일일일일 입니다", EntityType.CARD));
        assertEquals(List.of("오오오오에 오오오오에 오오오오에 사사사사"), found("오오오오에 오오오오에 오오오오에 사사사사요", EntityType.CARD));
    }

    @Test
    void rrn_read_in_korean() {
        assertEquals(List.of("구공공일공일 일이삼사오육칠"), found("주민번호 구공공일공일 일이삼사오육칠 이요", EntityType.RRN));
        assertEquals(List.of("팔팔공삼일오에 이일이삼사오육"), found("팔팔공삼일오에 이일이삼사오육입니다", EntityType.RRN));
    }

    @Test
    void account_read_in_korean() {
        assertEquals(List.of("일이삼사오육 칠팔 구공일이삼사"), found("환불은 국민은행 일이삼사오육 칠팔 구공일이삼사 계좌로요", EntityType.ACCOUNT));
        assertEquals(List.of("일이삼사오육칠팔구공일이"), found("입금 계좌 일이삼사오육칠팔구공일이 입니다", EntityType.ACCOUNT));
    }

    // ---------- 오탐 ----------

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "일번을 눌러주세요",
            "이번 달 결제일은 이십오일입니다",
            "삼십만원 결제됐어요",
            "오만 삼천원 입금했어요",
            "이천이십육년 시월 이십일일",
            "이사 일정은 다음 주예요",
            "사이에 끼어 있어요",
            "오이 두 개 주세요",
            "일일 한도는 오백만원입니다",
            "공사 중이라 구일 오후에 와요",
            "공일공 사팔이일 까지만 기억나요",
            "끝자리 사팔이일 칠칠삼육 이요",
            "공일공 사팔이일 칠칠 입니다",
            "영수증은 영업일 기준 삼일 이내 발송",
            "카드는 사일일일 일일일일 일일일일 일일일이 입니다",
            "주민번호 구공일삼삼일 일이삼사오육칠",
            "주민번호 구공공일공일 구이삼사오육칠",
            "일이삼사오육칠팔구공일이 번 주문이요",
            "이 이 이 이 이 이 이 이 이 라고 했어요",
            "다시 말씀드리면 이번에 사 일 동안 써요",
    })
    void not_detected_and_identical_to_engine_without_itn(String text) {
        assertTrue(engine.detect(text, NUMERIC).isEmpty(), "숫자형 오탐");
        assertEquals(engine.withoutItn().detect(text), engine.detect(text), "ITN 이 다른 결과를 바꿈");
    }

    @Test
    void arabic_number_with_copula_keeps_the_copula() {
        assertEquals("연락처는 [전화번호]이요", engine.mask("연락처는 010-1234-5678이요", Set.of(EntityType.values()),
                tag(), "s").masked());
        assertEquals("연락처는 [전화번호]이요", engine.mask("연락처는 공일공 사팔이일 칠칠삼육이요", Set.of(EntityType.values()),
                tag(), "s").masked());
        // 기존 시드 형태: 띄어 쓴 "이요"
        assertEquals("[전화번호] 이요.", engine.mask("공일공 육육구육 팔칠팔일 이요.", Set.of(EntityType.values()),
                tag(), "s").masked());
    }

    // ---------- 리뷰 반영 ----------

    /** 리뷰 1: "이" 를 빼면 계좌 형태만 남는 번호(끝자리 2)도 전 유형에서 마지막 자리까지 가린다 */
    @Test
    void last_digit_two_is_masked_with_all_types() {
        Set<EntityType> all = Set.of(EntityType.values());
        assertEquals("[전화번호]입니다.", engine.mask("공일공에 구일삼팔에 칠팔사이입니다.", all, tag(), "s").masked());
        assertEquals("[전화번호]입니다.", engine.mask("010에 일팔칠사에 칠구육이입니다.", all, tag(), "s").masked());
        // 유형 일부만 켠 경우와 결과가 같아야 한다
        assertEquals(found("공일공에 구일삼팔에 칠팔사이입니다.", EntityType.PHONE),
                engine.detect("공일공에 구일삼팔에 칠팔사이입니다.").stream()
                        .map(s -> "공일공에 구일삼팔에 칠팔사이입니다.".substring(s.start(), s.end())).toList());
    }

    /** 리뷰 2: 아라비아 숫자 번호 뒤·앞의 숫자 음절 단어("오늘", "이요", "사이")는 번호에 붙이지 않는다 */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "연락처 010-4821-7736 오늘 바뀌었어요",
            "010-4821-7736 이요",
            "010-4821-7736 이 번호로 주세요",
            "사이 010-4821-7736 이에요",
            "010-4821-7736 일단 저장해 주세요",
            "카드 4111-1111-1111-1111 사용했어요",
            "주민번호 900101-1234567 이고요",
    })
    void arabic_numbers_are_unchanged_by_itn(String text) {
        assertEquals(engine.withoutItn().detect(text), engine.detect(text));
    }

    /** 리뷰 3: 구분자 없이 문자 체계만 바뀐 번호 */
    @Test
    void mixed_scripts_glued() {
        assertEquals(List.of("공일공4821-7736"), found("번호는 공일공4821-7736 입니다", EntityType.PHONE));
        assertEquals(List.of("010사팔이일7736"), found("010사팔이일7736 이요", EntityType.PHONE));
    }

    /** 리뷰 4: SSML telephone 본문의 짧은 한글 번호, break 로 끊긴 번호 */
    @Test
    void ssml_korean_numbers() {
        String a = "<speak>뒷자리 <say-as interpret-as=\"telephone\">칠칠삼육</say-as> 맞으면</speak>";
        assertEquals(List.of("칠칠삼육"), found(a, EntityType.PHONE));
        String b = "<speak>공일공<break time=\"300ms\"/>사팔이일<break time=\"300ms\"/>칠칠삼육 맞으세요</speak>";
        assertEquals(List.of("공일공<break time=\"300ms\"/>사팔이일<break time=\"300ms\"/>칠칠삼육"), found(b, EntityType.PHONE));
    }

    /** 한 문장에 두 번호: 하나는 "이" 가 조사, 하나는 마지막 숫자 */
    @Test
    void two_chains_with_different_tail_readings() {
        String t = "공일공 사팔이일 칠칠삼육이요 그리고 공일공 구일삼팔 칠팔사이입니다";
        assertEquals("[전화번호]이요 그리고 [전화번호]입니다", engine.mask(t, Set.of(EntityType.values()), tag(), "s").masked());
    }

    // ---------- 마스킹(원문 위치) ----------

    private static Map<EntityType, MaskingPolicy> tag() {
        Map<EntityType, MaskingPolicy> m = new EnumMap<>(EntityType.class);
        for (EntityType t : EntityType.values()) {
            m.put(t, MaskingPolicy.TAG);
        }
        return m;
    }

    @Test
    void partial_mask_counts_korean_digits() {
        assertEquals("공일공 **** 칠칠삼육", Masker.replace("공일공 사팔이일 칠칠삼육", EntityType.PHONE, MaskingPolicy.PARTIAL, "s"));
        assertEquals("공일공에 ****에 칠칠삼육", Masker.replace("공일공에 사팔이일에 칠칠삼육", EntityType.PHONE, MaskingPolicy.PARTIAL, "s"));
        assertEquals("구공공일공일 일******", Masker.replace("구공공일공일 일이삼사오육칠", EntityType.RRN, MaskingPolicy.PARTIAL, "s"));
        assertEquals("*** **** ****", Masker.replace("공일공 사팔이일 칠칠삼육", EntityType.PHONE, MaskingPolicy.FULL, "s"));
    }

    @Test
    void hash_is_the_same_for_korean_and_arabic_forms() {
        String a = Masker.replace("공일공 사팔이일 칠칠삼육", EntityType.PHONE, MaskingPolicy.HASH, "salt");
        String b = Masker.replace("010-4821-7736", EntityType.PHONE, MaskingPolicy.HASH, "salt");
        assertEquals(b, a);
        assertNotEquals(b, Masker.replace("010-4821-7737", EntityType.PHONE, MaskingPolicy.HASH, "salt"));
    }

    @Test
    void masked_output_contains_no_middle_digits() {
        String text = "고객: 공일공에 사팔이일에 칠칠삼육 이 번호로 주세요";
        MaskingEngine.Result r = engine.mask(text, Set.of(EntityType.values()), Map.of(), "s");
        assertEquals("고객: [전화번호] 이 번호로 주세요", r.masked());
        assertTrue(r.spans().get(0).detector().endsWith(MaskingEngine.ITN_SUFFIX));
    }
}
