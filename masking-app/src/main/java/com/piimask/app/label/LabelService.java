package com.piimask.app.label;

import com.piimask.app.connection.ConnectionDtos.ColumnInfo;
import com.piimask.app.connection.ConnectionService;
import com.piimask.app.connection.DataSourceRegistry;
import com.piimask.app.connection.SchemaService;
import com.piimask.app.dict.DictionaryService;
import com.piimask.app.dict.EngineProvider;
import com.piimask.app.label.LabelRepository.Label;
import com.piimask.app.security.CurrentUser;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.AuditService;
import com.piimask.app.support.CryptoService;
import com.piimask.app.support.Errors;
import com.piimask.app.support.Sql;
import com.piimask.core.EntityType;
import com.piimask.core.detect.Detector;
import com.piimask.core.detect.NameRuleDetector;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 교정 라벨: 미리보기에서 "이름 아님(NOT_PII) / 놓침(MISSED)" 을 표시한다.
 * 원문은 요청으로 받지도 저장하지도 않는다. 서버가 원천 행을 다시 읽어(SELECT) 위치를 검증하고 행 지문(HMAC)만 남긴다.
 * 학습용 반출 기능은 없다(보안 승인 절차가 정해진 뒤 별도).
 */
@Service
public class LabelService {

    public static final String NOT_PII = "NOT_PII";
    public static final String MISSED = "MISSED";
    static final String FP_PURPOSE = "span-label-row";
    static final int MAX_SPAN = 100;
    static final int MAX_ROWS_PER_QUERY = 200;

    public record AddRequest(Long connectionId, String sourceTable, String pkColumn, String pk, String column,
                             Integer start, Integer end, String label, String entityType, String detector, Long jobId,
                             Boolean addException) {
    }

    public record QueryRequest(Long connectionId, String sourceTable, String pkColumn, List<String> pks) {
    }

    /** 화면용 라벨(원문 없음). stale: 저장 뒤 원천 값이 바뀌어 위치를 믿을 수 없음 */
    public record LabelView(long id, String pk, String column, int start, int end, String label, String entityType,
                            String detector, String createdBy, String createdAt, boolean stale) {
    }

    public record SummaryRow(long connectionId, String sourceTable, String label, String entityType, String detector,
                             int count, Integer stale) {
    }

    private final LabelRepository repo;
    private final ConnectionService connections;
    private final DataSourceRegistry registry;
    private final SchemaService schema;
    private final CryptoService crypto;
    private final EngineProvider engines;
    private final DictionaryService dictionary;
    private final AuditService audit;

    public LabelService(LabelRepository repo, ConnectionService connections, DataSourceRegistry registry, SchemaService schema,
                        CryptoService crypto, EngineProvider engines, DictionaryService dictionary, AuditService audit) {
        this.repo = repo;
        this.connections = connections;
        this.registry = registry;
        this.schema = schema;
        this.crypto = crypto;
        this.engines = engines;
        this.dictionary = dictionary;
        this.audit = audit;
    }

