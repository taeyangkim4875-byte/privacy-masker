package com.piimask.app.support;

import java.sql.SQLException;

/**
 * 예외를 화면·이력에 남길 문자열로 바꾼다.
 * SQL 예외는 SQLState/에러코드 위주로 남기고, 메시지는 길이를 잘라 원문 유출 가능성을 줄인다.
 */
public final class Errors {

    private Errors() {
    }

    public static String safeMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String type = root.getClass().getSimpleName();
        if (root instanceof SQLException sql) {
            return type + " [SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + "] "
                    + truncate(firstLine(sql.getMessage()), 200);
        }
        return type + ": " + truncate(firstLine(root.getMessage()), 200);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
