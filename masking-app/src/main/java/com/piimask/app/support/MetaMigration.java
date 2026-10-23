package com.piimask.app.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 메타 DB(H2) 컬럼 추가: 기동 시 한 번, 없을 때만 ALTER 한다.
 * meta-schema.sql 은 연결마다 실행되므로(INIT=RUNSCRIPT) 거기서 ALTER 하면 연결마다 테이블 배타 락을 잡는다.
 * 새 DB 는 CREATE TABLE 에 컬럼이 이미 있다.
 */
@Component("metaMigration")
public class MetaMigration {

    record AddColumn(String table, String column, String type) {
    }

    static final List<AddColumn> COLUMNS = List.of(
            new AddColumn("MASKING_JOB", "DIALOG_CALL_COLUMN", "VARCHAR(128)"),
            new AddColumn("MASKING_JOB", "DIALOG_SPEAKER_COLUMN", "VARCHAR(128)"),
            new AddColumn("MASKING_JOB", "DIALOG_AGENT_VALUE", "VARCHAR(50)"));

    public MetaMigration(JdbcTemplate jdbc) {
        migrate(jdbc);
    }

    static void migrate(JdbcTemplate jdbc) {
        for (AddColumn c : COLUMNS) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, c.table(), c.column());
            if (n == null || n == 0) {
                // 이름은 위 상수뿐(사용자 입력 아님)
                jdbc.execute("ALTER TABLE " + c.table() + " ADD COLUMN " + c.column() + " " + c.type());
            }
        }
    }
}
