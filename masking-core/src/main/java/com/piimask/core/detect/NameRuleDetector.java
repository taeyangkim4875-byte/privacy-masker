package com.piimask.core.detect;

import com.piimask.core.EntityType;
import com.piimask.core.Span;
import com.piimask.core.dict.NameDictionary;
import com.piimask.core.dict.NameHints;
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
 * 6. 점수화(약한 단서): "김태양인데요", "박지훈한테", "받는 분 정하윤" 처럼 위 단서가 없을 때
 *    후보(어절 첫머리의 성씨 + 1~2음절)에 단서·이름 음절·길이 점수를 매겨 {@link #THRESHOLD} 이상이면 잡는다.
 *    강한 단서 규칙(1~5)과 겹치면 그쪽을 쓰므로 점수화는 잡는 범위를 넓히기만 한다. 탐지기 이름은 "이름(점수)".
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

    public static final String SCORE_DETECTOR = "이름(점수)";

    // ---- 점수화 가중치(한 곳에서만 바꾼다. 바꾸면 NameEvalTest·SeedRegressionTest 수치를 확인) ----
    /** 이 점수 이상이면 이름. Recall 우선: 약한 단서 하나 + 흔한 이름 음절이면 통과 */
    static final int THRESHOLD = 3;
    static final int W_RIGHT_CUE = 2;
    static final int W_LEFT_CUE = 2;
    /** 이름 음절이 모두 흔한 이름 음절 */
    static final int W_ALL_COMMON = 1;
    /** 흔한 이름 음절이 아닌 음절 하나마다 */
    static final int W_UNCOMMON = -1;
    /** 이름에 거의 안 쓰는 음절(어미·조사 등)이 있으면 */
    static final int W_NON_NAME = -3;
    /** 성씨 + 2음절 */
    static final int W_LONG = 1;

    /** 이름 바로 뒤에 붙는 약한 단서 */
    private static final String[] RIGHT_ATTACHED = {"인데요", "인데", "한테", "에게", "께서", "께", "이라는", "라는", "이란", "이랑"};
    /** 이름 뒤에 공백 하나를 두고 올 수 있는 약한 단서 */
    private static final String[] RIGHT_SPACED = {"명의", "앞으로", "본인"};
    /** 이름 앞 어절(공백·쉼표로 띄움)이 이것이면 약한 단서 */
    private static final String[] LEFT_CUES = {"저는", "받는 분", "받는분", "보내는 분", "수신인", "담당자", "보호자", "가입자",
            "신청인", "명의자", "대표자", "남편", "아내", "와이프", "아들", "딸", "어머니", "아버지", "엄마", "아빠", "동생", "언니",
            "오빠", "누나", "형"};
    /** 왼쪽 단서만 있을 때 후보의 마지막 음절이 이것이면 "명사 + 조사"로 보고 버린다("은"은 하은·지은 때문에 뺐다) */
    private static final String LEFT_ONLY_BAD_ENDINGS = "가도만는을를의로이";
    /** 왼쪽 단서만 있을 때 이름 뒤에 올 수 있는 조사(그 뒤는 어절 끝) */
    private static final String[] PARTICLES = {"이고요", "고요", "이고", "으로", "이랑", "이가", "이요", "로", "랑", "고", "가", "이",
            "은", "는", "을", "를", "의", "도", "요"};

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
    private final Set<String> exceptionWords;
    private final Set<String> surnames;
    private final NameHints hints = NameHints.builtIn();
    private final boolean scoring;

    private final NameDictionary dict;

    public boolean scoring() {
        return scoring;
    }

    public NameDictionary dictionary() {
        return dict;
    }

    /** 기본 사전(리소스 파일의 성씨·예외어) */
    public NameRuleDetector() {
        this(NameDictionary.builtIn());
    }

    public NameRuleDetector(NameDictionary dict) {
        this(dict, true);
    }

    /** scoring=false 면 점수화 없이 강한 단서 규칙만(평가 비교용) */
    public NameRuleDetector(NameDictionary dict, boolean scoring) {
        this.scoring = scoring;
        this.dict = dict;
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
        surnames = Set.copyOf(dict.surnames());
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
        exceptionWords = Set.copyOf(words);
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
        // 강한 단서 스팬은 예외어를 먼저 거른 뒤 점수 후보와 겹침을 본다(예외어로 지워질 스팬이 점수 후보를 막지 않게)
        List<Span> strong = spans.isEmpty() ? spans : withoutExceptions(text, spans);
        if (!scoring) {
            return strong;
        }
        List<Span> scored = new ArrayList<>();
        collectScored(text, strong, scored);
        if (scored.isEmpty()) {
            return strong;
        }
        List<Span> out = new ArrayList<>(strong);
        out.addAll(withoutExceptions(text, scored));
        return out;
    }

    /** 점수화 후보: 강한 단서 스팬과 겹치지 않는 자리에서만 */
    private void collectScored(String text, List<Span> strong, List<Span> out) {
        int i = 0;
        while (i < text.length()) {
            int end = i > 0 && isHangul(text.charAt(i - 1)) ? -1 : bestCandidate(text, i);
            if (end > 0) {
                int s = i;
                if (strong.stream().noneMatch(x -> x.start() < end && s < x.end())) {
                    out.add(new Span(s, end, EntityType.NAME, SCORE_DETECTOR));
                }
                i = end;
            } else {
                i++;
            }
        }
    }

    /** i 에서 시작하는 후보 중 기준점을 넘는 가장 높은 점수(같으면 긴 쪽)의 끝 위치, 없으면 -1 */
    private int bestCandidate(String text, int i) {
        int bestEnd = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int sl = 2; sl >= 1; sl--) {
            if (i + sl > text.length() || !surnames.contains(text.substring(i, i + sl))) {
                continue;
            }
            for (int gl = 2; gl >= 1; gl--) {
                int e = i + sl + gl;
                if (e > text.length()) {
                    continue;
                }
                int score = score(text, i, i + sl, e);
                if (score >= THRESHOLD && score > bestScore) {
                    bestScore = score;
                    bestEnd = e;
                }
            }
        }
        return bestEnd;
    }

    /** 후보 [start, end) (이름 음절은 [givenStart, end)) 의 점수. 단서가 없거나 조건이 안 맞으면 Integer.MIN_VALUE */
    int score(String text, int start, int givenStart, int end) {
        for (int k = start; k < end; k++) {
            if (!isHangul(text.charAt(k))) {
                return Integer.MIN_VALUE;
            }
        }
        boolean right = rightCue(text, end);
        boolean left = leftCue(text, start);
        if (!right && !left) {
            return Integer.MIN_VALUE;
        }
        // 두 글자 후보("지원인데요", "엄마 지인한테", "저는 주부인데요")는 일반어가 너무 많아 점수화하지 않는다.
        // 두 글자 이름은 강한 단서 규칙("김혁 고객님")으로만 잡는다
        if (end - start <= 2) {
            return Integer.MIN_VALUE;
        }
        if (!right) {
            // 왼쪽 단서만 있으면 이름이 어절 끝(또는 조사 뒤 어절 끝)이어야 하고("남편 고생했어요" 제외),
            // 마지막 음절이 조사·어미면 "명사 + 조사"로 본다("아버지 연세가", "저는 하나도", "저는 신한만")
            if (!wordEndsAfterParticle(text, end) || LEFT_ONLY_BAD_ENDINGS.indexOf(text.charAt(end - 1)) >= 0) {
                return Integer.MIN_VALUE;
            }
            // 예외어로 시작하면 "예외어 + 어미"로 본다("아내 명의예요"). 오른쪽 단서가 있으면("이상민한테") 그대로 본다
            for (int k = start + 2; k < end; k++) {
                if (exceptionWords.contains(text.substring(start, k))) {
                    return Integer.MIN_VALUE;
                }
            }
        }
        String word = text.substring(start, end);
        if (hints.place(word) || word.chars().allMatch(c -> DIGIT_SYLLABLES.indexOf(c) >= 0)) {
            return Integer.MIN_VALUE;
        }
        // 예외어 + 조사("주소가", "정말로")는 이름 후보가 아니다. "이상민" 처럼 뒤가 조사가 아니면 그대로 본다
        for (String p : PARTICLES) {
            if (word.length() > p.length() && word.endsWith(p)
                    && exceptionWords.contains(word.substring(0, word.length() - p.length()))) {
                return Integer.MIN_VALUE;
            }
        }
        int score = (right ? W_RIGHT_CUE : 0) + (left ? W_LEFT_CUE : 0);
        boolean allCommon = true;
        for (int k = givenStart; k < end; k++) {
            char c = text.charAt(k);
            if (hints.nonName(c)) {
                score += W_NON_NAME;
                allCommon = false;
            } else if (!hints.commonGiven(c)) {
                score += W_UNCOMMON;
                allCommon = false;
            }
        }
        if (allCommon) {
            score += W_ALL_COMMON;
        }
        if (end - givenStart >= 2) {
            score += W_LONG;
        }
        return score;
    }

    private static boolean rightCue(String text, int end) {
        for (String c : RIGHT_ATTACHED) {
            if (text.startsWith(c, end)) {
                return true;
            }
        }
        int e = end < text.length() && text.charAt(end) == ' ' ? end + 1 : end;
        for (String c : RIGHT_SPACED) {
            if (text.startsWith(c, e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean leftCue(String text, int start) {
        int k = start;
        while (k > 0 && (text.charAt(k - 1) == ' ' || text.charAt(k - 1) == ',')) {
            k--;
        }
        if (k == start) {
            return false;
        }
        for (String c : LEFT_CUES) {
            int s = k - c.length();
            if (s >= 0 && text.startsWith(c, s) && (s == 0 || !isHangul(text.charAt(s - 1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean wordEndsAfterParticle(String text, int end) {
        if (end == text.length() || !isHangul(text.charAt(end))) {
            return true;
        }
        for (String p : PARTICLES) {
            int e = end + p.length();
            if (text.startsWith(p, end) && (e == text.length() || !isHangul(text.charAt(e)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
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
