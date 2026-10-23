package com.piimask.core.dict;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 단어 목록을 첫 글자로 묶어 문장을 한 번 훑으며 나온 자리를 찾는다. 로마자는 대소문자 무시. 불변·스레드 안전.
 */
public final class TermIndex {

    private final Map<Character, List<String>> byFirst = new HashMap<>();

    public TermIndex(Collection<String> terms) {
        for (String t : terms) {
            if (!t.isEmpty()) {
                byFirst.computeIfAbsent(Character.toLowerCase(t.charAt(0)), k -> new ArrayList<>()).add(t);
            }
        }
        // 같은 자리에서는 긴 단어부터 본다
        byFirst.values().forEach(l -> l.sort(Comparator.comparingInt(String::length).reversed()));
    }

    public boolean isEmpty() {
        return byFirst.isEmpty();
    }

    /**
     * 나온 자리 [start, end) 목록. 같은 시작점에서는 가장 긴 단어 하나만.
     * wordBoundary 이면 단어 앞이 같은 문자 체계(한글 음절, 로마자는 영숫자)가 아닐 때만, 로마자 단어는 뒤도 영숫자가 아닐 때만.
     * 한글 뒤는 조사가 붙으므로 보지 않는다.
     */
    public List<int[]> find(String text, boolean wordBoundary) {
        List<int[]> out = new ArrayList<>();
        if (text == null || byFirst.isEmpty()) {
            return out;
        }
        for (int i = 0; i < text.length(); i++) {
            List<String> cands = byFirst.get(Character.toLowerCase(text.charAt(i)));
            if (cands == null) {
                continue;
            }
            for (String t : cands) {
                if (!text.regionMatches(true, i, t, 0, t.length())) {
                    continue;
                }
                int end = i + t.length();
                if (wordBoundary && !boundaryOk(text, i, end, t)) {
                    continue;
                }
                out.add(new int[]{i, end});
                break;
            }
        }
        return out;
    }

    private static boolean boundaryOk(String text, int start, int end, String term) {
        boolean latin = isLatinOrDigit(term.charAt(0));
        if (start > 0) {
            char p = text.charAt(start - 1);
            if (latin ? isLatinOrDigit(p) : isHangul(p)) {
                return false;
            }
        }
        if (isLatinOrDigit(term.charAt(term.length() - 1)) && end < text.length()) {
            return !isLatinOrDigit(text.charAt(end));
        }
        return true;
    }

    static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
    }

    private static boolean isLatinOrDigit(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
