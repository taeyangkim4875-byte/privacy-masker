package com.piimask.app.label;

import com.piimask.app.support.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** span_label 접근. 원문 컬럼은 없다(위치·지문만) */
@Repository
public class LabelRepository {

    public record Label(long id, long connectionId, String sourceTable, String pkColumn, String pkValue, String column,
                        int start, int end, String label, String entityType, String detector, Long jobId, String rowFp,
                        String createdBy, String createdAt) {
    }

    private static final RowMapper<Label> MAPPER = (rs, i) -> {
        Long job = rs.getObject("job_id", Long.class);
        return new Label(rs.getLong("id"), rs.getLong("connection_id"), rs.getString("source_table"),
                rs.getString("pk_column"), rs.getString("pk_value"), rs.getString("column_name"),
                rs.getInt("start_pos"), rs.getInt("end_pos"), rs.getString("label"), rs.getString("entity_type"),
                rs.getString("detector"), job, rs.getString("row_fp"),
                rs.getString("created_by"), Times.fmt(rs.getTimestamp("created_at")));
    };

    private final JdbcTemplate jdbc;

    public LabelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Label l) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO span_label(connection_id, source_table, pk_column, pk_value, "
                    + "column_name, start_pos, end_pos, label, entity_type, detector, job_id, row_fp, created_by) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", new String[]{"ID"});
            ps.setLong(1, l.connectionId());
            ps.setString(2, l.sourceTable());
            ps.setString(3, l.pkColumn());
            ps.setString(4, l.pkValue());
            ps.setString(5, l.column());
            ps.setInt(6, l.start());
            ps.setInt(7, l.end());
            ps.setString(8, l.label());
            ps.setString(9, l.entityType());
            ps.setString(10, l.detector());
            if (l.jobId() == null) {
                ps.setNull(11, Types.BIGINT);
            } else {
                ps.setLong(11, l.jobId());
            }
            ps.setString(12, l.rowFp());
            ps.setString(13, l.createdBy());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<Label> find(long id) {
        return jdbc.query("SELECT * FROM span_label WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 같은 자리(접속·테이블·PK·컬럼·위치)의 라벨 */
    public Optional<Label> at(long connectionId, String table, String pk, String column, int start, int end) {
        return jdbc.query("SELECT * FROM span_label WHERE connection_id = ? AND source_table = ? AND pk_value = ? AND column_name = ? "
                + "AND start_pos = ? AND end_pos = ?", MAPPER, connectionId, table, pk, column, start, end).stream().findFirst();
    }

    /** 종류·유형·탐지기별 건수(메타 DB 만). connectionId·table 이 null 이면 전체 */
    public List<Object[]> counts(Long connectionId, String table) {
        String where = connectionId == null ? "" : " WHERE connection_id = ? AND source_table = ?";
        Object[] args = connectionId == null ? new Object[0] : new Object[]{connectionId, table};
        return jdbc.query("SELECT connection_id, source_table, label, entity_type, detector, COUNT(*) FROM span_label" + where
                        + " GROUP BY connection_id, source_table, label, entity_type, detector ORDER BY connection_id, source_table, label",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)},
                args);
    }

    public List<Label> forTable(long connectionId, String table) {
        return jdbc.query("SELECT * FROM span_label WHERE connection_id = ? AND source_table = ?", MAPPER, connectionId, table);
    }

    public List<Label> forRows(long connectionId, String table, Collection<String> pks) {
        if (pks.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(pks.size(), "?"));
        Object[] args = new Object[pks.size() + 2];
        args[0] = connectionId;
        args[1] = table;
        int k = 2;
        for (String pk : pks) {
            args[k++] = pk;
        }
        return jdbc.query("SELECT * FROM span_label WHERE connection_id = ? AND source_table = ? AND pk_value IN (" + in
                + ") ORDER BY pk_value, column_name, start_pos", MAPPER, args);
    }

    public List<Label> all() {
        return jdbc.query("SELECT * FROM span_label ORDER BY connection_id, source_table, id", MAPPER);
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM span_label WHERE id = ?", id) > 0;
    }
}
