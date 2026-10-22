package com.piimask.core.detect;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * 탐지 결과의 오탐을 줄이는 검증 로직 모음.
 */
public final class Validators {

    private Validators() {
    }

    public static String digitsOnly(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 카드번호 Luhn 체크 */
    public static boolean luhn(String number) {
        String d = digitsOnly(number);
        if (d.length() < 13 || d.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (dbl) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    /**
     * 주민·외국인등록번호 형식 검증.
     * 2020년 10월 이후 발급 번호는 마지막 자리가 체크섬이 아니므로,
     * 체크섬 대신 생년월일 유효성과 성별 자리(1~8)로만 판단한다(Recall 우선).
     */
    public static boolean residentNumber(String value) {
        String d = digitsOnly(value);
        if (d.length() != 13) {
            return false;
        }
        int yy = Integer.parseInt(d.substring(0, 2));
        int mm = Integer.parseInt(d.substring(2, 4));
        int dd = Integer.parseInt(d.substring(4, 6));
        int g = d.charAt(6) - '0';
        int century;
        switch (g) {
            case 1, 2, 5, 6 -> century = 1900;
            case 3, 4, 7, 8 -> century = 2000;
            default -> {
                return false;
            }
        }
        try {
            LocalDate.of(century + yy, mm, dd);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /** 2020년 10월 이전 발급 번호의 기존 체크섬. 참고·통계용. */
    public static boolean residentNumberLegacyChecksum(String value) {
        String d = digitsOnly(value);
        if (d.length() != 13) {
            return false;
        }
        int[] w = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (d.charAt(i) - '0') * w[i];
        }
        int check = (11 - (sum % 11)) % 10;
        return check == d.charAt(12) - '0';
    }

    /** 원문에서 매치 앞쪽 window 글자 안에 키워드가 있는지 */
    public static boolean keywordBefore(String text, int start, int window, String... keywords) {
        int from = Math.max(0, start - window);
        String before = text.substring(from, start);
        for (String k : keywords) {
            if (before.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
