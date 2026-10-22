package com.piimask.app.support;

import com.piimask.app.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 설정 변경·실행·로그인 감사 로그. 대상은 ID·이름만 남기고 데이터 값은 남기지 않는다.
 * actor 는 로그인 사용자, 사용자 요청이 아니면 system(스케줄은 system:scheduler).
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 현재 요청의 사용자로 기록 */
    public void log(String action, String target, String outcome) {
        logAs(CurrentUser.name(), action, target, outcome);
    }

    /** 사용자를 지정해 기록(요청 스레드 밖의 배치 완료, 로그인 시도 등) */
    public void logAs(String actor, String action, String target, String outcome) {
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
