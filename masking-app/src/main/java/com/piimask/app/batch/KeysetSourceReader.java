package com.piimask.app.batch;

import com.piimask.app.batch.RunState.SourceRow;
import com.piimask.app.support.Sql;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * keyset 리더: PK > 마지막으로 읽은 PK 인 행을 PK 순으로 pageSize 만큼씩 읽는다. 원천에는 SELECT 만 한다.
 * 페이지 크기 = 청크 크기라서 한 청크가 한 번의 SELECT 에 대응한다.
 * 정수형 PK 는 숫자로 바인딩한다. MariaDB 11.4 는 문자열과도 정확히 비교하지만(확인함),
 * 비교 의미를 DB 버전·호환 DB 의 형변환 규칙에 맡기지 않기 위해서다.
 */
final class KeysetSourceReader implements ItemStreamReader<SourceRow> {

    static final Set<String> INTEGER_TYPES = Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint");

    private final DataSource ds;
    private final String selectFirst;
    private final String selectNext;
    private final int pageSize;
    private final int columnCount;
    private final int firstMaskIndex;
    private final boolean integerPk;
    private final Deque<SourceRow> buffer = new ArrayDeque<>();
    private String lastPk;
    private boolean exhausted;

    KeysetSourceReader(DataSource ds, String table, String pkColumn, List<String> selectCols, int firstMaskIndex,
                       boolean integerPk, int pageSize, String startAfter) {
        this.ds = ds;
        this.pageSize = pageSize;
        this.columnCount = selectCols.size();
        this.firstMaskIndex = firstMaskIndex;
        this.integerPk = integerPk;
        this.lastPk = startAfter;
        String base = "SELECT " + Sql.qList(selectCols) + " FROM " + Sql.q(table);
        String order = " ORDER BY " + Sql.q(pkColumn) + " LIMIT ?";
        this.selectFirst = base + order;
        this.selectNext = base + " WHERE " + Sql.q(pkColumn) + " > ?" + order;
    }

    @Override
    public SourceRow read() {
        if (buffer.isEmpty() && !exhausted) {
            fetchPage();
        }
        return buffer.poll();
    }

    private void fetchPage() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(lastPk == null ? selectFirst : selectNext)) {
            int p = 1;
            if (lastPk != null) {
                if (integerPk) {
                    ps.setBigDecimal(p++, new BigDecimal(lastPk));
                } else {
                    ps.setString(p++, lastPk);
                }
            }
            ps.setInt(p, pageSize);
            int n = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object[] values = new Object[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        // MASK 컬럼은 문자열로(엔진 입력), 나머지는 타입 그대로 복사
                        values[i] = i >= firstMaskIndex ? rs.getString(i + 1) : rs.getObject(i + 1);
                    }
                    String pk = rs.getString(1);
                    buffer.add(new SourceRow(pk, values));
                    lastPk = pk;
                    n++;
                }
            }
            if (n < pageSize) {
                exhausted = true;
            }
        } catch (Exception e) {
            throw SanitizedBatchException.of(e);
        }
    }
}
