package com.piimask.app.connection;

import com.piimask.app.connection.ConnectionDtos.ColumnInfo;
import com.piimask.app.connection.ConnectionDtos.ConnectionSecret;
import com.piimask.app.connection.ConnectionDtos.TableInfo;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.Errors;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** information_schema 로 원천 DB 구조를 조회한다(MariaDB/MySQL 전용). */
@Service
public class SchemaService {

    private static final Set<String> TEXT_TYPES = Set.of(
            "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "json");

    private final ConnectionService connections;
    private final DataSourceRegistry registry;

    public SchemaService(ConnectionService connections, DataSourceRegistry registry) {
        this.connections = connections;
        this.registry = registry;
    }

    public List<TableInfo> tables(long connectionId) {
        ConnectionSecret s = connections.secret(connectionId);
        String sql = "SELECT TABLE_NAME, COALESCE(TABLE_ROWS, 0) AS TABLE_ROWS, COALESCE(TABLE_COMMENT, '') AS TABLE_COMMENT "
                + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
        try (Connection c = registry.get(s).getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, s.database());
            List<TableInfo> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TableInfo(rs.getString(1), rs.getLong(2), rs.getString(3)));
                }
            }
            return out;
        } catch (SQLException | com.zaxxer.hikari.pool.HikariPool.PoolInitializationException e) {
            throw ApiException.badRequest("테이블 목록 조회 실패: " + Errors.safeMessage(e));
        }
    }

    public List<ColumnInfo> columns(long connectionId, String table) {
        ConnectionSecret s = connections.secret(connectionId);
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COALESCE(COLUMN_COMMENT, '') "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection c = registry.get(s).getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, s.database());
            ps.setString(2, table);
            List<ColumnInfo> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String dataType = rs.getString(2).toLowerCase();
                    out.add(new ColumnInfo(rs.getString(1), dataType, rs.getString(3),
                            "YES".equalsIgnoreCase(rs.getString(4)),
                            "PRI".equalsIgnoreCase(rs.getString(5)),
                            TEXT_TYPES.contains(dataType), rs.getString(6)));
                }
            }
            if (out.isEmpty()) {
                throw ApiException.notFound("테이블을 찾을 수 없습니다: " + table);
            }
            return out;
        } catch (SQLException | com.zaxxer.hikari.pool.HikariPool.PoolInitializationException e) {
            throw ApiException.badRequest("컬럼 조회 실패: " + Errors.safeMessage(e));
        }
    }

    public boolean tableExists(long connectionId, String table) {
        return tables(connectionId).stream().anyMatch(t -> t.name().equals(table));
    }
}
