package com.piimask.core.detect;

import com.piimask.core.EntityType;
import com.piimask.core.Span;
import com.piimask.core.dict.NameDictionary;
import com.piimask.core.dict.TermIndex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 규칙 기반 이름 탐지기(베이스라인).
 *
 * 잡는 형태
 *  1. 이름 + 호칭·서술: "김태양 고객님", "장나예,회원님이", "김태양입니다"
 *  2. 단서 + 이름: "성함은 김태양", "예금주 김태양"
 *  3. SSML·태그 텍스트 노드에 이름만 단독: "<prosody ...>장나예</prosody>"
 *  4. 한 글자씩 끊어 읽기: "고,영,환" / "고, 영, 환"
 *  5. 영문(로마자) 이름: "KIM EUNMIN,회원님", "<prosody ...>LEE SEOYEON</prosody>"
 *
 * 성씨와 예외어는 {@link NameDictionary} 에서 온다. 예외어가 나온 자리 안에 완전히 들어가는 스팬은 버린다
 * ("지점장님" 의 "지점장", "명세서입니다" 의 "명세서"). 진짜 이름이 예외어 밖으로 나가면 남는다.
 *
 * 3단계 NER 모델의 비교 기준이자 사전 라벨러로 쓴다. 문맥 없이 문장 중간에 나온 이름은 놓친다.
 */
public final class NameRuleDetector implements Detector {

    /** 이름 뒤에 오는 호칭·서술(쉼표·공백 허용) */
    private static final String SUFFIX_CUE =
            "\\s?,?\\s?(?:회원님|회원|고객님|고객|님|씨|입니다|이에요|예요|이고요|이구요|이라고|라고|이요|이고)";

    /** 로마자 성씨(여권 표기 위주) */
    private static final String ROMAN_SURNAME =
            "KIM|LEE|YI|RHEE|PARK|PAK|CHOI|CHOE|JUNG|JEONG|CHUNG|KANG|CHO|JO|YOON|YUN|JANG|CHANG|LIM|IM|HAN|OH|SEO|SUH|"
                    + "SHIN|SIN|KWON|HWANG|AHN|AN|SONG|YOO|YU|RYU|JEON|JUN|HONG|KO|GO|MOON|MUN|YANG|SON|BAE|BAEK|HEO|HUR|"
                    + "NAM|SIM|SHIM|NOH|ROH|HA|KWAK|SUNG|CHA|JOO|JU|WOO|KOO|KU|MIN|JIN|NA|JI|UM|EOM";

    private static final String ROMAN_NAME =
            "(?:" + ROMAN_SURNAME + ")(?:[ -][A-Z]{2,10}){1,2}";

    /** 영문 이름 + 호칭, 또는 텍스트 노드에 영문 이름만 */
    private static final Pattern ROMAN = Pattern.compile(
            "(?<![A-Za-z])(" + ROMAN_NAME + ")(?![A-Za-z])(?=\\s?,?\\s?(?:회원님|회원|고객님|고객|님|씨)|\\s*<)");

    /** 숫자 읽기 음절: "이,삼,사" 같은 숫자 끊어 읽기를 이름으로 오인하지 않도록 */
    private static final String DIGIT_SYLLABLES = "공영일이삼사오육칠팔구";

    private final Pattern suffix;
    private final Pattern prefix;
    /** 태그 사이 텍스트 노드가 이름 하나뿐: >장나예< */
    private final Pattern nodeOnly;
    /** 한 글자씩 끊어 읽기: 고,영,환 (첫 글자는 성씨) */
    private final Pattern spelled;
    /** 예외어: 이 단어가 나온 자리 안의 스팬은 버린다 */
    private final TermIndex exceptions;
    /** "*점" 형태 예외: 이 글자로 끝나는 스팬은 버린다 */
    private final Set<String> exceptionEndings;

    /** 기본 사전(리소스 파일의 성씨·예외어) */
    public NameRuleDetector() {
        this(NameDictionary.builtIn());
    }

