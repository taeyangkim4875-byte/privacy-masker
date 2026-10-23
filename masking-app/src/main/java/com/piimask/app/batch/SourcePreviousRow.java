package com.piimask.app.batch;

import com.piimask.app.support.Sql;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 원천에서 같은 통화의 직전 행 1건(화자·발화)을 시작 워터마크 이하 범위에서 읽는다. SELECT 만.
 * (통화 컬럼, PK) 인덱스가 없으면 조회마다 PK 를 거꾸로 훑을 수 있다. 조회는 실행당 상한이 있다(DialogBinding).
 * 테이블·컬럼명은 작업 저장 때 메타데이터로 확인한 값이고 {@link Sql#q} 로만 넣는다.
 */
final class SourcePreviousRow implements DialogBinding.PreviousRow {

    private final DataSource ds;
    private final String sql;
    private final boolean integerPk;

    SourcePreviousRow(DataSource ds, String table, String pkColumn, String callColumn, String speakerColumn,
                      String textColumn, boolean integerPk) {
        this.ds = ds;
        this.integerPk = integerPk;
        this.sql = "SELECT " + Sql.q(speakerColumn) + ", " + Sql.q(textColumn) + " FROM " + Sql.q(table)
                + " WHERE " + Sql.q(callColumn) + " = ? AND " + Sql.q(pkColumn) + " < ? AND " + Sql.q(pkColumn)
                + " <= ? ORDER BY " + Sql.q(pkColumn)
                + " DESC LIMIT 1";
    }

    @Override
    public Optional<Object[]> find(String callId, String pk, String upTo) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, callId);
            if (integerPk) {
                ps.setBigDecimal(2, new BigDecimal(pk));
                ps.setBigDecimal(3, new BigDecimal(upTo));
            } else {
                ps.setString(2, pk);
                ps.setString(3, upTo);
            }
            try (ResultSet rs = ps.executeQuery()) {
                // 화자는 배치 리더(COPY 컬럼은 getObject)와 같은 방식으로 읽어 같은 규칙(DialogBinding.str)으로 비교한다
                return rs.next() ? Optional.of(new Object[]{rs.getObject(1), rs.getString(2)}) : Optional.empty();
            }
        } catch (SQLException e) {
            // 메시지에 값이 섞일 수 있으므로 원인을 붙이지 않는다(프로세서가 SanitizedBatchException 으로 감싼다)
            throw new IllegalStateException("직전 발화 조회 실패: SQLState=" + e.getSQLState());
        }
    }
}
