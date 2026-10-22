package com.piimask.app.support;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 동적 SQL 식별자 처리. 테이블·컬럼명은 바인드 파라미터로 넘길 수 없으므로
 * 메타데이터로 존재를 확인한 이름만 쓰고, 백틱으로 감싼다.
 */
public final class Sql {

    private static final Pattern SAFE = Pattern.compile("[\\p{L}\\p{N}_$]{1,64}");

    private Sql() {
    }

    public static String requireIdentifier(String name, String what) {
        if (name == null || !SAFE.matcher(name).matches()) {
            throw ApiException.badRequest(what + " 이름에 사용할 수 없는 문자가 있습니다: 영문·숫자·한글·_·$ 만 허용");
        }
        return name;
    }

    public static String q(String identifier) {
        return "`" + requireIdentifier(identifier, "식별자") + "`";
    }

    public static String qList(List<String> identifiers) {
        return identifiers.stream().map(Sql::q).collect(Collectors.joining(", "));
    }

    public static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
