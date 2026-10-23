package com.piimask.app.batch;

import java.util.HexFormat;

/**
 * 대화 문맥의 통화 ID·화자 값을 문자열로 바꾸는 규칙(배치·직전 행 조회·미리보기가 같이 쓴다).
 * TINYINT(1)/BIT 는 드라이버가 Boolean 으로 줄 수 있어 1/0 으로, 바이너리는 16진수로 바꾼다.
 */
public final class DialogValues {

    private DialogValues() {
    }

    public static String str(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (v instanceof byte[] bytes) {
            return HexFormat.of().formatHex(bytes);
        }
        return v.toString();
    }

    /** 화자 값이 상담사 값과 같은지(대소문자·앞뒤 공백 무시) */
    public static boolean isAgent(String agentValue, Object speaker) {
        String s = str(speaker);
        return s != null && agentValue.equalsIgnoreCase(s.strip());
    }
}
