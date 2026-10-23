package com.piimask.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이름 탐지 Recall·Precision 측정(2단계 완료 기준). 점수화 끔(강한 단서 규칙만) / 켬 을 나란히 출력한다.
 *  - 손으로 쓴 평가셋 eval/names-handwritten.txt (구역별)
 *  - 시드 전체 + 정답 파일 eval/seed-names.txt (gen_seed.py 가 생성)
 * 맞춤 기준: 정답 스팬과 겹치는 예측이 있으면 Recall 1건, 정답과 겹치는 예측이면 Precision 1건.
 * 실패해도 평가셋 줄 번호만 보고한다.
 */
class NameEvalTest {

    private static final Set<EntityType> NAME = Set.of(EntityType.NAME);
    private final MaskingEngine scored = MaskingEngine.withDefaults();
    private final MaskingEngine ruleOnly = scored.withoutNameScoring();

    record Sample(int line, String section, String text, List<int[]> gold) {
    }

    /** tp(정답 중 잡힌 수), gold, correct(예측 중 맞은 수), predicted */
    static final class Score {
        int tp;
        int gold;
        int correct;
        int predicted;
        final List<Integer> missLines = new ArrayList<>();
        final List<Integer> fpLines = new ArrayList<>();

        double recall() {
            return gold == 0 ? 1 : (double) tp / gold;
        }

        double precision() {
            return predicted == 0 ? 1 : (double) correct / predicted;
        }

        double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0 : 2 * p * r / (p + r);
        }

