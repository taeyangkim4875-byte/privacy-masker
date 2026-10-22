package com.piimask.app.connection;

import com.piimask.app.connection.ConnectionDtos.ConnectionRequest;
import com.piimask.app.connection.ConnectionDtos.ConnectionSecret;
import com.piimask.app.connection.ConnectionDtos.ConnectionView;
import com.piimask.app.connection.ConnectionDtos.TestResult;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.AuditService;
import com.piimask.app.support.CryptoService;
import com.piimask.app.support.Errors;
import com.piimask.app.support.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class ConnectionService {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-_]{1,255}");
    private static final Pattern DB = Pattern.compile("[A-Za-z0-9_$\\-]{1,64}");
    private static final Pattern OPTIONS = Pattern.compile("[A-Za-z0-9=&_.\\-]{0,500}");

    private final JdbcTemplate jdbc;
    private final CryptoService crypto;
    private final DataSourceRegistry registry;
    private final AuditService audit;

    public ConnectionService(JdbcTemplate jdbc, CryptoService crypto, DataSourceRegistry registry, AuditService audit) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.registry = registry;
        this.audit = audit;
    }

    public List<ConnectionView> list() {
        return jdbc.query("SELECT * FROM db_connection ORDER BY id", (rs, i) -> new ConnectionView(
                rs.getLong("id"), rs.getString("name"), rs.getString("host"), rs.getInt("port"),
                rs.getString("database_name"), rs.getString("username"), rs.getString("options"),
                rs.getString("password_enc") != null,
                Times.fmt(rs.getTimestamp("created_at")), Times.fmt(rs.getTimestamp("updated_at"))));
    }

    public ConnectionView get(long id) {
        return list().stream().filter(c -> c.id() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("접속 정보를 찾을 수 없습니다: " + id));
    }

    public ConnectionSecret secret(long id) {
        List<ConnectionSecret> found = jdbc.query("SELECT * FROM db_connection WHERE id = ?", (rs, i) -> new ConnectionSecret(
                rs.getLong("id"), rs.getString("name"), rs.getString("host"), rs.getInt("port"),
                rs.getString("database_name"), rs.getString("username"),
                crypto.decrypt(rs.getString("password_enc")), rs.getString("options")), id);
        if (found.isEmpty()) {
            throw ApiException.notFound("접속 정보를 찾을 수 없습니다: " + id);
        }
        return found.get(0);
    }

    public ConnectionView create(ConnectionRequest r) {
        validate(r);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO db_connection(name, host, port, database_name, username, password_enc, options) VALUES (?,?,?,?,?,?,?)",
                    new String[]{"ID"});
            ps.setString(1, r.name().trim());
            ps.setString(2, r.host().trim());
            ps.setInt(3, r.port());
            ps.setString(4, r.database().trim());
            ps.setString(5, r.username().trim());
            ps.setString(6, crypto.encrypt(r.password() == null ? "" : r.password()));
            ps.setString(7, blankToNull(r.options()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        audit.log("CONNECTION_CREATE", "connection#" + id + " " + r.name(), "OK");
        return get(id);
    }

    public ConnectionView update(long id, ConnectionRequest r) {
        get(id);
        validate(r);
        if (r.password() == null || r.password().isEmpty()) {
            jdbc.update("UPDATE db_connection SET name=?, host=?, port=?, database_name=?, username=?, options=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    r.name().trim(), r.host().trim(), r.port(), r.database().trim(), r.username().trim(), blankToNull(r.options()), id);
        } else {
            jdbc.update("UPDATE db_connection SET name=?, host=?, port=?, database_name=?, username=?, password_enc=?, options=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    r.name().trim(), r.host().trim(), r.port(), r.database().trim(), r.username().trim(),
                    crypto.encrypt(r.password()), blankToNull(r.options()), id);
        }
        registry.evict(id);
        audit.log("CONNECTION_UPDATE", "connection#" + id + " " + r.name(), "OK");
        return get(id);
    }

    public void delete(long id) {
        ConnectionView c = get(id);
        Integer jobs = jdbc.queryForObject("SELECT COUNT(*) FROM masking_job WHERE connection_id = ?", Integer.class, id);
        if (jobs != null && jobs > 0) {
            throw ApiException.conflict("이 접속을 사용하는 작업이 " + jobs + "개 있습니다. 작업을 먼저 삭제하세요.");
        }
        jdbc.update("DELETE FROM db_connection WHERE id = ?", id);
        registry.evict(id);
        audit.log("CONNECTION_DELETE", "connection#" + id + " " + c.name(), "OK");
    }

    /** 저장 전 입력값으로 접속 테스트 */
    public TestResult test(ConnectionRequest r, Long existingId) {
        validate(r);
        String password = r.password();
        if ((password == null || password.isEmpty()) && existingId != null) {
            password = secret(existingId).password();
        }
        ConnectionSecret s = new ConnectionSecret(0, r.name(), r.host().trim(), r.port(), r.database().trim(),
                r.username().trim(), password, blankToNull(r.options()));
        return testSecret(s);
    }

    public TestResult test(long id) {
        return testSecret(secret(id));
    }

    private TestResult testSecret(ConnectionSecret s) {
        long t0 = System.currentTimeMillis();
        DriverManager.setLoginTimeout(5);
        try (Connection c = DriverManager.getConnection(s.jdbcUrl(), s.username(), s.password())) {
            String version = c.getMetaData().getDatabaseProductVersion();
            return new TestResult(true, "접속 성공", version, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return new TestResult(false, Errors.safeMessage(e), null, System.currentTimeMillis() - t0);
        }
    }

    private void validate(ConnectionRequest r) {
        if (r == null) {
            throw ApiException.badRequest("요청 본문이 없습니다");
        }
        if (r.name() == null || r.name().isBlank()) {
            throw ApiException.badRequest("이름을 입력하세요");
        }
        if (r.host() == null || !HOST.matcher(r.host().trim()).matches()) {
            throw ApiException.badRequest("호스트 형식이 올바르지 않습니다");
        }
        if (r.port() == null || r.port() < 1 || r.port() > 65535) {
            throw ApiException.badRequest("포트는 1~65535 사이여야 합니다");
        }
        if (r.database() == null || !DB.matcher(r.database().trim()).matches()) {
            throw ApiException.badRequest("데이터베이스 이름 형식이 올바르지 않습니다");
        }
        if (r.username() == null || r.username().isBlank()) {
            throw ApiException.badRequest("사용자를 입력하세요");
        }
        if (r.options() != null && !OPTIONS.matcher(r.options().trim()).matches()) {
            throw ApiException.badRequest("JDBC 옵션은 key=value&key=value 형식만 허용합니다");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
