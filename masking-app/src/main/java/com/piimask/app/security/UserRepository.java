package com.piimask.app.security;

import com.piimask.app.support.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** app_user 접근. 비밀번호 해시는 인증에만 쓰고 화면 응답(UserView)에는 넣지 않는다 */
@Repository
public class UserRepository {

    /** 계정 한 건(내부용, 해시 포함) */
    public record AppUser(long id, String username, String passwordHash, String displayName, Set<Role> roles,
                          boolean enabled, String authSource, int failedCount, LocalDateTime lockedUntil,
                          LocalDateTime passwordChangedAt, String lastLoginAt, String createdAt) {

        public boolean lockedAt(LocalDateTime now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }

    private static final RowMapper<AppUser> MAPPER = (rs, i) -> {
        Timestamp locked = rs.getTimestamp("locked_until");
        Timestamp pwChanged = rs.getTimestamp("password_changed_at");
        return new AppUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("display_name"), parseRoles(rs.getString("roles")), rs.getBoolean("enabled"),
                rs.getString("auth_source"), rs.getInt("failed_count"),
                locked == null ? null : locked.toLocalDateTime(),
                pwChanged == null ? null : pwChanged.toLocalDateTime(),
                Times.fmt(rs.getTimestamp("last_login_at")), Times.fmt(rs.getTimestamp("created_at")));
    };

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static Set<Role> parseRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(Role.class);
        }
        EnumSet<Role> out = EnumSet.noneOf(Role.class);
        // 모르는 역할 문자열은 버린다(매 요청 500 이 나지 않게)
        Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> Arrays.stream(Role.values()).anyMatch(r -> r.name().equals(s)))
                .forEach(s -> out.add(Role.valueOf(s)));
        return out;
    }

    static String rolesCsv(Set<Role> roles) {
        return roles.stream().sorted().map(Role::name).collect(Collectors.joining(","));
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        return n == null ? 0 : n;
    }

    public List<AppUser> findAll() {
        return jdbc.query("SELECT * FROM app_user ORDER BY id", MAPPER);
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT * FROM app_user WHERE username = ?", MAPPER, username).stream().findFirst();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("SELECT * FROM app_user WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public void insert(String username, String passwordHash, String displayName, Set<Role> roles, String authSource) {
        jdbc.update("INSERT INTO app_user(username, password_hash, display_name, roles, auth_source) VALUES (?,?,?,?,?)",
                username, passwordHash, displayName, rolesCsv(roles), authSource);
    }

    public void update(long id, String displayName, Set<Role> roles, boolean enabled) {
        jdbc.update("UPDATE app_user SET display_name=?, roles=?, enabled=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                displayName, rolesCsv(roles), enabled, id);
    }

    /** 비밀번호 변경. 변경 시각보다 먼저 로그인한 세션은 AccountRefreshFilter 가 끊는다 */
    public void updatePassword(long id, String passwordHash, LocalDateTime changedAt) {
        jdbc.update("UPDATE app_user SET password_hash=?, password_changed_at=?, failed_count=0, locked_until=NULL, "
                + "updated_at=CURRENT_TIMESTAMP WHERE id=?", passwordHash, Timestamp.valueOf(changedAt), id);
    }

    public void loginSucceeded(long id) {
        jdbc.update("UPDATE app_user SET failed_count=0, locked_until=NULL, last_login_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }

    /** 실패 횟수를 올리고, 한도에 닿으면 잠근다. 잠겼으면 true */
    public boolean loginFailed(long id, int maxFailures, LocalDateTime lockUntil) {
        jdbc.update("UPDATE app_user SET failed_count = failed_count + 1 WHERE id=?", id);
        int n = jdbc.update("UPDATE app_user SET locked_until=?, failed_count=0 WHERE id=? AND failed_count >= ?",
                Timestamp.valueOf(lockUntil), id, maxFailures);
        return n > 0;
    }

    /** 사용 중인 관리자 수(마지막 관리자 보호용) */
    public long enabledAdmins() {
        return findAll().stream().filter(u -> u.enabled() && u.roles().contains(Role.ADMIN)).count();
    }
}