        @Override
        public String toString() {
            return String.format("R %.3f (%d/%d) P %.3f (%d/%d) F1 %.3f", recall(), tp, gold, precision(), correct, predicted, f1());
        }
    }

    static List<Sample> handwritten() throws IOException {
        return markedFile("/eval/names-handwritten.txt");
    }

    static List<Sample> markedFile(String resource) throws IOException {
        List<Sample> out = new ArrayList<>();
        try (InputStream in = NameEvalTest.class.getResourceAsStream(resource)) {
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n");
            String section = "";
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].strip();
                if (l.startsWith("## ")) {
                    section = l.substring(3).strip();
                    continue;
                }
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                StringBuilder text = new StringBuilder();
                List<int[]> gold = new ArrayList<>();
                int k = 0;
                while (k < l.length()) {
                    int open = l.indexOf("[[", k);
                    if (open < 0) {
                        text.append(l, k, l.length());
                        break;
                    }
                    int close = l.indexOf("]]", open);
                    text.append(l, k, open);
                    int start = text.length();
                    text.append(l, open + 2, close);
                    gold.add(new int[]{start, text.length()});
                    k = close + 2;
                }
                out.add(new Sample(i + 1, section, text.toString(), gold));
            }
        }
        return out;
    }

    /** 시드 행 + 정답 파일(행 순서대로 "|" 구분 이름). 정답은 그 이름이 나온 모든 자리 */
    static List<Sample> seed() throws IOException {
        List<SeedRegressionTest.Row> rows = SeedRegressionTest.seedRows();
        List<String> goldLines;
        try (InputStream in = NameEvalTest.class.getResourceAsStream("/eval/seed-names.txt")) {
            goldLines = new ArrayList<>(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n", -1)));
        }
        goldLines.removeIf(l -> l.startsWith("#"));
        if (!goldLines.isEmpty() && goldLines.getLast().isEmpty()) {
            goldLines.removeLast();
        }
        assertEquals(rows.size(), goldLines.size(), "정답 파일과 시드 행 수가 다름: gen_seed.py 를 다시 실행");
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String t = rows.get(i).text();
            String[] kv = goldLines.get(i).split("\t", 2);
            // 행 본문 해시로 정답 줄이 같은 행인지 확인(순서가 바뀌면 조용히 틀린 수치가 나오지 않게)
            assertEquals(kv[0], sha1Prefix(t), "정답 파일 " + (i + 2) + "번째 줄이 시드 행과 맞지 않음: gen_seed.py 를 다시 실행");
            List<int[]> gold = new ArrayList<>();
            for (String n : kv[1].split("\\|")) {
                if (n.isEmpty()) {
                    continue;
                }
                for (int at = t.indexOf(n); at >= 0; at = t.indexOf(n, at + 1)) {
                    gold.add(new int[]{at, at + n.length()});
                }
            }
            out.add(new Sample(i + 1, rows.get(i).table(), t, gold));
        }
        return out;
    }

    private static String sha1Prefix(String text) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h).substring(0, 10);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void score(MaskingEngine e, Sample s, Score acc) {
        List<Span> pred = e.detect(s.text(), NAME);
        acc.gold += s.gold().size();
        acc.predicted += pred.size();
        boolean missed = false;
        for (int[] g : s.gold()) {
            if (pred.stream().anyMatch(p -> p.start() < g[1] && g[0] < p.end())) {
                acc.tp++;
            } else {
                missed = true;
            }
        }
        boolean fp = false;
        for (Span p : pred) {
            if (s.gold().stream().anyMatch(g -> p.start() < g[1] && g[0] < p.end())) {
                acc.correct++;
            } else {
                fp = true;
            }
        }
        if (missed) {
            acc.missLines.add(s.line());
        }
        if (fp) {
            acc.fpLines.add(s.line());
        }
    }

    static Map<String, Score> bySection(MaskingEngine e, List<Sample> samples) {
        Map<String, Score> m = new LinkedHashMap<>();
        Score all = new Score();
        for (Sample s : samples) {
            score(e, s, m.computeIfAbsent(s.section(), k -> new Score()));
            score(e, s, all);
        }
        m.put("전체", all);
        return m;
    }

    @Test
    void handwritten_eval_set() throws IOException {
        List<Sample> samples = handwritten();
        assertTrue(samples.size() >= 150, "평가셋 문장 수: " + samples.size());
        Map<String, Score> off = bySection(ruleOnly, samples);
        Map<String, Score> on = bySection(scored, samples);
        StringBuilder r = new StringBuilder("이름 평가셋(손으로 씀) " + samples.size() + "문장, 점수화 끔 → 켬\n");
        on.forEach((k, v) -> r.append("  ").append(k).append(": ").append(off.get(k)).append("  →  ").append(v).append('\n'));
        r.append("  켬 누락 줄: ").append(on.get("전체").missLines).append('\n');
        r.append("  켬 오탐 줄: ").append(on.get("전체").fpLines);
        System.out.println(r);

        // 강한 단서는 점수화 전후 모두 전부 잡는다
        assertEquals(1.0, off.get("strong").recall(), "강한 단서 누락 줄: " + off.get("strong").missLines);
        assertEquals(1.0, on.get("strong").recall(), "강한 단서 누락 줄: " + on.get("strong").missLines);
        // 점수화가 넓히는 범위: 약한 단서 (첫 측정 기준선, PLAN 에 기록)
        assertTrue(on.get("weak").recall() >= 0.95, "약한 단서 Recall: " + on.get("weak") + " 누락 줄 " + on.get("weak").missLines);
        assertTrue(on.get("weak").recall() > off.get("weak").recall());
        // 오탐 유도 문장은 하나도 잡지 않는다
        assertEquals(0, on.get("negative").predicted, "오탐 줄: " + on.get("negative").fpLines);
        assertTrue(on.get("전체").precision() >= 0.85, "전체 Precision: " + on.get("전체"));
    }

    private Map<String, Score> report(String title, List<Sample> samples) {
        Map<String, Score> off = bySection(ruleOnly, samples);
        Map<String, Score> on = bySection(scored, samples);
        StringBuilder r = new StringBuilder(title + " " + samples.size() + "문장, 점수화 끔 → 켬\n");
        on.forEach((k, v) -> r.append("  ").append(k).append(": ").append(off.get(k)).append("  →  ").append(v).append('\n'));
        r.append("  켬 누락 줄: ").append(on.get("전체").missLines).append('\n');
        r.append("  켬 오탐 줄: ").append(on.get("전체").fpLines);
        System.out.println(r);
        assertTrue(on.get("전체").tp >= off.get("전체").tp, "점수화로 놓친 이름이 생김");
        return on;
    }

    /**
     * 보류 평가셋 1: 첫 측정(점수화 1차) 뒤 리뷰 지적으로 규칙을 고쳤으므로 더는 완전한 보류셋이 아니다.
     * 기준선은 측정값 기준(PLAN 기록). 나빠지면 실패한다.
     */
    @Test
    void holdout_eval_set() throws IOException {
        Map<String, Score> on = report("이름 보류 평가셋 1", markedFile("/eval/names-holdout.txt"));
        assertTrue(on.get("weak").recall() >= 0.90, "약한 단서 Recall: " + on.get("weak"));
        assertTrue(on.get("전체").precision() >= 0.85, "전체 Precision: " + on.get("전체"));
        assertTrue(on.get("negative").predicted <= 2, "오탐 유도 문장 오탐: " + on.get("negative").fpLines);
    }

    /** 보류 평가셋 2: 리뷰 지적 수정 전에 써 두고 수정 후 한 번 측정. 이 파일을 보고 규칙을 고치지 않는다 */
    @Test
    void holdout2_eval_set() throws IOException {
        // 첫 측정에서 강한 단서 누락 4건·약한 단서 누락 3건은 모두 성씨 사전에 없는 희귀 성씨(봉·피·계·어)였다. 고치지 않고 기록만 한다
        Map<String, Score> on = report("이름 보류 평가셋 2", markedFile("/eval/names-holdout2.txt"));
        HOLDOUT2_BASELINE.check(on);
    }

    /** 보류셋 2 첫 측정값(약한 단서 R 0.80, P 1.00, 오탐 0)보다 조금 낮게 둔 기준선 */
    private static final Baseline HOLDOUT2_BASELINE = new Baseline(0.75, 0.90, 1);

    record Baseline(double weakRecall, double precision, int negativeFp) {
        void check(Map<String, Score> on) {
            assertTrue(on.get("weak").recall() >= weakRecall, "약한 단서 Recall: " + on.get("weak"));
            assertTrue(on.get("전체").precision() >= precision, "전체 Precision: " + on.get("전체"));
            assertTrue(on.get("negative").predicted <= negativeFp, "오탐 유도 문장 오탐: " + on.get("negative").fpLines);
        }
    }

    /** 이름 없는 상담 발화(리뷰 중 작성, 오탐을 고치며 본 개발용): 점수화가 만든 오탐 수를 본다 */
    @Test
    void probe_negative_set() throws IOException {
        List<Sample> samples = markedFile("/eval/names-probe-negative.txt");
        int off = 0;
        int on = 0;
        List<Integer> lines = new ArrayList<>();
        for (Sample s : samples) {
            int a = ruleOnly.detect(s.text(), NAME).size();
            int b = scored.detect(s.text(), NAME).size();
            off += a;
            on += b;
            if (b > a) {
                lines.add(s.line());
            }
        }
        System.out.println("이름 없는 발화(개발용) " + samples.size() + "문장: 이름 탐지 점수화 끔 " + off + "건 → 켬 " + on
                + "건, 점수화가 늘린 줄 " + lines);
        assertTrue(samples.size() >= 300);
        assertTrue(on - off <= 3, "점수화 오탐: " + lines);
    }

    @Test
    void seed_eval() throws IOException {
        List<Sample> samples = seed();
        Map<String, Score> off = bySection(ruleOnly, samples);
        Map<String, Score> on = bySection(scored, samples);
        StringBuilder r = new StringBuilder("이름 평가(시드 " + samples.size() + "행), 점수화 끔 → 켬\n");
        on.forEach((k, v) -> r.append("  ").append(k).append(": ").append(off.get(k)).append("  →  ").append(v).append('\n'));
        r.append("  켬 오탐 행(처음 20): ").append(on.get("전체").fpLines.stream().limit(20).toList());
        System.out.println(r);

        Score a = off.get("전체");
        Score b = on.get("전체");
        // 점수화는 Recall 을 낮추지 않고, Precision 을 기준선 밑으로 떨어뜨리지 않는다
        assertTrue(b.tp >= a.tp, "점수화로 놓친 이름이 생김");
        assertTrue(b.precision() >= 0.95, "시드 Precision: " + b);
    }
}