    public LabelView add(AddRequest r) {
        if (r == null || r.connectionId() == null || r.pk() == null || r.start() == null || r.end() == null
                || r.sourceTable() == null || r.pkColumn() == null || r.column() == null) {
            throw ApiException.badRequest("접속·테이블·PK·컬럼·위치가 필요합니다");
        }
        String label = r.label();
        if (!NOT_PII.equals(label) && !MISSED.equals(label)) {
            throw ApiException.badRequest("label 은 NOT_PII 또는 MISSED 입니다");
        }
        EntityType type;
        try {
            type = EntityType.valueOf(r.entityType());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("알 수 없는 탐지 유형입니다");
        }
        if (type != EntityType.NAME) {
            throw ApiException.badRequest("지금은 이름 유형만 교정 라벨을 달 수 있습니다");
        }
        String detector = null;
        if (NOT_PII.equals(label)) {
            detector = r.detector();
            if (detector == null || !knownDetectors().contains(detector)) {
                throw ApiException.badRequest("탐지기 이름이 올바르지 않습니다");
            }
        }
        if (Boolean.TRUE.equals(r.addException()) && !NOT_PII.equals(label)) {
            throw ApiException.badRequest("예외 사전 추가는 '이름 아님' 라벨에서만 할 수 있습니다");
        }
        validateColumns(r.connectionId(), r.sourceTable(), r.pkColumn(), List.of(r.column()));
        if (r.pk().length() > 255) {
            throw ApiException.badRequest("PK 값이 너무 깁니다");
        }

        String text = readTexts(r.connectionId(), r.sourceTable(), r.pkColumn(), r.column(), List.of(r.pk())).get(r.pk());
        if (text == null) {
            throw ApiException.notFound("원천 행이 없거나 값이 비어 있습니다");
        }
        int s = r.start();
        int e = r.end();
        if (s < 0 || e > text.length() || s >= e || e - s > MAX_SPAN) {
            throw ApiException.badRequest("위치가 원천 값 범위를 벗어났습니다(1~" + MAX_SPAN + "자)");
        }

        String fp = crypto.fingerprint(FP_PURPOSE, text);
        boolean withException = Boolean.TRUE.equals(r.addException());
        if (withException) {
            // 사전 규칙(길이·문자)에 맞지 않으면 라벨도 저장하지 않는다
            try {
                com.piimask.core.dict.NameDictionary.cleanTerm(text.substring(s, e));
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("예외 사전에 넣을 수 없는 단어입니다: " + ex.getMessage());
            }
        }
        // 같은 자리에는 라벨 하나. 원천이 바뀌어 무효가 된 라벨이면 새 라벨로 바꾼다
        repo.at(r.connectionId(), r.sourceTable(), r.pk(), r.column(), s, e).ifPresent(old -> {
            if (old.rowFp().equals(fp)) {
                throw ApiException.conflict(old.label().equals(label) ? "이미 같은 라벨이 있습니다"
                        : "같은 자리에 다른 라벨이 있습니다. 먼저 지우세요");
            }
            repo.delete(old.id());
            audit.log("LABEL_DELETE", "label#" + old.id() + " " + old.label() + " " + old.entityType() + " stale", "OK");
        });
        Label l = new Label(0, r.connectionId(), r.sourceTable(), r.pkColumn(), r.pk(), r.column(), s, e, label,
                type.name(), detector, r.jobId(), fp, CurrentUser.name(), null);
        long id;
        try {
            id = repo.insert(l);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("이미 같은 라벨이 있습니다");
        }
        if (withException) {
            // 예외어는 평문으로 저장된다(사용자가 체크했을 때만). 사전 서비스가 감사 로그(건수만)를 남긴다.
            // 사전 추가가 실패하면 라벨도 되돌린다(재시도 때 409 가 나지 않게)
            try {
                dictionary.add("EXCEPT", List.of(text.substring(s, e)));
            } catch (RuntimeException ex) {
                repo.delete(id);
                throw ex;
            }
        }
        audit.log("LABEL_ADD", "label#" + id + " " + label + " " + type.name()
                + (r.jobId() == null ? "" : " job#" + r.jobId()) + (withException ? " +EXCEPT" : ""), "OK");
        return view(repo.find(id).orElseThrow(), false);
    }

    public List<LabelView> forRows(QueryRequest q) {
        if (q == null || q.connectionId() == null || q.pks() == null) {
            throw ApiException.badRequest("접속·PK 목록이 필요합니다");
        }
        if (q.pks().size() > MAX_ROWS_PER_QUERY) {
            throw ApiException.badRequest("한 번에 " + MAX_ROWS_PER_QUERY + "행까지 조회할 수 있습니다");
        }
        validateColumns(q.connectionId(), q.sourceTable(), q.pkColumn(), List.of());
        List<Label> labels = repo.forRows(q.connectionId(), q.sourceTable(), new HashSet<>(q.pks())).stream()
                .filter(l -> l.pkColumn().equals(q.pkColumn())).toList();
        Map<String, Set<String>> pksByColumn = new HashMap<>();
        labels.forEach(l -> pksByColumn.computeIfAbsent(l.column(), c -> new HashSet<>()).add(l.pkValue()));
        Map<String, Map<String, String>> current = new HashMap<>();
        for (var en : pksByColumn.entrySet()) {
            try {
                validateColumns(q.connectionId(), q.sourceTable(), q.pkColumn(), List.of(en.getKey()));
                current.put(en.getKey(), fingerprints(q.connectionId(), q.sourceTable(), q.pkColumn(), en.getKey(), en.getValue()));
            } catch (ApiException ex) {
                current.put(en.getKey(), Map.of()); // 컬럼이 없어졌으면 그 라벨은 모두 무효
            }
        }
        return labels.stream()
                .map(l -> view(l, !l.rowFp().equals(current.getOrDefault(l.column(), Map.of()).get(l.pkValue()))))
                .toList();
    }

    public void delete(long id) {
        Label l = repo.find(id).orElseThrow(() -> ApiException.notFound("라벨이 없습니다"));
        if (!repo.delete(id)) {
            throw ApiException.notFound("라벨이 없습니다");
        }
        audit.log("LABEL_DELETE", "label#" + id + " " + l.label() + " " + l.entityType(), "OK");
    }

