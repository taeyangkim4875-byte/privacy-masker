package com.piimask.core;

import com.piimask.core.normalize.KoreanDigits;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 가짜 시드(docker/mariadb/init/02-seed.sql) 전체로 ITN 끔/켬을 비교한다.
 * 탐지 규칙을 바꿀 때 다른 유형의 탐지 건수가 의도치 않게 바뀌지 않았는지 보는 장치.
 * 실패해도 원문은 출력하지 않고 테이블·행 번호·유형만 보고한다.
 */
class SeedRegressionTest {

    private static final Path SEED = Path.of("../docker/mariadb/init/02-seed.sql");
    private static final Pattern LITERAL = Pattern.compile("'((?:[^'\\\\]|''|\\\\.)*)'");
    /** 테이블별 탐지 대상 텍스트가 몇 번째 문자열 리터럴인지 */
    private static final Map<String, Integer> TEXT_COLUMN = Map.of(
            "stt_call_history", 2, "tts_request_log", 0, "tts_ssml_log", 2);
    private static final Set<EntityType> NUMERIC = Set.of(EntityType.PHONE, EntityType.CARD, EntityType.RRN, EntityType.ACCOUNT);

    record Row(String table, int no, String key, String text) {
    }

    static List<Row> seedRows() throws IOException {
        List<Row> rows = new ArrayList<>();
        String table = null;
        int no = 0;
        for (String line : Files.readAllLines(SEED, StandardCharsets.UTF_8)) {
            if (line.startsWith("INSERT INTO ")) {
                table = line.substring(12, line.indexOf(' ', 12));
                continue;
            }
            if (table == null || !line.startsWith("(")) {
                continue;
            }
            Matcher m = LITERAL.matcher(line);
            List<String> lits = new ArrayList<>();
            while (m.find()) {
                lits.add(m.group(1).replace("''", "'").replace("\\\\", "\\"));
            }
            rows.add(new Row(table, ++no, lits.get(0), lits.get(TEXT_COLUMN.get(table))));
        }
        return rows;
    }

