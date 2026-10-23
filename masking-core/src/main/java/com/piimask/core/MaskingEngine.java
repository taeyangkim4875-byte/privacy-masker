package com.piimask.core;

import com.piimask.core.detect.DefaultDetectors;
import com.piimask.core.detect.Detector;
import com.piimask.core.detect.DictionaryNameDetector;
import com.piimask.core.detect.NameRuleDetector;
import com.piimask.core.dict.NameDictionary;
import com.piimask.core.mask.Masker;
import com.piimask.core.mask.MaskingPolicy;
import com.piimask.core.normalize.KoreanDigits;
import com.piimask.core.normalize.NumberNormalizer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 탐지 → 병합 → 치환 파이프라인. 스레드 안전.
 * ITN: 한글로 읽은 숫자("공일공 사팔이일…")를 정규화한 텍스트에서 숫자형 탐지기를 한 번 더 돌리고,
 * 스팬을 원문 위치로 되돌려 합친다. 한글 숫자를 덮는 스팬만 채택하므로 한글 숫자가 없는 텍스트의 결과는 그대로다.
 * 3단계에서 NER 탐지기가 detectors 목록에 추가된다.
 */
public final class MaskingEngine {

    public record Result(String masked, List<Span> spans) {
    }

    /** 정규화 텍스트에서 다시 돌리는 유형(숫자형). 이메일·이름은 원문에서만 */
    static final Set<EntityType> ITN_TYPES = EnumSet.of(EntityType.PHONE, EntityType.CARD, EntityType.RRN, EntityType.ACCOUNT);
    public static final String ITN_SUFFIX = " (ITN)";

    private final List<Detector> detectors;
    private final boolean itn;

    public MaskingEngine(List<Detector> detectors) {
        this(detectors, true);
    }

    public MaskingEngine(List<Detector> detectors, boolean itn) {
        this.detectors = List.copyOf(detectors);
        this.itn = itn;
    }

    public static MaskingEngine withDefaults() {
        return new MaskingEngine(DefaultDetectors.all());
    }

    /** 기본 탐지기에 사용자 정의 탐지기를 더한 새 엔진 */
    public MaskingEngine with(List<Detector> extra) {
        List<Detector> all = new ArrayList<>(detectors);
        all.addAll(extra);
        return new MaskingEngine(all, itn);
    }

    /**
     * 이름 사전을 바꾼 새 엔진: 규칙 이름 탐지기를 이 사전으로 다시 만들고, 추가 탐지어가 있으면 사전 탐지기를 그 뒤에 둔다.
     * 다른 탐지기와 ITN 설정은 그대로.
     */
    public MaskingEngine withDictionary(NameDictionary dict) {
        List<Detector> out = new ArrayList<>();
        for (Detector d : detectors) {
            if (d instanceof DictionaryNameDetector) {
                continue;
            }
            if (d instanceof NameRuleDetector nr) {
                out.add(new NameRuleDetector(dict, nr.scoring()));
                if (!dict.forced().isEmpty()) {
                    out.add(new DictionaryNameDetector(dict.forced()));
                }
            } else {
                out.add(d);
            }
        }
        if (!dict.forced().isEmpty() && out.stream().noneMatch(d -> d instanceof DictionaryNameDetector)) {
            out.add(new DictionaryNameDetector(dict.forced()));
        }
        return new MaskingEngine(out, itn);
    }

    /** 이름 점수화를 끈 같은 엔진(강한 단서 규칙만, 평가·회귀 비교용) */
    public MaskingEngine withoutNameScoring() {
        List<Detector> out = new ArrayList<>();
        for (Detector d : detectors) {
            out.add(d instanceof NameRuleDetector nr ? new NameRuleDetector(nr.dictionary(), false) : d);
        }
        return new MaskingEngine(out, itn);
    }

    /** ITN 을 끈 같은 엔진(회귀 비교용) */
    public MaskingEngine withoutItn() {
        return new MaskingEngine(detectors, false);
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
        if (itn) {
            raw.addAll(detectNormalized(text, enabled));
        }
        return SpanMerger.merge(raw);
    }

    private List<Span> detectNormalized(String text, Set<EntityType> enabled) {
        List<NumberNormalizer.Normalized> variants = NumberNormalizer.normalize(text);
        if (variants.isEmpty()) {
            return List.of();
        }
        List<List<Span>> perVariant = new ArrayList<>();
        for (NumberNormalizer.Normalized v : variants) {
            List<Span> found = new ArrayList<>();
            for (Detector d : detectors) {
                if (!ITN_TYPES.contains(d.type()) || !enabled.contains(d.type())) {
                    continue;
                }
                for (Span s : d.detect(v.text())) {
                    int[] o = v.map().toOriginal(s.start(), s.end());
                    // 한글 숫자를 덮지 않는 스팬은 원문 탐지와 같으므로 버린다
                    if (o[1] > o[0] && KoreanDigits.containsKoreanDigit(text, o[0], o[1])) {
                        found.add(new Span(o[0], o[1], s.type(), s.detector() + ITN_SUFFIX));
                    }
                }
            }
            perVariant.add(found);
        }
        // 끝 "이" 해석: "이" 를 뺀 해석에서 바로 앞까지 잡혔으면 "이" 를 숫자로 본 해석의 같은 자리 스팬은 버린다
        // ("칠칠삼육이요" 가 전화번호 + 조사일 때 "이" 까지 덮어 계좌 등으로 잡히는 것 방지)
        List<Span> out = new ArrayList<>();
        for (int k = 0; k < variants.size(); k++) {
            NumberNormalizer.Normalized v = variants.get(k);
            for (Span s : perVariant.get(k)) {
                if (!v.tailStripped() && coveredByStripped(text, s, v.ambiguousTails(), variants, perVariant)) {
                    continue;
                }
                out.add(s);
            }
        }
        return out;
    }

    /**
     * "이" 를 뺀 해석에 같거나 더 높은 우선순위의 스팬이 그 "이" 바로 앞(구분자만 사이에 둠)에서 끝나면 true.
     * 우선순위를 보는 이유: "…칠팔사이입니다" 처럼 "이" 를 빼면 전화가 아니라 계좌 형태만 남는 경우,
     * 계좌 스팬 때문에 전화 스팬(마지막 자리 "이" 포함)을 버리면 마지막 자리가 노출된다.
     */
    private static boolean coveredByStripped(String text, Span s, List<Integer> tails,
                                             List<NumberNormalizer.Normalized> variants, List<List<Span>> perVariant) {
        for (int tail : tails) {
            if (s.start() > tail || s.end() <= tail) {
                continue;
            }
            for (int k = 0; k < variants.size(); k++) {
                if (variants.get(k).tailStripped() && perVariant.get(k).stream()
                        .anyMatch(b -> b.end() <= tail && b.type().priority() <= s.type().priority()
                                && NumberNormalizer.onlySeparators(text, b.end(), tail))) {
                    return true;
                }
            }
        }
        return false;
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
