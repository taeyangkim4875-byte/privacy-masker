package com.piimask.app.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 통합 테스트용 MariaDB 접근(테스트 픽스처 전용).
 * 접속 정보는 환경변수로만 받는다: PIIMASK_IT_DB_URL, PIIMASK_IT_DB_USER, PIIMASK_IT_DB_PASSWORD.
 * 시드 테이블은 읽기만 하고, 쓰기는 it_ 로 시작하는 테스트 전용 테이블에만 한다.
 */
final class ItDb {

    static final String SEED_TABLE = "stt_call_history";
    private static final Pattern URL = Pattern.compile("jdbc:mariadb://([^:/]+):(\\d+)/([A-Za-z0-9_$\\-]+).*");
    private static final Pattern IT_TABLE = Pattern.compile("it_[a-z0-9_]{1,50}");

    final String url;
    final String user;
    final String password;
    final String host;
    final int port;
    final String database;

    private ItDb(String url, String user, String password) {
        Matcher m = URL.matcher(url);
        if (!m.matches()) {
            throw new IllegalStateException("PIIMASK_IT_DB_URL 형식: jdbc:mariadb://host:port/db");
        }
        this.url = url;
        this.user = user;
        this.password = password;
        this.host = m.group(1);
        this.port = Integer.parseInt(m.group(2));
        this.database = m.group(3);
    }

    /** 환경변수가 없으면 이유를 알려주고 실패한다(접속 정보 기본값은 두지 않는다) */
    static ItDb fromEnv() {
        String url = System.getenv("PIIMASK_IT_DB_URL");
        String user = System.getenv("PIIMASK_IT_DB_USER");
        String pw = System.getenv("PIIMASK_IT_DB_PASSWORD");
        if (url == null || user == null || pw == null) {
            throw new IllegalStateException("통합 테스트 DB 환경변수가 없습니다. "
                    + "PIIMASK_IT_DB_URL, PIIMASK_IT_DB_USER, PIIMASK_IT_DB_PASSWORD 를 설정하세요(README 의 통합 테스트 항목 참고)");
        }
        return new ItDb(url, user, pw);
    }

    static boolean keepTables() {
        return "1".equals(System.getenv("PIIMASK_IT_KEEP"));
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    static String t(String table) {
        if (!IT_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("테스트 테이블 이름은 it_ 로 시작해야 합니다: " + table);
        }
        return "`" + table + "`";
    }

    void exec(String sql, Object... args) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 실패 [SQLState=" + e.getSQLState() + ", code=" + e.getErrorCode() + "]");
        }
    }

    long scalar(String sql, Object... args) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 실패 [SQLState=" + e.getSQLState() + ", code=" + e.getErrorCode() + "]");
        }
    }

    /** 대상 테이블의 마지막 기록 시각. DB 값끼리 비교하므로 세션 time_zone 과 무관하다 */
    Timestamp maxMaskedAt(String table) {
        return (Timestamp) value("SELECT MAX(masked_at) FROM " + t(table));
    }

    /** 원천 테이블 전체 체크섬(건수:CRC 합). 배치 전후로 같아야 원천을 건드리지 않은 것 */
    String checksum(String table) {
        return checksumOf(t(table));
    }

    String seedChecksum() {
        return checksumOf(SEED_TABLE);
    }

    private String checksumOf(String quoted) {
        return String.valueOf(value("SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(CRC32(CONCAT_WS('|', id, call_id, seq, speaker, "
                + "COALESCE(utterance, '~'), created_at))), 0)) FROM " + quoted));
    }

    private Object value(String sql) {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 실패 [SQLState=" + e.getSQLState() + "]");
        }
    }

    /** id 순으로 (id, 원천 텍스트, 대상 텍스트) — 비교에만 쓰고 출력하지 않는다 */
    List<Object[]> pairs(String src, String tgt) {
        String sql = "SELECT s.id, s.utterance, g.utterance FROM " + t(src) + " s JOIN " + t(tgt) + " g ON g.id = s.id ORDER BY s.id";
        List<Object[]> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)});
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 실패 [SQLState=" + e.getSQLState() + "]");
        }
        return out;
    }

    List<String> texts(String table) {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT utterance FROM " + t(table) + " WHERE utterance IS NOT NULL")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 실패 [SQLState=" + e.getSQLState() + "]");
        }
        return out;
    }

    long count(String table) {
        return scalar("SELECT COUNT(*) FROM " + t(table));
    }

    long maxId(String table) {
        return scalar("SELECT COALESCE(MAX(id), 0) FROM " + t(table));
    }

    /**
     * 시드(원본 행만, make_bulk 로 불린 행 제외)를 복사해 원천 테이블을 만든다.
     * multiplier 배로 불리고, id 중간에 구멍을 내고, NULL 텍스트 행을 넣는다.
     */
    void createSource(String table, int multiplier) {
        drop(table);
        exec("CREATE TABLE " + t(table) + " LIKE " + SEED_TABLE);
        exec("ALTER TABLE " + t(table) + " MODIFY utterance TEXT NULL");
        String cols = "call_id, seq, speaker, utterance, created_at";
        exec("INSERT INTO " + t(table) + " (" + cols + ") SELECT " + cols + " FROM " + SEED_TABLE
                + " WHERE call_id NOT LIKE '%-B%' ORDER BY id");
        long seed = count(table);
        if (seed < 1000) {
            throw new IllegalStateException("시드 데이터가 부족합니다(" + seed + "건). run.cmd -ResetDb 로 가짜 데이터를 다시 넣으세요");
        }
        for (int i = 1; i < multiplier; i++) {
            exec("INSERT INTO " + t(table) + " (" + cols + ") SELECT CONCAT(call_id, '-B', ?), seq, speaker, utterance, created_at FROM "
                    + t(table) + " WHERE call_id NOT LIKE '%-B%' ORDER BY id", i);
        }
        // keyset 이 연속 PK 를 가정하지 않는지 보기 위해 구멍을 낸다
        exec("DELETE FROM " + t(table) + " WHERE id % 97 = 0");
        for (int i = 0; i < 3; i++) {
            exec("INSERT INTO " + t(table) + " (" + cols + ") VALUES (?, 1, 'CUSTOMER', NULL, NOW())", "IT-NULL-" + i);
        }
    }

    void insertRows(String table, List<String> utterances, String callId) {
        int seq = 1;
        for (String u : utterances) {
            exec("INSERT INTO " + t(table) + " (call_id, seq, speaker, utterance, created_at) VALUES (?, ?, 'CUSTOMER', ?, NOW())",
                    callId, seq++, u);
        }
    }

    void drop(String table) {
        exec("DROP TABLE IF EXISTS " + t(table));
    }

    void dropUnlessKept(String... tables) {
        if (keepTables()) {
            return;
        }
        for (String table : tables) {
            drop(table);
        }
    }
}
