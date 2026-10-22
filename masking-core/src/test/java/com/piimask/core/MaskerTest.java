package com.piimask.core;

import com.piimask.core.mask.Masker;
import com.piimask.core.mask.MaskingPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskerTest {

    private final MaskingEngine engine = MaskingEngine.withDefaults();

    private String mask(String text, MaskingPolicy policy) {
        Map<EntityType, MaskingPolicy> all = new java.util.EnumMap<>(EntityType.class);
        for (EntityType t : EntityType.values()) {
            all.put(t, policy);
        }
        return engine.mask(text, EnumSet.allOf(EntityType.class), all, "test-salt").masked();
    }

    @Test
    void partial_policy_per_type() {
        assertEquals("010-****-5678", Masker.replace("010-1234-5678", EntityType.PHONE, MaskingPolicy.PARTIAL, ""));
        assertEquals("02-***-4567", Masker.replace("02-123-4567", EntityType.PHONE, MaskingPolicy.PARTIAL, ""));
        assertEquals("4111-11**-****-1111", Masker.replace("4111-1111-1111-1111", EntityType.CARD, MaskingPolicy.PARTIAL, ""));
        assertEquals("900101-1******", Masker.replace("900101-1234567", EntityType.RRN, MaskingPolicy.PARTIAL, ""));
        assertEquals("***-***-**6789", Masker.replace("110-123-456789", EntityType.ACCOUNT, MaskingPolicy.PARTIAL, ""));
        assertEquals("ki****@example.com", Masker.replace("kim.ty@example.com", EntityType.EMAIL, MaskingPolicy.PARTIAL, ""));
        assertEquals("김*양", Masker.replace("김태양", EntityType.NAME, MaskingPolicy.PARTIAL, ""));
        assertEquals("김*", Masker.replace("김솔", EntityType.NAME, MaskingPolicy.PARTIAL, ""));
        assertEquals("남**수", Masker.replace("남궁민수", EntityType.NAME, MaskingPolicy.PARTIAL, ""));
    }

    @Test
    void tag_and_full_policy() {
        assertEquals("연락처는 [전화번호]이요", mask("연락처는 010-1234-5678이요", MaskingPolicy.TAG));
        assertEquals("연락처는 ***-****-****이요", mask("연락처는 010-1234-5678이요", MaskingPolicy.FULL));
    }

    @Test
    void hash_is_format_independent_and_salted() {
        String a = Masker.replace("010-1234-5678", EntityType.PHONE, MaskingPolicy.HASH, "s1");
        String b = Masker.replace("01012345678", EntityType.PHONE, MaskingPolicy.HASH, "s1");
        String c = Masker.replace("01012345678", EntityType.PHONE, MaskingPolicy.HASH, "s2");
        assertEquals(a, b);
        assertNotEquals(b, c);
        assertTrue(a.startsWith("[PHONE:"));
    }

    @Test
    void masked_output_contains_no_original_values() {
        String text = "박민서 고객님, 카드 4111-1111-1111-1111 과 주민번호 900101-1234567, 연락처 010-9876-5432 확인했습니다.";
        String out = mask(text, MaskingPolicy.TAG);
        assertFalse(out.contains("박민서"));
        assertFalse(out.contains("4111-1111-1111-1111"));
        assertFalse(out.contains("1234567"));
        assertFalse(out.contains("9876"));
        assertEquals("[이름] 고객님, 카드 [카드번호] 과 주민번호 [주민등록번호], 연락처 [전화번호] 확인했습니다.", out);
    }

    @Test
    void text_without_pii_is_unchanged() {
        String text = "이번 달 결제일은 25일이고 한도는 300만원입니다.";
        assertEquals(text, mask(text, MaskingPolicy.TAG));
    }
}
