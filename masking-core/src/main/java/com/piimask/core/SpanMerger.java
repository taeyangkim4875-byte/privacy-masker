package com.piimask.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 탐지 스팬 병합.
 * 겹치는 스팬은 합집합 범위로 합치고 우선순위가 높은(숫자가 작은) 유형을 남긴다.
 * 일부만 겹쳐도 합집합을 쓰는 이유: 겹치지 않은 나머지가 노출되지 않게 하기 위함(Recall 우선).
 */
public final class SpanMerger {

    private SpanMerger() {
    }

    public static List<Span> merge(List<Span> spans) {
        if (spans.isEmpty()) {
            return List.of();
        }
        List<Span> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));

        List<Span> out = new ArrayList<>();
        Span cur = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Span next = sorted.get(i);
            if (next.start() < cur.end()) {
                EntityType type = next.type().priority() < cur.type().priority() ? next.type() : cur.type();
                String detector = type == cur.type() ? cur.detector() : next.detector();
                cur = new Span(cur.start(), Math.max(cur.end(), next.end()), type, detector);
            } else {
                out.add(cur);
                cur = next;
            }
        }
        out.add(cur);
        return out;
    }
}