    /**
     * 종류·유형·탐지기별 건수(원문·위치 없음). 메타 DB 만 센다.
     * connectionId·table 을 정하면 그 테이블만 원천을 다시 읽어 무효(원천 변경) 수도 센다. 정하지 않으면 stale 은 null.
     */
    public List<SummaryRow> summary(Long connectionId, String table) {
        boolean scoped = connectionId != null && table != null;
        Map<String, Integer> stale = new HashMap<>();
        if (scoped) {
            Map<String, List<Label>> byTarget = new LinkedHashMap<>();
            for (Label l : repo.forTable(connectionId, table)) {
                byTarget.computeIfAbsent(l.pkColumn() + "\u0000" + l.column(), k -> new ArrayList<>()).add(l);
            }
            for (List<Label> group : byTarget.values()) {
                Label first = group.get(0);
                Map<String, String> fps;
                try {
                    validateColumns(connectionId, table, first.pkColumn(), List.of(first.column()));
                    Set<String> pks = new HashSet<>();
                    group.forEach(l -> pks.add(l.pkValue()));
                    fps = fingerprints(connectionId, table, first.pkColumn(), first.column(), pks);
                } catch (ApiException ex) {
                    fps = Map.of(); // 테이블·컬럼이 없어지면 전부 무효로 센다
                }
                for (Label l : group) {
                    if (!l.rowFp().equals(fps.get(l.pkValue()))) {
                        stale.merge(key(l.label(), l.entityType(), l.detector()), 1, Integer::sum);
                    }
                }
            }
        }
        List<SummaryRow> out = new ArrayList<>();
        for (Object[] c : repo.counts(scoped ? connectionId : null, table)) {
            String k = key((String) c[2], (String) c[3], (String) c[4]);
            out.add(new SummaryRow((Long) c[0], (String) c[1], (String) c[2], (String) c[3], (String) c[4], (Integer) c[5],
                    scoped ? stale.getOrDefault(k, 0) : null));
        }
        return out;
    }

    private static String key(String label, String type, String detector) {
        return label + "\u0000" + type + "\u0000" + (detector == null ? "" : detector);
    }

    private LabelView view(Label l, boolean stale) {
        return new LabelView(l.id(), l.pkValue(), l.column(), l.start(), l.end(), l.label(), l.entityType(), l.detector(),
                l.createdBy(), l.createdAt(), stale);
    }

    /** 지금 엔진의 탐지기 이름(+ ITN·점수·문맥 표시). 화면이 보낸 탐지기 문자열에 다른 값이 섞이지 않게 */
    private Set<String> knownDetectors() {
        Set<String> names = new HashSet<>();
        for (Detector d : engines.current().detectors()) {
            names.add(d.name());
            names.add(d.name() + com.piimask.core.MaskingEngine.ITN_SUFFIX);
        }
        names.add(NameRuleDetector.SCORE_DETECTOR);
        names.add(NameRuleDetector.CONTEXT_DETECTOR);
        return names;
    }

    /** 테이블·PK·컬럼이 원천 메타데이터에 있는지(동적 SQL 에 넣기 전에) */
    private void validateColumns(long connectionId, String table, String pkColumn, List<String> columns) {
        Sql.requireIdentifier(table, "원천 테이블");
        Sql.requireIdentifier(pkColumn, "PK 컬럼");
        Map<String, ColumnInfo> cols = new HashMap<>();
        schema.columns(connectionId, table).forEach(c -> cols.put(c.name(), c));
        ColumnInfo pk = cols.get(pkColumn);
        if (pk == null || !pk.primaryKey() || cols.values().stream().filter(ColumnInfo::primaryKey).count() != 1) {
            throw ApiException.badRequest("PK 컬럼은 원천 테이블의 단일 기본키여야 합니다");
        }
        for (String c : columns) {
            Sql.requireIdentifier(c, "컬럼");
            if (!cols.containsKey(c)) {
                throw ApiException.badRequest("원천 테이블에 없는 컬럼입니다: " + c);
            }
        }
    }

    private Map<String, String> fingerprints(long connectionId, String table, String pkColumn, String column, Set<String> pks) {
        Map<String, String> out = new HashMap<>();
        readTexts(connectionId, table, pkColumn, column, pks).forEach((pk, t) -> out.put(pk, crypto.fingerprint(FP_PURPOSE, t)));
        return out;
    }

    /** 원천에서 PK 들의 값을 읽는다(SELECT 만). 결과는 메모리에서만 쓰고 저장·로그하지 않는다 */
    private Map<String, String> readTexts(long connectionId, String table, String pkColumn, String column, java.util.Collection<String> pks) {
        Map<String, String> out = new HashMap<>();
        if (pks.isEmpty()) {
            return out;
        }
        List<String> list = new ArrayList<>(pks);
        DataSource ds = registry.get(connections.secret(connectionId));
        for (int from = 0; from < list.size(); from += 500) {
            List<String> part = list.subList(from, Math.min(list.size(), from + 500));
            String sql = "SELECT " + Sql.q(pkColumn) + ", " + Sql.q(column) + " FROM " + Sql.q(table) + " WHERE " + Sql.q(pkColumn)
                    + " IN (" + Sql.placeholders(part.size()) + ")";
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < part.size(); i++) {
                    ps.setString(i + 1, part.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String v = rs.getString(2);
                        if (v != null) {
                            out.put(rs.getString(1), v);
                        }
                    }
                }
            } catch (SQLException | com.zaxxer.hikari.pool.HikariPool.PoolInitializationException e) {
                throw ApiException.badRequest("원천 조회 실패: " + Errors.safeMessage(e));
            }
        }
        return out;
    }
}
