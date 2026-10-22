package com.piimask.app.job;

import com.piimask.app.connection.ConnectionService;
import com.piimask.app.connection.DataSourceRegistry;
import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.job.JobDtos.PreviewCell;
import com.piimask.app.job.JobDtos.PreviewResult;
import com.piimask.app.job.JobDtos.PreviewRow;
import com.piimask.app.job.JobDtos.SpanView;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.Errors;
import com.piimask.app.support.Sql;
import com.piimask.core.MaskingEngine;
import com.piimask.core.Span;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 원천 테이블 앞부분 N건에 마스킹을 적용해 보여준다. 대상 테이블에는 쓰지 않는다.
 * 화면 표시용으로만 원문을 내려주며, 서버 로그에는 남기지 않는다.
 */
@Service
public class PreviewService {

    private final ConnectionService connections;
    private final DataSourceRegistry registry;
    private final MaskingEngine engine;

    public PreviewService(ConnectionService connections, DataSourceRegistry registry, MaskingEngine engine) {
        this.connections = connections;
        this.registry = registry;
        this.engine = engine;
    }

    public PreviewResult preview(JobDef job, int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        List<ColumnSpec> maskCols = job.maskColumns();
        List<String> names = maskCols.stream().map(ColumnSpec::column).toList();
        String sql = "SELECT " + Sql.q(job.pkColumn()) + ", " + Sql.qList(names)
                + " FROM " + Sql.q(job.sourceTable()) + " ORDER BY " + Sql.q(job.pkColumn()) + " LIMIT ?";
        long t0 = System.currentTimeMillis();
        Map<String, Integer> counts = new TreeMap<>();
        List<PreviewRow> rows = new ArrayList<>();
        try (Connection c = registry.get(connections.secret(job.connectionId())).getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<PreviewCell> cells = new ArrayList<>();
                    for (int i = 0; i < maskCols.size(); i++) {
                        ColumnSpec col = maskCols.get(i);
                        String text = rs.getString(i + 2);
                        if (text == null) {
                            cells.add(new PreviewCell(col.column(), null, null, List.of()));
                            continue;
                        }
                        MaskingEngine.Result r = engine.mask(text, JobDef.entitiesOf(col), job.policies(), job.hashSalt());
                        for (Span s : r.spans()) {
                            counts.merge(s.type().name(), 1, Integer::sum);
                        }
                        cells.add(new PreviewCell(col.column(), text, r.masked(), toViews(r.spans())));
                    }
                    rows.add(new PreviewRow(rs.getString(1), cells));
                }
            }
        } catch (SQLException | com.zaxxer.hikari.pool.HikariPool.PoolInitializationException e) {
            throw ApiException.badRequest("미리보기 조회 실패: " + Errors.safeMessage(e));
        }
        return new PreviewResult(rows, System.currentTimeMillis() - t0, counts);
    }

    public static List<SpanView> toViews(List<Span> spans) {
        return spans.stream()
                .map(s -> new SpanView(s.start(), s.end(), s.type().name(), s.type().label(), s.detector()))
                .toList();
    }
}
