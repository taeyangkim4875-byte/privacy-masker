package com.piimask.core;

import com.piimask.core.mask.MaskingPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TTS 요청 SSML 패턴 */
class SsmlTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><speak version=\"1.0\" "
            + "xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"ko-KR\"><prosody rate=\"slow\">";
    private static final String TAIL = "</prosody></speak>";

    private final MaskingEngine engine = MaskingEngine.withDefaults();

    private List<String> found(String body, EntityType type) {
        String text = HEAD + body + TAIL;
        return engine.detect(text, Set.of(type)).stream().map(s -> text.substring(s.start(), s.end())).toList();
    }

    private String mask(String body) {
        Map<EntityType, MaskingPolicy> p = new EnumMap<>(EntityType.class);
        for (EntityType t : EntityType.values()) {
            p.put(t, MaskingPolicy.TAG);
        }
        return engine.mask(HEAD + body + TAIL, EnumSet.allOf(EntityType.class), p, "s").masked();
    }

    @Test
    void markup_is_never_masked() {
        String out = mask("고객님 안녕하세요.");
        assertTrue(out.startsWith(HEAD), "SSML 헤더(버전·네임스페이스)는 그대로여야 함");
        assertTrue(out.endsWith(TAIL));
    }

    @Test
    void name_followed_by_comma_and_member_title() {
        assertEquals(List.of("장나예"), found("장나예,회원님이 맞으면 1번, 아니면 2번을 눌러주세요.", EntityType.NAME));
        assertEquals(List.of("박서준"), found("박서준회원님의 카드는 현재 두개 입니다.", EntityType.NAME));
    }

    @Test
    void name_alone_in_text_node() {
        assertEquals(List.of("장나예"), found("장나예", EntityType.NAME));
        assertTrue(found("감사합니다", EntityType.NAME).isEmpty());
    }

    @Test
    void spelled_name() {
        assertEquals(List.of("고,영,환"), found("고,영,환", EntityType.NAME));
        assertEquals(List.of("김, 태, 양"), found("성함은 김, 태, 양 맞으신가요", EntityType.NAME));
        assertTrue(found("이,삼,사", EntityType.NAME).isEmpty(), "숫자 끊어 읽기는 이름 아님");
        assertTrue(found("일번,패밀리 카드,이번,하이패스", EntityType.NAME).isEmpty());
    }

    @Test
    void romanized_name() {
        assertEquals(List.of("KIM EUNMIN"), found("KIM EUNMIN,회원님이 맞으면 1번", EntityType.NAME));
        assertEquals(List.of("LEE SEO YEON"), found("LEE SEO YEON", EntityType.NAME));
        assertTrue(found("패밀리 카드M_NEW,LOCAL,이번,카드M(구) 하이패스,LOCAL, 입니다.", EntityType.NAME).isEmpty());
    }

    @Test
    void say_as_telephone_any_length() {
        assertEquals(List.of("01048217736"), found("<say-as interpret-as=\"telephone\">01048217736</say-as>", EntityType.PHONE));
        assertEquals(List.of("7736"), found("<say-as interpret-as=\"telephone\">7736</say-as>", EntityType.PHONE));
    }

    @Test
    void partial_masking_does_not_leak_short_phone_or_spelled_name() {
        assertEquals("****", com.piimask.core.mask.Masker.replace("7736", EntityType.PHONE, MaskingPolicy.PARTIAL, ""));
        assertEquals("고,*,환", com.piimask.core.mask.Masker.replace("고,영,환", EntityType.NAME, MaskingPolicy.PARTIAL, ""));
        assertEquals("K** *****N", com.piimask.core.mask.Masker.replace("KIM EUNMIN", EntityType.NAME, MaskingPolicy.PARTIAL, ""));
    }

    @Test
    void cardinal_and_date_are_not_pii() {
        String out = mask("<say-as interpret-as=\"cardinal\">12.5</say-as>");
        assertTrue(out.contains(">12.5<"));
        out = mask("<say-as format=\"md\" interpret-as=\"date\">1022</say-as>");
        assertTrue(out.contains(">1022<"));
    }
}
