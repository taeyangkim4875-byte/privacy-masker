package com.piimask.app.detect;

import com.piimask.app.job.JobDef;
import com.piimask.app.job.JobDtos.SpanView;
import com.piimask.app.job.PreviewService;
import com.piimask.app.support.ApiException;
import com.piimask.core.EntityType;
import com.piimask.core.MaskingEngine;
import com.piimask.core.detect.Detector;
import com.piimask.core.detect.RegexDetector;
import com.piimask.core.mask.MaskingPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class DetectController {

    public record DetectRequest(String text, List<String> entities, Map<String, String> policies) {
    }

    public record DetectResponse(String masked, List<SpanView> spans, long elapsedMicros) {
    }

    private final MaskingEngine engine;

    public DetectController(MaskingEngine engine) {
        this.engine = engine;
    }

    /** 탐지 테스트: 텍스트를 붙여넣어 결과 확인. 저장·로그하지 않는다 */
    @PostMapping("/detect")
    public DetectResponse detect(@RequestBody DetectRequest r) {
        if (r == null || r.text() == null) {
            throw ApiException.badRequest("text 가 필요합니다");
        }
        if (r.text().length() > 20_000) {
            throw ApiException.badRequest("한 번에 20,000자까지 테스트할 수 있습니다");
        }
        Set<EntityType> enabled = EnumSet.noneOf(EntityType.class);
        if (r.entities() == null) {
            enabled.addAll(EnumSet.allOf(EntityType.class));
        } else {
            for (String e : r.entities()) {
                try {
                    enabled.add(EntityType.valueOf(e));
                } catch (IllegalArgumentException | NullPointerException ex) {
                    throw ApiException.badRequest("알 수 없는 탐지 유형입니다: " + e);
                }
            }
        }
        Map<EntityType, MaskingPolicy> policies;
        try {
            policies = JobDef.parsePolicies(r.policies());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw ApiException.badRequest("마스킹 정책 값이 올바르지 않습니다");
        }
        long t0 = System.nanoTime();
        MaskingEngine.Result res = engine.mask(r.text(), enabled, policies, "detect-test");
        long micros = (System.nanoTime() - t0) / 1000;
        return new DetectResponse(res.masked(), PreviewService.toViews(res.spans()), micros);
    }

    /** 화면 구성용 메타 정보: 탐지 유형, 정책, 탐지기 목록 */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (EntityType t : EntityType.values()) {
            types.add(Map.of("code", t.name(), "label", t.label(), "priority", t.priority()));
        }
        List<Map<String, Object>> policies = new ArrayList<>();
        for (MaskingPolicy p : MaskingPolicy.values()) {
            policies.add(Map.of("code", p.name(), "label", p.label()));
        }
        List<Map<String, Object>> detectors = new ArrayList<>();
        for (Detector d : engine.detectors()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("type", d.type().name());
            m.put("kind", d instanceof RegexDetector ? "정규식" : "규칙");
            m.put("pattern", d instanceof RegexDetector rd ? rd.regex() : "성씨 + 문맥 단서(성함/고객님/입니다 등)");
            detectors.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityTypes", types);
        out.put("policies", policies);
        out.put("detectors", detectors);
        out.put("defaultPolicies", JobDef.policiesView(JobDef.defaultPolicies()));
        return out;
    }
}
