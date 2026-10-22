package com.piimask.app.batch;

import com.piimask.app.support.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class ExecutionRepository {

    private static final String SELECT = "SELECT e.*, j.name AS job_name FROM job_execution e "
            + "LEFT JOIN masking_job j ON j.id = e.job_id ";

    private static final RowMapper<ExecutionView> MAPPER = (rs, i) -> new ExecutionView(
            rs.getLong("id"), rs.getLong("job_id"),
            rs.getString("job_name") == null ? "(삭제된 작업)" : rs.getString("job_name"),
            rs.getString("trigger_type"), rs.getString("status"),
            Times.fmt(rs.getTimestamp("started_at")), Times.fmt(rs.getTimestamp("ended_at")),
            rs.getLong("read_count"), rs.getLong("masked_rows"), rs.getLong("span_count"),
            rs.getLong("written_count"), rs.getString("start_pk"), rs.getString("end_pk"),
            rs.getString("error_message"));

    private final JdbcTemplate jdbc;

    public ExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(long jobId, String trigger, String startPk) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO job_execution(job_id, trigger_type, status, started_at, start_pk, end_pk) VALUES (?,?,?,?,?,?)",
                    new String[]{"ID"});
            ps.setLong(1, jobId);
            ps.setString(2, trigger);
            ps.setString(3, "RUNNING");
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(5, startPk);
            ps.setString(6, startPk);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void progress(long execId, long read, long maskedRows, long spans, long written, String endPk) {
        jdbc.update("UPDATE job_execution SET read_count=?, masked_rows=?, span_count=?, written_count=?, end_pk=? WHERE id=?",
                read, maskedRows, spans, written, endPk, execId);
    }

    public void finish(long execId, String status, String error) {
        jdbc.update("UPDATE job_execution SET status=?, ended_at=?, error_message=? WHERE id=?",
                status, Timestamp.valueOf(LocalDateTime.now()), error, execId);
    }

    public int failInterrupted() {
        // Spring Batch 쪽에 STARTED 로 남은 실행도 같이 정리한다(새 실행은 매번 새 JobInstance 라 막히진 않지만 이력 정합성용)
        for (String table : List.of("BATCH_STEP_EXECUTION", "BATCH_JOB_EXECUTION")) {
            jdbc.update("UPDATE " + table + " SET STATUS='FAILED', EXIT_CODE='FAILED', END_TIME=CURRENT_TIMESTAMP, "
                    + "EXIT_MESSAGE='interrupted by application restart' WHERE STATUS IN ('STARTING','STARTED','STOPPING')");
        }
        return jdbc.update("UPDATE job_execution SET status='FAILED', ended_at=CURRENT_TIMESTAMP, "
                + "error_message='애플리케이션 재시작으로 중단됨. 다시 실행하면 마지막 처리 지점부터 이어서 진행합니다' WHERE status='RUNNING'");
    }

    public ExecutionView last(long jobId) {
        List<ExecutionView> l = jdbc.query(SELECT + "WHERE e.job_id = ? ORDER BY e.id DESC LIMIT 1", MAPPER, jobId);
        return l.isEmpty() ? null : l.get(0);
    }

    public ExecutionView get(long id) {
        List<ExecutionView> l = jdbc.query(SELECT + "WHERE e.id = ?", MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<ExecutionView> byJob(long jobId, int limit) {
        return jdbc.query(SELECT + "WHERE e.job_id = ? ORDER BY e.id DESC LIMIT ?", MAPPER, jobId, limit);
    }

    public List<ExecutionView> recent(int limit) {
        return jdbc.query(SELECT + "ORDER BY e.id DESC LIMIT ?", MAPPER, limit);
    }

    /** 최근 7일 일자별 처리 건수(대시보드) */
    public List<Map<String, Object>> dailyTotals() {
        return jdbc.query("SELECT CAST(started_at AS DATE) AS d, SUM(read_count) AS r, "
                        + "SUM(masked_rows) AS m, SUM(span_count) AS s "
                        + "FROM job_execution WHERE started_at >= DATEADD(DAY, -6, CURRENT_DATE) "
                        + "GROUP BY CAST(started_at AS DATE) ORDER BY d",
                (rs, i) -> Map.<String, Object>of(
                        "date", rs.getDate("d").toLocalDate().toString(),
                        "readCount", rs.getLong("r"),
                        "maskedRows", rs.getLong("m"),
                        "spanCount", rs.getLong("s")));
    }
}
