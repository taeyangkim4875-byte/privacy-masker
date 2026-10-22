package com.piimask.app.job;

import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.core.EntityType;
import com.piimask.core.mask.MaskingPolicy;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 저장된(또는 저장 전 미리보기용) 마스킹 작업 정의 */
public record JobDef(long id, String name, long connectionId, String sourceTable, String pkColumn,
                     String targetTable, int chunkSize, String cron, boolean enabled,
                     List<ColumnSpec> columns, Map<EntityType, MaskingPolicy> policies, String hashSalt) {

    public static final String MASK = "MASK";
    public static final String COPY = "COPY";

    public List<ColumnSpec> maskColumns() {
        return columns.stream().filter(c -> MASK.equals(c.mode())).toList();
    }

    public List<ColumnSpec> copyColumns() {
        return columns.stream().filter(c -> COPY.equals(c.mode())).toList();
    }

    public static Set<EntityType> entitiesOf(ColumnSpec c) {
        if (c.entities() == null || c.entities().isEmpty()) {
            return EnumSet.allOf(EntityType.class);
        }
        EnumSet<EntityType> set = EnumSet.noneOf(EntityType.class);
        for (String e : c.entities()) {
            set.add(EntityType.valueOf(e));
        }
        return set;
    }

    public static Map<EntityType, MaskingPolicy> parsePolicies(Map<String, String> raw) {
        Map<EntityType, MaskingPolicy> out = defaultPolicies();
        if (raw != null) {
            raw.forEach((k, v) -> out.put(EntityType.valueOf(k), MaskingPolicy.valueOf(v)));
        }
        return out;
    }

    public static Map<EntityType, MaskingPolicy> parsePolicies(String csv) {
        Map<EntityType, MaskingPolicy> out = defaultPolicies();
        if (csv != null && !csv.isBlank()) {
            for (String pair : csv.split(",")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    out.put(EntityType.valueOf(kv[0].trim()), MaskingPolicy.valueOf(kv[1].trim()));
                }
            }
        }
        return out;
    }

    public static Map<EntityType, MaskingPolicy> defaultPolicies() {
        Map<EntityType, MaskingPolicy> m = new EnumMap<>(EntityType.class);
        for (EntityType t : EntityType.values()) {
            m.put(t, MaskingPolicy.PARTIAL);
        }
        return m;
    }

    public static String policiesCsv(Map<EntityType, MaskingPolicy> p) {
        return p.entrySet().stream().map(e -> e.getKey().name() + "=" + e.getValue().name())
                .collect(Collectors.joining(","));
    }

    public static Map<String, String> policiesView(Map<EntityType, MaskingPolicy> p) {
        Map<String, String> m = new LinkedHashMap<>();
        p.forEach((k, v) -> m.put(k.name(), v.name()));
        return m;
    }
}
