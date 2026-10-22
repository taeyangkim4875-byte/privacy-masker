package com.piimask.core.mask;

import com.piimask.core.EntityType;
import com.piimask.core.Span;
import com.piimask.core.detect.Validators;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * 스팬 목록에 정책을 적용해 마스킹 문자열을 만든다.
 * spans 는 SpanMerger 를 거쳐 겹치지 않고 start 순으로 정렬되어 있어야 한다.
 */
public final class Masker {

    private Masker() {
    }

    public static String apply(String text, List<Span> spans,
                               Function<EntityType, MaskingPolicy> policyOf, String salt) {
        if (text == null || spans.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int pos = 0;
        for (Span s : spans) {
            if (s.start() < pos || s.end() > text.length()) {
                throw new IllegalArgumentException("spans must be merged, sorted and within text");
            }
            sb.append(text, pos, s.start());
            String value = text.substring(s.start(), s.end());
            MaskingPolicy policy = policyOf.apply(s.type());
            sb.append(replace(value, s.type(), policy == null ? MaskingPolicy.TAG : policy, salt));
            pos = s.end();
        }
        sb.append(text, pos, text.length());
        return sb.toString();
    }

    public static String replace(String value, EntityType type, MaskingPolicy policy, String salt) {
        return switch (policy) {
            case TAG -> "[" + type.label() + "]";
            case FULL -> full(value);
            case PARTIAL -> partial(value, type);
            case HASH -> "[" + type.name() + ":" + hash(normalize(value, type), salt) + "]";
        };
    }

    static String full(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? '*' : c);
        }
        return sb.toString();
    }

    static String partial(String value, EntityType type) {
        return switch (type) {
            case PHONE -> {
                String d = Validators.digitsOnly(value);
                int head = d.startsWith("02") ? 2 : 3;
                yield maskDigits(value, head, 4);
            }
            // 카드: 여신업계 관행대로 7~12번째 자리 마스킹
            case CARD -> maskDigits(value, 6, 4);
            // 주민번호: 생년월일 + 성별 자리만 노출
            case RRN -> maskDigits(value, 7, 0);
            case ACCOUNT -> maskDigits(value, 0, 4);
            case EMAIL -> maskEmail(value);
            case NAME -> maskName(value);
        };
    }

    /** 숫자만 세어 앞 keepHead, 뒤 keepTail 자리를 남기고 나머지 숫자를 * 로 */
    static String maskDigits(String value, int keepHead, int keepTail) {
        int total = Validators.digitsOnly(value).length();
        StringBuilder sb = new StringBuilder(value.length());
        int idx = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                boolean keep = idx < keepHead || idx >= total - keepTail;
                sb.append(keep ? c : '*');
                idx++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0) {
            return full(value);
        }
        String local = value.substring(0, at);
        int keep = local.length() <= 3 ? 1 : 2;
        return local.substring(0, keep) + "*".repeat(local.length() - keep) + value.substring(at);
    }

    static String maskName(String value) {
        int n = value.length();
        if (n <= 1) {
            return "*";
        }
        if (n == 2) {
            return value.charAt(0) + "*";
        }
        return value.charAt(0) + "*".repeat(n - 2) + value.charAt(n - 1);
    }

    /** 숫자형은 숫자만 남겨 형식이 달라도 같은 토큰이 되게 한다 */
    static String normalize(String value, EntityType type) {
        return switch (type) {
            case PHONE, CARD, RRN, ACCOUNT -> Validators.digitsOnly(value);
            case EMAIL -> value.toLowerCase();
            case NAME -> value.replace(" ", "");
        };
    }

    static String hash(String value, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8));
            md.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
