package com.piimask.app.dict;

import com.piimask.app.support.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** name_dictionary 접근. 단어(term)는 실명일 수 있으므로 여기서 로그를 남기지 않는다 */
@Repository
public class DictionaryRepository {

    public enum Kind {
        /** 이름 아님(예외어) */
        EXCEPT,
        /** 문맥 없이도 이름(추가 탐지어) */
        FORCE
    }

    public record Entry(long id, Kind kind, String term, String createdBy, String createdAt) {
    }

    private final JdbcTemplate jdbc;

    public DictionaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Entry> list() {
        return jdbc.query("SELECT id, kind, term, created_by, created_at FROM name_dictionary ORDER BY kind, term",
                (rs, i) -> new Entry(rs.getLong("id"), Kind.valueOf(rs.getString("kind")), rs.getString("term"),
                        rs.getString("created_by"), Times.fmt(rs.getTimestamp("created_at"))));
    }

    public List<String> terms(Kind kind) {
        return jdbc.queryForList("SELECT term FROM name_dictionary WHERE kind = ?", String.class, kind.name());
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM name_dictionary", Integer.class);
        return n == null ? 0 : n;
    }

    public Optional<Entry> find(long id) {
        return jdbc.query("SELECT id, kind, term, created_by, created_at FROM name_dictionary WHERE id = ?",
                (rs, i) -> new Entry(rs.getLong("id"), Kind.valueOf(rs.getString("kind")), rs.getString("term"),
                        rs.getString("created_by"), Times.fmt(rs.getTimestamp("created_at"))), id).stream().findFirst();
    }

    /** 한 트랜잭션으로 넣는다(중복 검사는 호출 쪽에서, 유일 제약이 마지막 방어선) */
    @Transactional
    public void insertAll(Kind kind, List<String> terms, String createdBy) {
        for (String t : terms) {
            jdbc.update("INSERT INTO name_dictionary(kind, term, created_by) VALUES (?, ?, ?)", kind.name(), t, createdBy);
        }
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM name_dictionary WHERE id = ?", id) > 0;
    }
}
