package com.piimask.app.it;

import java.security.SecureRandom;
import java.time.LocalDate;

/**
 * 테스트 전용 가짜 개인정보 생성기. 실행마다 다른 값(카나리)을 만들어
 * 로그·이력에서 발견되면 원문 유출로 판정한다. 실존 번호를 노리지 않는다.
 */
final class FakePii {

    private final SecureRandom rnd = new SecureRandom();

    String phone() {
        return "010-" + digits(4) + "-" + digits(4);
    }

    /** Luhn 을 통과하는 16자리 카드번호(4로 시작) */
    String card() {
        StringBuilder d = new StringBuilder("4").append(digits(14));
        d.append(luhnCheckDigit(d.toString()));
        return d.substring(0, 4) + "-" + d.substring(4, 8) + "-" + d.substring(8, 12) + "-" + d.substring(12);
    }

    /** 생년월일·성별 자리가 유효한 주민등록번호 형식 */
    String rrn() {
        LocalDate birth = LocalDate.of(1970 + rnd.nextInt(30), 1, 1).plusDays(rnd.nextInt(365));
        String yymmdd = String.format("%02d%02d%02d", birth.getYear() % 100, birth.getMonthValue(), birth.getDayOfMonth());
        return yymmdd + "-" + (1 + rnd.nextInt(2)) + digits(6);
    }

    String email() {
        return "itc" + digits(10) + "@example.com";
    }

    private String digits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(rnd.nextInt(10));
        }
        return sb.toString();
    }

    static int luhnCheckDigit(String body) {
        int sum = 0;
        boolean dbl = true;
        for (int i = body.length() - 1; i >= 0; i--) {
            int v = body.charAt(i) - '0';
            if (dbl) {
                v *= 2;
                if (v > 9) {
                    v -= 9;
                }
            }
            sum += v;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }
}