    @Test
    void itn_only_adds_numeric_spans_over_korean_digits() throws IOException {
        MaskingEngine on = MaskingEngine.withDefaults();
        MaskingEngine off = on.withoutItn();
        List<Row> rows = seedRows();
        assertTrue(rows.size() > 4000, "시드 행 수: " + rows.size());

        Map<EntityType, int[]> counts = new EnumMap<>(EntityType.class); // 유형별 단독 탐지 [끔, 켬]
        Map<EntityType, int[]> merged = new EnumMap<>(EntityType.class); // 전 유형 병합 후(실제 마스킹 기준) [끔, 켬]
        List<String> problems = new ArrayList<>();
        int koreanPhoneRows = 0;
        int koreanPhoneDetected = 0;
        for (Row r : rows) {
            String t = r.text();
            off.detect(t).forEach(s -> merged.computeIfAbsent(s.type(), k -> new int[2])[0]++);
            on.detect(t).forEach(s -> merged.computeIfAbsent(s.type(), k -> new int[2])[1]++);
            for (EntityType type : EntityType.values()) {
                List<Span> a = off.detect(t, Set.of(type));
                List<Span> b = on.detect(t, Set.of(type));
                int[] c = counts.computeIfAbsent(type, k -> new int[2]);
                c[0] += a.size();
                c[1] += b.size();
                if (!NUMERIC.contains(type)) {
                    if (!a.equals(b)) {
                        problems.add(r.table() + "#" + r.no() + " " + type + ": 숫자형이 아닌데 결과가 바뀜");
                    }
                    continue;
                }
                // 끔에서 잡힌 스팬은 켬에서도 같은 자리가 덮여야 하고, 새 스팬은 한글 숫자를 덮어야 한다
                for (Span s : a) {
                    if (b.stream().noneMatch(x -> x.start() <= s.start() && x.end() >= s.end())) {
                        problems.add(r.table() + "#" + r.no() + " " + type + ": 기존 스팬이 사라짐");
                    }
                }
                for (Span s : b) {
                    if (!a.contains(s) && !KoreanDigits.containsKoreanDigit(t, s.start(), s.end())) {
                        problems.add(r.table() + "#" + r.no() + " " + type + ": 한글 숫자와 무관한 새 스팬");
                    }
                }
            }
            // (a) 유형 하나만 켰을 때 가려지는 한글 숫자는 전 유형을 켰을 때도 가려져야 한다(병합으로 자리가 빠지면 노출)
            List<Span> all = on.detect(t);
            for (EntityType type : NUMERIC) {
                for (Span s : on.detect(t, Set.of(type))) {
                    for (int i = s.start(); i < s.end(); i++) {
                        int idx = i;
                        if (KoreanDigits.isKoreanDigit(t.charAt(i)) && all.stream().noneMatch(x -> x.start() <= idx && idx < x.end())) {
                            problems.add(r.table() + "#" + r.no() + " " + type + ": 전 유형에서 한글 숫자가 노출됨");
                            break;
                        }
                    }
                }
            }
            // (b) ITN 이 기존 스팬을 늘렸다면, 늘어난 구간은 한글 숫자 3자 이상이어야 한다("[전화번호]늘" 같은 과마스킹 방지)
            List<Span> before = off.detect(t);
            for (Span s : all) {
                if (before.stream().noneMatch(x -> x.start() < s.end() && s.start() < x.end())) {
                    continue;
                }
                int korean = 0;
                boolean extra = false;
                for (int i = s.start(); i <= s.end(); i++) {
                    int idx = i;
                    boolean covered = i < s.end() && before.stream().anyMatch(x -> x.start() <= idx && idx < x.end());
                    if (i < s.end() && !covered) {
                        extra = true;
                        korean += KoreanDigits.isKoreanDigit(t.charAt(i)) ? 1 : 0;
                    } else if (extra) {
                        if (korean < 3) {
                            problems.add(r.table() + "#" + r.no() + ": 기존 스팬이 한글 숫자 아닌 쪽으로 늘어남");
                        }
                        extra = false;
                        korean = 0;
                    }
                }
            }
            if (t.startsWith("공일공") || t.startsWith("영일영")) {
                koreanPhoneRows++;
                if (!on.detect(t, Set.of(EntityType.PHONE)).isEmpty()) {
                    koreanPhoneDetected++;
                }
            }
        }
        StringBuilder report = new StringBuilder("시드 " + rows.size() + "행 탐지 건수(ITN 끔 → 켬) 유형별 단독:");
        counts.forEach((k, v) -> report.append(' ').append(k).append(' ').append(v[0]).append("→").append(v[1]));
        report.append(" / 병합 후:");
        merged.forEach((k, v) -> report.append(' ').append(k).append(' ').append(v[0]).append("→").append(v[1]));
        report.append(" / 한글로 읽은 휴대폰 발화 ").append(koreanPhoneDetected).append('/').append(koreanPhoneRows);
        System.out.println(report);

        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertTrue(koreanPhoneRows >= 38, "한글 휴대폰 발화 수: " + koreanPhoneRows);
        assertEquals(koreanPhoneRows, koreanPhoneDetected, "한글로 읽은 휴대폰 번호를 모두 잡아야 함");
    }

    /** gen_seed.py 의 ITN 검증 통화(C20261021-*): 한글 번호 발화는 모두 잡고, 오탐 유도 문장은 하나도 잡지 않는다 */
    @Test
    void itn_seed_calls_positive_and_negative() throws IOException {
        MaskingEngine on = MaskingEngine.withDefaults();
        MaskingEngine off = on.withoutItn();
        List<Row> itn = seedRows().stream().filter(r -> r.key().startsWith("C20261021-")).toList();
        assertTrue(itn.size() > 300, "ITN 통화 행 수: " + itn.size());
        List<String> missed = new ArrayList<>();
        List<String> falsePositive = new ArrayList<>();
        int positives = 0;
        for (Row r : itn) {
            String t = r.text();
            boolean hasNumber = !com.piimask.core.normalize.NumberNormalizer.normalize(t).isEmpty();
            List<Span> spans = on.detect(t, NUMERIC);
            if (hasNumber) {
                positives++;
                // 번호 덩어리의 한글 숫자를 하나도 빠짐없이 덮어야 한다(한 자리라도 남으면 누락)
                for (int i = 0; i < t.length(); i++) {
                    int idx = i;
                    if (KoreanDigits.isKoreanDigit(t.charAt(i)) && inNumberChunk(t, i)
                            && spans.stream().noneMatch(s -> s.start() <= idx && idx < s.end())) {
                        missed.add("stt#" + r.no());
                        break;
                    }
                }
            } else if (!spans.equals(off.detect(t, NUMERIC))) {
                // 바꿀 한글 번호가 없는 문장은 ITN 이 결과를 바꾸면 안 된다(아라비아 숫자 번호는 원래대로 잡힘)
                falsePositive.add("stt#" + r.no());
            }
        }
        System.out.println("ITN 시드 통화 " + itn.size() + "행: 번호 발화 " + positives + "건, 누락 " + missed.size()
                + "건, 오탐 " + falsePositive.size() + "건");
        assertTrue(positives >= 60, "번호 발화 수: " + positives);
        assertTrue(missed.isEmpty(), "누락: " + missed);
        assertTrue(falsePositive.isEmpty(), "오탐: " + falsePositive);
    }

