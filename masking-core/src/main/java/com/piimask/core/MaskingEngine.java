package com.piimask.core;

import com.piimask.core.detect.DefaultDetectors;
import com.piimask.core.detect.Detector;
import com.piimask.core.mask.Masker;
import com.piimask.core.mask.MaskingPolicy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 탐지 → 병합 → 치환 파이프라인. 스레드 안전.
 * 2단계에서 ITN 정규화, 3단계에서 NER 탐지기가 detectors 목록에 추가된다.
 */
public final class MaskingEngine {

    public record Result(String masked, List<Span> spans) {
    }

    private final List<Detector> detectors;

    public MaskingEngine(List<Detector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    public static MaskingEngine withDefaults() {
        return new MaskingEngine(DefaultDetectors.all());
    }

    /** 기본 탐지기에 사용자 정의 탐지기를 더한 새 엔진 */
    public MaskingEngine with(List<Detector> extra) {
        List<Detector> all = new ArrayList<>(detectors);
        all.addAll(extra);
        return new MaskingEngine(all);
    }

    public List<Detector> detectors() {
        return detectors;
    }

    public List<Span> detect(String text, Set<EntityType> enabled) {
        if (text == null || text.isEmpty() || enabled.isEmpty()) {
            return List.of();
        }
        List<Span> raw = new ArrayList<>();
        for (Detector d : detectors) {
            if (enabled.contains(d.type())) {
                raw.addAll(d.detect(text));
            }
        }
        return SpanMerger.merge(raw);
    }

    public List<Span> detect(String text) {
        return detect(text, EnumSet.allOf(EntityType.class));
    }

    public Result mask(String text, Set<EntityType> enabled, Map<EntityType, MaskingPolicy> policies, String salt) {
        List<Span> spans = detect(text, enabled);
        String masked = Masker.apply(text, spans, t -> policies.getOrDefault(t, MaskingPolicy.TAG), salt);
        return new Result(masked, spans);
    }
}
