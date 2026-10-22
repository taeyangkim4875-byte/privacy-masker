package com.piimask.core.normalize;

import java.util.ArrayList;
import java.util.List;

/**
 * ITN(역정규화) 1단계: 한 자리씩 한글로 읽은 숫자를 아라비아 숫자열로 바꾼다.
 * "공일공에 사팔이일에 칠칠삼육" → "010-4821-7736"
 *
 * 규칙
 * - 숫자 음절(한글·아라비아)이 이어진 묶음(그룹)과, 그룹 사이 구분자(공백·-·,·.·조사 "에")로 이어진 덩어리를 찾는다.
 * - 덩어리 전체 숫자가 {@link #MIN_DIGITS} 자리 이상이고 한글 숫자가 하나라도 있을 때만 바꾼다.
 *   "일번", "이번 달", "사이", "삼십만원"(자릿수 단어) 등은 바뀌지 않는다.
 * - 덩어리는 그룹을 "-" 로 이은 숫자열이 된다. 기존 정규식(전화·카드·주민·하이픈 계좌)이 그대로 맞는다.
 * - 덩어리 끝의 "이" 바로 뒤에 한글이 붙으면("칠칠삼육이요") 숫자 2 인지 서술격 조사인지 알 수 없으므로
 *   두 해석을 모두 후보로 낸다. 탐지 결과는 합집합(Recall 우선).
 * 원문은 바꾸지 않는다. 정규화 텍스트는 탐지에만 쓰고, 스팬은 {@link OffsetMap} 으로 원문 위치에 되돌린다.
 */
public final class NumberNormalizer {

    /** 가장 짧은 대상: 02-123-4567 (9자리) */
    public static final int MIN_DIGITS = 9;
    private static final int MAX_SEPARATOR = 5;

    /**
     * @param ambiguousTails 두 가지로 해석한 덩어리 끝 "이" 의 원문 위치
     * @param tailStripped   true 면 그 "이" 를 조사로 보고 뺀 해석
     */
    public record Normalized(String text, OffsetMap map, List<Integer> ambiguousTails, boolean tailStripped) {
    }

    private record Group(int start, int end) {
    }

    private record Chain(List<Group> groups, boolean ambiguousTail) {
    }

    private NumberNormalizer() {
    }

    /** 정규화 후보(0~2개). 바꿀 덩어리가 없으면 빈 목록 */
    public static List<Normalized> normalize(String text) {
        if (text == null || text.isEmpty() || !KoreanDigits.containsKoreanDigit(text, 0, text.length())) {
            return List.of();
        }
        List<Chain> chains = findChains(text);
        if (chains.isEmpty()) {
            return List.of();
        }
        List<Normalized> out = new ArrayList<>(2);
        Normalized plain = build(text, chains, false);
        if (plain != null) {
            out.add(plain);
        }
        if (chains.stream().anyMatch(Chain::ambiguousTail)) {
            Normalized stripped = build(text, chains, true);
            if (stripped != null && (plain == null || !stripped.text().equals(plain.text()))) {
                out.add(stripped);
            }
        }
        return out;
    }