    /** gen_seed.py 의 가짜 이름 문법(SURNAMES + GIVEN 1~2음절). 이 형태가 예외 사전으로 지워지면 진짜 이름을 지운 것 */
    private static final String SEED_SURNAMES = "김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허남심노하곽성차주우구";
    private static final String SEED_GIVEN = "민서준우하윤지호예은도현수아태양시연하늘유진건희주원채린서영재민가온다인소율";
    private static final Pattern SEED_NAME = Pattern.compile(
            "(?:남궁|황보|제갈|[" + SEED_SURNAMES + "])[" + SEED_GIVEN + "]{1,2}");

    /** 이름 점수화 켬/끔: 이름 아닌 유형은 그대로, 기존 이름 스팬은 유지, 새 스팬은 시드 이름 형태여야 한다 */
    @Test
    void name_scoring_only_adds_seed_shaped_names() throws IOException {
        MaskingEngine on = MaskingEngine.withDefaults();
        MaskingEngine off = on.withoutNameScoring();
        Set<EntityType> others = java.util.EnumSet.complementOf(java.util.EnumSet.of(EntityType.NAME));
        List<String> problems = new ArrayList<>();
        int added = 0;
        for (Row r : seedRows()) {
            String t = r.text();
            String where = r.table() + "#" + r.no();
            if (!on.detect(t, others).equals(off.detect(t, others))) {
                problems.add(where + ": 이름 아닌 유형의 결과가 바뀜");
            }
            List<Span> a = off.detect(t, Set.of(EntityType.NAME));
            List<Span> b = on.detect(t, Set.of(EntityType.NAME));
            if (!b.containsAll(a)) {
                problems.add(where + ": 점수화로 기존 이름 스팬이 바뀜");
            }
            for (Span s : b) {
                if (!a.contains(s)) {
                    added++;
                    if (!SEED_NAME.matcher(t.substring(s.start(), s.end())).matches()) {
                        problems.add(where + ": 점수화로 이름 형태가 아닌 스팬이 생김");
                    }
                }
            }
        }
        System.out.println("이름 점수화 끔 → 켬: 시드에서 새 이름 스팬 " + added + "건");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** 사전 도입 전 STOPWORDS 에 있던 말 중 시드 이름 형태인 것(그때부터 이름으로 보지 않았음) */
    private static final Set<String> LEGACY_NAME_SHAPED = Set.of("한도", "오늘", "주소", "정도", "최소", "임시");

    /** 새로 넣는 예외어가 이름 형태면, 그 이름을 가진 사람이 지워진다("이율", "고지서") */
    @Test
    void built_in_exceptions_are_not_name_shaped() {
        List<String> shaped = com.piimask.core.dict.NameDictionary.builtIn().exceptions().stream()
                .filter(t -> SEED_NAME.matcher(t).matches() && !LEGACY_NAME_SHAPED.contains(t)).sorted().toList();
        assertTrue(shaped.isEmpty(), "이름일 수 있는 예외어 " + shaped.size() + "개");
    }

    /** 기본 예외 사전 켬/끔: 이름 아닌 유형은 그대로, 이름은 줄기만 하고, 지워진 것 중 시드 이름 형태는 없어야 한다 */
    @Test
    void exception_dictionary_only_removes_non_names() throws IOException {
        MaskingEngine on = MaskingEngine.withDefaults();
        MaskingEngine off = on.withDictionary(com.piimask.core.dict.NameDictionary.builtIn().withoutExceptions());
        Set<EntityType> others = java.util.EnumSet.complementOf(java.util.EnumSet.of(EntityType.NAME));
        List<String> problems = new ArrayList<>();
        int[] name = new int[2]; // [끔, 켬]
        int removed = 0;
        int dictRows = 0;
        int dictFalsePositiveOff = 0;
        int dictNames = 0;
        int legacyNames = 0;
        Pattern cued = Pattern.compile("(?<![가-힣])(" + SEED_NAME.pattern() + ")(?=입니다|이요| 고객님)");
        for (Row r : seedRows()) {
            String t = r.text();
            String where = r.table() + "#" + r.no();
            if (!on.detect(t, others).equals(off.detect(t, others))) {
                problems.add(where + ": 이름 아닌 유형의 결과가 바뀜");
            }
            List<Span> a = off.detect(t, Set.of(EntityType.NAME));
            List<Span> b = on.detect(t, Set.of(EntityType.NAME));
            name[0] += a.size();
            name[1] += b.size();
            if (!r.key().startsWith("C20261022-")) {
                legacyNames += (int) on.detect(t).stream().filter(s -> s.type() == EntityType.NAME).count();
            }
            for (Span s : b) {
                if (!a.contains(s)) {
                    problems.add(where + ": 예외 사전을 켰더니 새 이름 스팬이 생김");
                }
            }
            for (Span s : a) {
                if (!b.contains(s)) {
                    removed++;
                    if (SEED_NAME.matcher(t.substring(s.start(), s.end())).matches()) {
                        problems.add(where + ": 시드 이름 형태가 예외 사전으로 지워짐");
                    }
                }
            }
            if (r.key().startsWith("C20261022-")) {
                dictRows++;
                for (Span s : a) {
                    if (!SEED_NAME.matcher(t.substring(s.start(), s.end())).matches()) {
                        dictFalsePositiveOff++;
                    }
                }
                for (Span s : b) {
                    if (!SEED_NAME.matcher(t.substring(s.start(), s.end())).matches()) {
                        problems.add(where + ": 예외 사전 검증 통화에 이름 오탐이 남음");
                    }
                }
                Matcher m = cued.matcher(t);
                while (m.find()) {
                    dictNames++;
                    int st = m.start(1);
                    int en = m.end(1);
                    if (b.stream().noneMatch(s -> s.start() <= st && en <= s.end())) {
                        problems.add(where + ": 예외 사전 검증 통화의 이름을 놓침");
                    }
                }
            }
        }
        System.out.println("예외 사전 끔 → 켬: 이름 " + name[0] + "→" + name[1] + " (지워진 스팬 " + removed + ")"
                + " / 검증 통화 " + dictRows + "행: 사전 없을 때 오탐 " + dictFalsePositiveOff + "건, 이름 " + dictNames + "건");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        // 사전 도입 전 시드 행의 이름 건수(1262)에서 "오일입니다" 오탐 2건만 빠진 값. 사전·규칙을 바꾸면 이 값을 확인하고 고친다
        assertEquals(1260, legacyNames, "사전 도입 전 시드 행의 이름 건수");
        assertTrue(dictRows >= 200, "검증 통화 행 수: " + dictRows);
        assertTrue(dictFalsePositiveOff >= 60, "검증 통화가 오탐을 유도해야 함: " + dictFalsePositiveOff);
        assertTrue(dictNames >= 60, "검증 통화의 이름 수: " + dictNames);
    }

    /**
     * 9자리 이상 숫자 덩어리(숫자·공백·"에")에 든 글자인지.
     * 덩어리의 마지막 숫자가 "이" 면 조사("…이요", "…이에요")일 수 있으므로 검사에서 뺀다.
     */
    private static boolean inNumberChunk(String t, int i) {
        int s = i;
        int e = i + 1;
        while (s > 0 && (KoreanDigits.isDigitLike(t.charAt(s - 1)) || " 에".indexOf(t.charAt(s - 1)) >= 0)) {
            s--;
        }
        while (e < t.length() && (KoreanDigits.isDigitLike(t.charAt(e)) || " 에".indexOf(t.charAt(e)) >= 0)) {
            e++;
        }
        int lastDigit = -1;
        for (int k = s; k < e; k++) {
            if (KoreanDigits.isDigitLike(t.charAt(k))) {
                lastDigit = k;
            }
        }
        if (i == lastDigit && t.charAt(i) == '이') {
            return false;
        }
        return KoreanDigits.digits(t.substring(s, e)).length() >= 9;
    }
}
