package com.piimask.app.support;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

public final class Times {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Times() {
    }

    public static String fmt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().format(FMT);
    }
}