    public NameRuleDetector(NameDictionary dict) {
        // 성씨는 정규식에 그대로 들어가므로 한글 음절만 받는다
        if (dict.surnames().stream().anyMatch(x -> x.isEmpty() || !x.chars().allMatch(c -> c >= '가' && c <= '힣'))) {
            throw new IllegalArgumentException("성씨는 한글 음절만 쓸 수 있습니다");
        }
        StringBuilder one = new StringBuilder();
        dict.surnames().stream().filter(x -> x.length() == 1).sorted().forEach(one::append);
        if (one.isEmpty()) {
            throw new IllegalArgumentException("1글자 성씨가 없습니다");
        }
        List<String> two = dict.surnames().stream().filter(x -> x.length() >= 2).sorted().toList();
        String surname1 = one.toString();
        String name = "(?:" + (two.isEmpty() ? "" : "(?:" + String.join("|", two) + ")[가-힣]{1,2}|")
                + "[" + surname1 + "][가-힣]{1,2})";

        suffix = Pattern.compile("(?<![가-힣])(" + name + ")(?=" + SUFFIX_CUE + ")");
        prefix = Pattern.compile(
                "(?:성함|이름|명의|예금주|고객명|회원명|본인)(?:은|는|이|이요|요|:)?\\s{0,3}(" + name + ")"
                        + "(?=$|[^가-힣]|이에요|입니다|이고|이요|요|이|으로|로|씨|님|고객|회원)");
        nodeOnly = Pattern.compile(">\\s*(" + name + ")\\s*<");
        spelled = Pattern.compile(
                "(?<![가-힣,])([" + surname1 + "]\\s?,\\s?[가-힣]\\s?,\\s?[가-힣](?:\\s?,\\s?[가-힣])?)(?![가-힣])");

        List<String> words = new ArrayList<>();
        Set<String> endings = new HashSet<>();
        for (String e : dict.exceptions()) {
            if (e.startsWith(NameDictionary.SUFFIX_MARK)) {
                endings.add(e.substring(NameDictionary.SUFFIX_MARK.length()));
            } else {
                words.add(e);
            }
        }
        exceptions = new TermIndex(words);
        exceptionEndings = Set.copyOf(endings);
    }

    @Override
    public EntityType type() {
        return EntityType.NAME;
    }

    @Override
    public String name() {
        return "이름(규칙)";
    }

    @Override
    public List<Span> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        collect(suffix, text, spans);
        collect(prefix, text, spans);
        collect(nodeOnly, text, spans);
        collectSpelled(text, spans);
        collectRoman(text, spans);
        return spans.isEmpty() ? spans : withoutExceptions(text, spans);
    }

    /** 예외어가 나온 자리 안에 완전히 들어가는 스팬과 "*X" 끝 글자로 끝나는 스팬을 버린다 */
    private List<Span> withoutExceptions(String text, List<Span> spans) {
        List<int[]> hits = exceptions.find(text, false);
        List<Span> out = new ArrayList<>(spans.size());
        for (Span s : spans) {
            String w = text.substring(s.start(), s.end());
            boolean excepted = hits.stream().anyMatch(h -> h[0] <= s.start() && s.end() <= h[1])
                    || exceptionEndings.stream().anyMatch(w::endsWith);
            if (!excepted) {
                out.add(s);
            }
        }
        return out;
    }

    private void collect(Pattern p, String text, List<Span> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(new Span(m.start(1), m.end(1), EntityType.NAME, name()));
        }
    }

    private void collectSpelled(String text, List<Span> out) {
        Matcher m = spelled.matcher(text);
        while (m.find()) {
            String syllables = m.group(1).replaceAll("[\\s,]", "");
            boolean allDigits = syllables.chars().allMatch(c -> DIGIT_SYLLABLES.indexOf(c) >= 0);
            if (!allDigits) {
                out.add(new Span(m.start(1), m.end(1), EntityType.NAME, name()));
            }
        }
    }

    private void collectRoman(String text, List<Span> out) {
        Matcher m = ROMAN.matcher(text);
        while (m.find()) {
            out.add(new Span(m.start(1), m.end(1), EntityType.NAME, name()));
        }
    }
}
