package com.piimask.app.job;

import com.piimask.app.connection.ConnectionDtos.ColumnInfo;
import com.piimask.app.connection.ConnectionService;
import com.piimask.app.connection.DataSourceRegistry;
import com.piimask.app.connection.SchemaService;
import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.job.JobDtos.DdlView;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.Errors;
import com.piimask.app.support.Sql;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 마스킹 결과 테이블 DDL 생성·검증.
 * 구조: 원천 PK + COPY 컬럼(원천 타입 그대로) + MASK 컬럼(TEXT 계열) + masked_at
 */
@Service
public class TargetTableService {

    public static final String MASKED_AT = "masked_at";
    private static final Set<String> BIG_TEXT = Set.of("mediumtext", "longtext");

    private final SchemaService schema;
    private final ConnectionService connections;
    private final DataSourceRegistry registry;

    public TargetTableService(SchemaService schema, ConnectionService connections, DataSourceRegistry registry) {
        this.schema = schema;
        this.connections = connections;
        this.registry = registry;
    }

    public DdlView inspect(JobDef job) {
        String ddl = ddl(job);
        if (!schema.tableExists(job.connectionId(), job.targetTable())) {
            return new DdlView(ddl, false, List.of());
        }
        Set<String> existing = schema.columns(job.connectionId(), job.targetTable()).stream()
                .map(ColumnInfo::name).collect(Collectors.toSet());
        List<String> missing = requiredColumns(job).stream().filter(c -> !existing.contains(c)).toList();
        return new DdlView(ddl, true, missing);
    }

    public String ddl(JobDef job) {
        Map<String, ColumnInfo> src = schema.columns(job.connectionId(), job.sourceTable()).stream()
                .collect(Collectors.toMap(ColumnInfo::name, Function.identity()));
        ColumnInfo pk = src.get(job.pkColumn());
        if (pk == null) {
            throw ApiException.badRequest("PK 컬럼이 원천 테이블에 없습니다: " + job.pkColumn());
        }
        List<String> lines = new ArrayList<>();
        lines.add("  " + Sql.q(pk.name()) + " " + pk.columnType() + " NOT NULL");
        for (ColumnSpec c : job.copyColumns()) {
            ColumnInfo ci = src.get(c.column());
            lines.add("  " + Sql.q(c.column()) + " " + ci.columnType() + " NULL");
        }
        for (ColumnSpec c : job.maskColumns()) {
            ColumnInfo ci = src.get(c.column());
            String type = BIG_TEXT.contains(ci.dataType()) ? ci.dataType().toUpperCase() : "TEXT";
            lines.add("  " + Sql.q(c.column()) + " " + type + " NULL");
        }
        lines.add("  " + Sql.q(MASKED_AT) + " DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        lines.add("  PRIMARY KEY (" + Sql.q(pk.name()) + ")");
        return "CREATE TABLE IF NOT EXISTS " + Sql.q(job.targetTable()) + " (\n"
                + String.join(",\n", lines)
                + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PII masked copy of " + job.sourceTable() + "'";
    }

    public void create(JobDef job) {
        DdlView v = inspect(job);
        if (v.exists()) {
            if (!v.missingColumns().isEmpty()) {
                throw ApiException.conflict("대상 테이블이 이미 있고 필요한 컬럼이 없습니다: " + v.missingColumns());
            }
            return;
        }
        try (Connection c = registry.get(connections.secret(job.connectionId())).getConnection();
             Statement st = c.createStatement()) {
            st.execute(v.ddl());
        } catch (SQLException | com.zaxxer.hikari.pool.HikariPool.PoolInitializationException e) {
            throw ApiException.badRequest("대상 테이블 생성 실패: " + Errors.safeMessage(e));
        }
    }

    private List<String> requiredColumns(JobDef job) {
        List<String> cols = new ArrayList<>();
        cols.add(job.pkColumn());
        job.columns().forEach(c -> cols.add(c.column()));
        cols.add(MASKED_AT);
        return cols;
    }
}
