package com.piimask.app.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 대화 문맥 컬럼이 없는 예전 masking_job 에 컬럼을 한 번만 더한다 */
class MetaMigrationTest {

    @Test
    void adds_missing_columns_once() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:migr;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE masking_job (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        jdbc.update("INSERT INTO masking_job(id, name) VALUES (1, 'old')");
        MetaMigration.migrate(jdbc);
        MetaMigration.migrate(jdbc); // 두 번째는 아무것도 하지 않는다
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'MASKING_JOB' AND COLUMN_NAME LIKE 'DIALOG_%'", Integer.class));
        assertEquals(null, jdbc.queryForObject("SELECT dialog_call_column FROM masking_job WHERE id = 1", String.class),
                "기존 작업은 대화 문맥 사용 안 함");
    }
}
