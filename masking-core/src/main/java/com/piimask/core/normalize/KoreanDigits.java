package com.piimask.core.normalize;

/**
 * 한 자리씩 읽은 한글 숫자 음절(STT 출력): 공·영=0 일=1 이=2 삼=3 사=4 오=5 육·륙=6 칠=7 팔=8 구=9.
 * 자릿수 단어(십·백·천·만·억)는 숫자로 보지 않는다(금액·날짜 표현은 정규화 대상이 아님).
 */
public final class KoreanDigits {

    private KoreanDigits() {
    }

    /** 한글 숫자 음절이면 0~9, 아니면 -1 */
    public static int value(char c) {
        return switch (c) {
            case '공', '영' -> 0;
            case '일' -> 1;
            case '이' -> 2;
            case '삼' -> 3;
            case '사' -> 4;
            case '오' -> 5;
            case '육', '륙' -> 6;
            case '칠' -> 7;
            case '팔' -> 8;
            case '구' -> 9;
            default -> -1;
        };
    }

    public static boolean isKoreanDigit(char c) {
        return value(c) >= 0;
    }

    /** 아라비아 숫자 또는 한글 숫자 음절 */
    public static boolean isDigitLike(char c) {
        return (c >= '0' && c <= '9') || isKoreanDigit(c);
    }

    /** 숫자 값(아라비아 숫자 그대로, 한글 숫자는 변환). 숫자가 아니면 -1 */
    public static int digitValue(char c) {
        return c >= '0' && c <= '9' ? c - '0' : value(c);
    }

    /** 문자열에서 숫자(아라비아·한글)만 뽑아 아라비아 숫자열로. "공일공-12" → "01012" */
    public static String digits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            int v = digitValue(s.charAt(i));
            if (v >= 0) {
                sb.append((char) ('0' + v));
            }
        }
        return sb.toString();
    }

    public static boolean containsKoreanDigit(String s, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(end, s.length()); i++) {
            if (isKoreanDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean isHangulSyllable(char c) {
        return c >= '가' && c <= '힣';
    }
}
