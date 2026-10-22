package com.piimask.core;

import com.piimask.core.normalize.NumberNormalizer;
import com.piimask.core.normalize.NumberNormalizer.Normalized;
import com.piimask.core.normalize.OffsetMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumberNormalizerTest {

    private static List<String> texts(String s) {
        return NumberNormalizer.normalize(s).stream().map(Normalized::text).toList();
    }

    @Test
    void groups_are_joined_with_hyphen() {
        assertEquals(List.of("010-4821-7736"), texts("공일공 사팔이일 칠칠삼육"));
        assertEquals(List.of("010-4821-7736"), texts("공일공에 사팔이일에 칠칠삼육"));
        assertEquals(List.of("01048217736"), texts("공일공사팔이일칠칠삼육"));
        assertEquals(List.of("번호는 010-4821-7736 입니다"), texts("번호는 공일공-사팔이일-칠칠삼육 입니다"));
        assertEquals(List.of("010-4821-7736"), texts("영일영, 사팔이일, 칠칠삼육"));
        assertEquals(List.of("010-6696-8781"), texts("공일공 육륙구육 팔칠팔일"));
    }

    @Test
    void arabic_groups_join_only_through_separators() {
        assertEquals(List.of("010-4821-7736"), texts("010에 사팔이일 칠칠삼육"));
        // 아라비아 숫자 바로 뒤에 붙은 "이" 는 조사: 잇지 않는다
        assertTrue(texts("연락처는 010-1234-5678이요").isEmpty());
    }

    @Test
    void offsets_point_back_into_the_original() {
        String src = "번호 공일공에 사팔이일에 칠칠삼육요";
        Normalized n = NumberNormalizer.normalize(src).get(0);
        assertEquals("번호 010-4821-7736요", n.text());
        OffsetMap m = n.map();
        assertEquals(n.text().length(), m.size());
        // 숫자 글자는 원문 한 글자, "-" 는 구분자 "에 " 전체
        assertEquals("공", src.substring(m.originalStart(3), m.originalEnd(3)));
        assertEquals("에 ", src.substring(m.originalStart(6), m.originalEnd(6)));
        int s = n.text().indexOf("010");
        assertArrayEquals(new int[]{src.indexOf("공일공"), src.indexOf("요")}, m.toOriginal(s, s + "010-4821-7736".length()));
        // 바뀌지 않은 부분은 그대로
        assertEquals("번호 ", src.substring(m.originalStart(0), m.originalEnd(2)));
    }

    @Test
    void trailing_i_followed_by_hangul_gives_two_readings() {
        List<Normalized> v = NumberNormalizer.normalize("공일공 사팔이일 칠칠삼육이요");
        assertEquals(List.of("010-4821-77362요", "010-4821-7736이요"), v.stream().map(Normalized::text).toList());
        assertTrue(v.get(1).tailStripped());
        assertEquals(List.of("공일공 사팔이일 칠칠삼육이요".indexOf("이요")), v.get(0).ambiguousTails());
        // 뒤가 한글이 아니면 하나뿐
        assertEquals(List.of("010-4821-7732."), texts("공일공 사팔이일 칠칠삼이."));
    }

    @Test
    void short_or_place_value_expressions_are_left_alone() {
        for (String s : List.of(
                "일번을 눌러주세요", "이번 달 결제일은 이십오일입니다", "삼십만원 결제됐어요", "오만 삼천원입니다",
                "이천이십육년 시월", "이사 일정은 다음 주예요", "사이에 끼어 있어요", "공일공 사팔이일",
                "사팔이일 칠칠삼육", "일일 한도는 오백만원", "공사 중이라 구일 오후에 와요", "010-1234-5678",
                "영수증은 영업일 기준 삼일 이내", "")) {
            assertTrue(texts(s).isEmpty(), s);
        }
    }
}