    private static List<Chain> findChains(String text) {
        List<Chain> chains = new ArrayList<>();
        List<int[]> telephone = telephoneBodies(text);
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (!KoreanDigits.isDigitLike(text.charAt(i))) {
                i++;
                continue;
            }
            List<Group> groups = new ArrayList<>();
            int ge = groupEnd(text, i);
            groups.add(new Group(i, ge));
            i = ge;
            while (true) {
                // 구분자가 없어도 문자 체계가 바뀌면(“공일공4821”) 다음 그룹으로 잇는다
                int next = separatorEnd(text, i);
                if (next < n && KoreanDigits.isDigitLike(text.charAt(next))) {
                    int e = groupEnd(text, next);
                    groups.add(new Group(next, e));
                    i = e;
                } else {
                    break;
                }
            }
            trimStrayKorean(text, groups);
            if (!groups.isEmpty() && accepted(text, groups, telephone)) {
                // 끝 "이" 가 조사·지시어일 수 있는 경우: 한글이 바로 붙음("칠칠삼육이요") 또는 "이" 한 글자 그룹("… 이요", "… 이 번호로")
                Group last = groups.get(groups.size() - 1);
                boolean endsWithI = text.charAt(last.end() - 1) == '이';
                boolean gluedHangul = last.end() < n && KoreanDigits.isHangulSyllable(text.charAt(last.end()))
                        && !KoreanDigits.isKoreanDigit(text.charAt(last.end()));
                boolean ambiguous = endsWithI && (gluedHangul || last.end() - last.start() == 1);
                chains.add(new Chain(groups, ambiguous));
            }
        }
        return chains;
    }

    /** 같은 문자 체계(아라비아 / 한글) 숫자가 이어진 끝. "5678이요" 의 "이" 는 앞 숫자와 잇지 않는다(서술격 조사) */
    private static int groupEnd(String text, int start) {
        boolean korean = KoreanDigits.isKoreanDigit(text.charAt(start));
        int i = start;
        while (i < text.length() && KoreanDigits.isDigitLike(text.charAt(i))
                && KoreanDigits.isKoreanDigit(text.charAt(i)) == korean) {
            i++;
        }
        return i;
    }

    /**
     * 아라비아 숫자 그룹이 있는 덩어리에서, 양 끝의 1~2음절 한글 숫자 그룹은 숫자가 아니라 이어진 단어로 본다.
     * "010-4821-7736 오늘", "010-4821-7736 이요", "사이 010-…" 의 "오·이·사이" 를 번호에 붙이지 않는다.
     */
    private static void trimStrayKorean(String text, List<Group> groups) {
        boolean hasArabic = groups.stream().anyMatch(g -> !KoreanDigits.isKoreanDigit(text.charAt(g.start())));
        if (!hasArabic) {
            return;
        }
        while (!groups.isEmpty() && isShortKorean(text, groups.get(groups.size() - 1))) {
            groups.remove(groups.size() - 1);
        }
        while (!groups.isEmpty() && isShortKorean(text, groups.get(0))) {
            groups.remove(0);
        }
    }

    private static boolean isShortKorean(String text, Group g) {
        return KoreanDigits.isKoreanDigit(text.charAt(g.start())) && g.end() - g.start() <= 2;
    }

    /** SSML say-as telephone 본문 구간. 이 안의 한글 숫자는 자릿수와 관계없이 바꾼다 */
    private static List<int[]> telephoneBodies(String text) {
        if (text.indexOf("<say-as") < 0) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        java.util.regex.Matcher m = TELEPHONE_BODY.matcher(text);
        while (m.find()) {
            out.add(new int[]{m.start(1), m.end(1)});
        }
        return out;
    }

    private static final java.util.regex.Pattern TELEPHONE_BODY = java.util.regex.Pattern.compile(
            "<say-as\\b[^>]*interpret-as\\s*=\\s*[\"']telephone[\"'][^>]*>([^<]*)</\\s*say-as\\s*>",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern BREAK_TAG = java.util.regex.Pattern.compile("<break\\b[^>]{0,80}/>");

    /** 그룹 사이 구분자: 공백·문장부호, 선택적 조사 "에", SSML &lt;break/&gt;. 없으면 start 그대로 */
    private static int separatorEnd(String text, int start) {
        int i = start;
        int plain = 0;
        boolean particle = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (" \t-,.·".indexOf(c) >= 0) {
                i++;
                plain++;
            } else if (c == '에' && !particle) {
                i++;
                plain++;
                particle = true;
            } else if (c == '<') {
                java.util.regex.Matcher m = BREAK_TAG.matcher(text).region(i, text.length());
                if (!m.lookingAt()) {
                    break;
                }
                i = m.end();
            } else {
                break;
            }
        }
        return plain <= MAX_SEPARATOR ? i : start;
    }

    /** [from, to) 가 덩어리 구분자(공백·문장부호·"에")로만 되어 있는지 */
    public static boolean onlySeparators(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (" \t-,.·에".indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int skipPunct(String text, int i) {
        while (i < text.length() && " \t-,.·".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }

    private static boolean accepted(String text, List<Group> groups, List<int[]> telephone) {
        int digits = 0;
        boolean korean = false;
        for (Group g : groups) {
            digits += g.end() - g.start();
            korean |= KoreanDigits.containsKoreanDigit(text, g.start(), g.end());
        }
        int s = groups.get(0).start();
        int e = groups.get(groups.size() - 1).end();
        boolean inTelephone = telephone.stream().anyMatch(r -> r[0] <= s && e <= r[1]);
        return korean && (digits >= MIN_DIGITS || inTelephone);
    }

    private static Normalized build(String text, List<Chain> chains, boolean stripTail) {
        StringBuilder sb = new StringBuilder(text.length());
        OffsetMap map = new OffsetMap(text.length());
        List<int[]> telephone = telephoneBodies(text);
        int pos = 0;
        boolean changed = false;
        for (Chain c : chains) {
            List<Group> groups = new ArrayList<>(c.groups());
            if (stripTail && c.ambiguousTail()) {
                Group last = groups.remove(groups.size() - 1);
                if (last.end() - 1 > last.start()) {
                    groups.add(new Group(last.start(), last.end() - 1));
                }
                if (groups.isEmpty() || !accepted(text, groups, telephone)) {
                    continue;
                }
            }
            int chainStart = groups.get(0).start();
            copy(text, pos, chainStart, sb, map);
            for (int k = 0; k < groups.size(); k++) {
                Group g = groups.get(k);
                for (int i = g.start(); i < g.end(); i++) {
                    sb.append((char) ('0' + KoreanDigits.digitValue(text.charAt(i))));
                    map.add(i, i + 1);
                }
                // 원문에 구분자가 있던 자리만 "-" (문자 체계만 바뀐 자리는 그대로 붙인다)
                if (k < groups.size() - 1 && g.end() < groups.get(k + 1).start()) {
                    sb.append('-');
                    map.add(g.end(), groups.get(k + 1).start());
                }
            }
            pos = groups.get(groups.size() - 1).end();
            changed = true;
        }
        if (!changed) {
            return null;
        }
        copy(text, pos, text.length(), sb, map);
        List<Integer> tails = chains.stream().filter(Chain::ambiguousTail)
                .map(ch -> ch.groups().get(ch.groups().size() - 1).end() - 1).toList();
        return new Normalized(sb.toString(), map, tails, stripTail);
    }

    private static void copy(String text, int from, int to, StringBuilder sb, OffsetMap map) {
        for (int i = from; i < to; i++) {
            sb.append(text.charAt(i));
            map.add(i, i + 1);
        }
    }
}
