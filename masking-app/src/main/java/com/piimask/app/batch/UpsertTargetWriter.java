package com.piimask.app.batch;

import com.piimask.app.batch.RunState.MaskedRow;
import com.piimask.app.job.TargetTableService;
import com.piimask.app.support.Sql;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * 대상 테이블에 INSERT ... ON DUPLICATE KEY UPDATE 로 청크를 쓰고 MariaDB 에 바로 커밋한다.
 * 커밋이 끝난 뒤에만 RunState 의 건수와 마지막 PK 를 갱신한다(워터마크 저장은 ProgressListener).
 * 같은 청크를 다시 쓰면 같은 값으로 덮어쓰므로 재처리해도 중복이 생기지 않는다.
 */
final class UpsertTargetWriter implements ItemWriter<MaskedRow> {

    private final DataSource ds;
    private final String insert;
    private final RunState state;

    UpsertTargetWriter(DataSource ds, String targetTable, List<String> insertCols, RunState state) {
        this.ds = ds;
        this.state = state;
        StringBuilder upd = new StringBuilder();
        for (int i = 1; i < insertCols.size(); i++) {
            upd.append(Sql.q(insertCols.get(i))).append(" = VALUES(").append(Sql.q(insertCols.get(i))).append("), ");
        }
        upd.append(Sql.q(TargetTableService.MASKED_AT)).append(" = NOW()");
        this.insert = "INSERT INTO " + Sql.q(targetTable) + " (" + Sql.qList(insertCols) + ", "
                + Sql.q(TargetTableService.MASKED_AT) + ") VALUES (" + Sql.placeholders(insertCols.size())
                + ", NOW()) ON DUPLICATE KEY UPDATE " + upd;
    }

    @Override
    public void write(Chunk<? extends MaskedRow> chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        long masked = 0;
        long spans = 0;
        String lastPk = null;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ins = c.prepareStatement(insert)) {
                for (MaskedRow row : chunk) {
                    Object[] v = row.values();
                    for (int i = 0; i < v.length; i++) {
                        ins.setObject(i + 1, v[i]);
                    }
                    ins.addBatch();
                    if (row.hasPii()) {
                        masked++;
                    }
                    spans += row.spans();
                    lastPk = row.pk();
                }
                ins.executeBatch();
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw SanitizedBatchException.of(e);
        }
        state.read += chunk.size();
        state.written += chunk.size();
        state.maskedRows += masked;
        state.spanCount += spans;
        state.watermark = lastPk;
    }
}
