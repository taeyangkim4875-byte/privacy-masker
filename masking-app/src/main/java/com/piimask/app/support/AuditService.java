package com.piimask.app.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 설정 변경·실행 감사 로그. 대상은 ID·이름만 남기고 데이터 값은 남기지 않는다. */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final String actor;

    public AuditService(JdbcTemplate jdbc, @Value("${piimask.actor}") String actor) {
        this.jdbc = jdbc;
        this.actor = actor;
    }

    public void log(String action, String target, String outcome) {
        jdbc.update("INSERT INTO audit_log(actor, action, target, outcome) VALUES (?, ?, ?, ?)",
                actor, action, target, outcome);
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.query("SELECT id, actor, action, target, outcome, created_at FROM audit_log ORDER BY id DESC LIMIT ?",
                (rs, i) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "actor", rs.getString("actor"),
                        "action", rs.getString("action"),
                        "target", rs.getString("target") == null ? "" : rs.getString("target"),
                        "outcome", rs.getString("outcome"),
                        "createdAt", Times.fmt(rs.getTimestamp("created_at"))),
                limit);
    }
}
