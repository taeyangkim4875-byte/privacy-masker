package com.piimask.app.batch;

import com.piimask.app.batch.RunState.MaskedRow;
import com.piimask.app.batch.RunState.SourceRow;
import com.piimask.core.DetectionContext;
import com.piimask.core.EntityType;
import com.piimask.core.MaskingEngine;
import com.piimask.core.mask.MaskingPolicy;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 원천 행 → 대상 행. 값 순서: [PK, COPY..., MASK...]. COPY 는 그대로, MASK 는 엔진으로 치환한다.
 * 결과에는 원문을 남기지 않고 탐지 건수만 붙인다.
 */
final class MaskingProcessor implements ItemProcessor<SourceRow, MaskedRow> {

    private final MaskingEngine engine;
    private final int firstMaskIndex;
    private final List<Set<EntityType>> maskEntities;
    private final Map<EntityType, MaskingPolicy> policies;
    private final String salt;
    /** 대화 문맥(없으면 null). 행을 PK 순서로 처리하므로 통화별 상태를 이어 간다 */
    private final DialogBinding dialog;

    MaskingProcessor(MaskingEngine engine, int copyCount, List<Set<EntityType>> maskEntities,
                     Map<EntityType, MaskingPolicy> policies, String salt) {
        this(engine, copyCount, maskEntities, policies, salt, null);
    }

    MaskingProcessor(MaskingEngine engine, int copyCount, List<Set<EntityType>> maskEntities,
                     Map<EntityType, MaskingPolicy> policies, String salt, DialogBinding dialog) {
        this.dialog = dialog;
        this.engine = engine;
        this.firstMaskIndex = 1 + copyCount;
        this.maskEntities = maskEntities;
        this.policies = policies;
        this.salt = salt;
    }

    @Override
    public MaskedRow process(SourceRow row) {
        try {
            // 문맥은 마스킹 전 원본 값으로 정한다
            DetectionContext ctx = dialog == null ? DetectionContext.NONE : dialog.contextFor(row.pk(), row.values());
            Object[] out = row.values().clone();
            boolean hasPii = false;
            int spans = 0;
            for (int i = 0; i < maskEntities.size(); i++) {
                int col = firstMaskIndex + i;
                String text = (String) out[col];
                if (text == null) {
                    continue;
                }
                // 문맥은 발화(첫 마스킹 컬럼)에만 적용한다
                MaskingEngine.Result r = engine.mask(text, maskEntities.get(i), policies, salt, i == 0 ? ctx : DetectionContext.NONE);
                if (!r.spans().isEmpty()) {
                    hasPii = true;
                    spans += r.spans().size();
                }
                out[col] = r.masked();
            }
            return new MaskedRow(row.pk(), out, hasPii, spans);
        } catch (RuntimeException e) {
            throw SanitizedBatchException.of(e);
        }
    }
}
