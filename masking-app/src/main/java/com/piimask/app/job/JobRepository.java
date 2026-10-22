package com.piimask.app.job;

import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

@Repository
public class JobRepository {

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<JobDef> findAll() {
        // 행을 먼저 다 읽은 뒤 컬럼을 조회한다(RowMapper 안에서 조회하면 커넥션을 두 개 잡는다)
        List<JobDef> base = jdbc.query("SELECT * FROM masking_job ORDER BY id", (rs, i) -> new JobDef(
                rs.getLong("id"), rs.getString("name"), rs.getLong("connection_id"),
                rs.getString("source_table"), rs.getString("pk_column"), rs.getString("target_table"),
                rs.getInt("chunk_size"), rs.getString("cron"), rs.getBoolean("enabled"),
                List.of(), JobDef.parsePolicies(rs.getString("policies")), rs.getString("hash_salt")));
        return base.stream().map(j -> new JobDef(j.id(), j.name(), j.connectionId(), j.sourceTable(), j.pkColumn(),
                j.targetTable(), j.chunkSize(), j.cron(), j.enabled(), columns(j.id()), j.policies(), j.hashSalt())).toList();
    }

    public JobDef get(long id) {
        return findAll().stream().filter(j -> j.id() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("작업을 찾을 수 없습니다: " + id));
    }

    public List<ColumnSpec> columns(long jobId) {
        return jdbc.query("SELECT column_name, col_mode, entity_types FROM masking_column WHERE job_id = ? ORDER BY ordinal",
                (rs, i) -> {
                    String et = rs.getString("entity_types");
                    List<String> entities = et == null || et.isBlank() ? List.of() : Arrays.asList(et.split(","));
                    return new ColumnSpec(rs.getString("column_name"), rs.getString("col_mode"), entities);
                }, jobId);
    }

    @Transactional
    public long insert(JobDef j) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO masking_job(name, connection_id, source_table, pk_column, target_table, chunk_size, cron, enabled, policies, hash_salt) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)", new String[]{"ID"});
            ps.setString(1, j.name());
            ps.setLong(2, j.connectionId());
            ps.setString(3, j.sourceTable());
            ps.setString(4, j.pkColumn());
            ps.setString(5, j.targetTable());
            ps.setInt(6, j.chunkSize());
            if (j.cron() == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, j.cron());
            }
            ps.setBoolean(8, j.enabled());
            ps.setString(9, JobDef.policiesCsv(j.policies()));
            ps.setString(10, j.hashSalt());
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        saveColumns(id, j.columns());
        return id;
    }

    @Transactional
    public void update(long id, JobDef j) {
        jdbc.update("UPDATE masking_job SET name=?, connection_id=?, source_table=?, pk_column=?, target_table=?, chunk_size=?, "
                        + "cron=?, enabled=?, policies=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                j.name(), j.connectionId(), j.sourceTable(), j.pkColumn(), j.targetTable(), j.chunkSize(),
                j.cron(), j.enabled(), JobDef.policiesCsv(j.policies()), id);
        jdbc.update("DELETE FROM masking_column WHERE job_id = ?", id);
        saveColumns(id, j.columns());
    }

    @Transactional
    public void delete(long id) {
        jdbc.update("DELETE FROM masking_column WHERE job_id = ?", id);
        jdbc.update("DELETE FROM job_watermark WHERE job_id = ?", id);
        jdbc.update("DELETE FROM masking_job WHERE id = ?", id);
    }

    private void saveColumns(long jobId, List<ColumnSpec> cols) {
        int ord = 0;
        for (ColumnSpec c : cols) {
            String entities = c.entities() == null ? null : String.join(",", c.entities());
            jdbc.update("INSERT INTO masking_column(job_id, column_name, col_mode, entity_types, ordinal) VALUES (?,?,?,?,?)",
                    jobId, c.column(), c.mode(), entities, ord++);
        }
    }

    public String watermark(long jobId) {
        List<String> v = jdbc.queryForList("SELECT last_pk FROM job_watermark WHERE job_id = ?", String.class, jobId);
        return v.isEmpty() ? null : v.get(0);
    }

    public void saveWatermark(long jobId, String lastPk) {
        int n = jdbc.update("UPDATE job_watermark SET last_pk = ?, updated_at = CURRENT_TIMESTAMP WHERE job_id = ?", lastPk, jobId);
        if (n == 0) {
            jdbc.update("INSERT INTO job_watermark(job_id, last_pk) VALUES (?, ?)", jobId, lastPk);
        }
    }

    public void resetWatermark(long jobId) {
        jdbc.update("DELETE FROM job_watermark WHERE job_id = ?", jobId);
    }
}
