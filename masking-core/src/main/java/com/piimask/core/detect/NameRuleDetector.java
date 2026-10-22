package com.piimask.core.detect;

import com.piimask.core.EntityType;
import com.piimask.core.Span;

import java.util.ArrayList;
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
 * 3단계 NER 모델의 비교 기준이자 사전 라벨러로 쓴다. 문맥 없이 문장 중간에 나온 이름은 놓친다.
 */
public final class NameRuleDetector implements Detector {

    static final String SURNAME_2 = "남궁|황보|제갈|선우|독고|사공|서문";
    static final String SURNAME_1 =
            "김이박최정강조윤장임한오서신권황안송류유전홍고문양손배백허남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국";

    private static final String NAME =
            "(?:(?:" + SURNAME_2 + ")[가-힣]{1,2}|[" + SURNAME_1 + "][가-힣]{1,2})";

    /** 이름 뒤에 오는 호칭·서술(쉼표·공백 허용) */
    private static final String SUFFIX_CUE =
            "\\s?,?\\s?(?:회원님|회원|고객님|고객|님|씨|입니다|이에요|예요|이고요|이구요|이라고|라고|이요|이고)";

    private static final Pattern SUFFIX = Pattern.compile(
            "(?<![가-힣])(" + NAME + ")(?=" + SUFFIX_CUE + ")");

    private static final Pattern PREFIX = Pattern.compile(
            "(?:성함|이름|명의|예금주|고객명|회원명|본인)(?:은|는|이|이요|요|:)?\\s{0,3}(" + NAME + ")"
                    + "(?=$|[^가-힣]|이에요|입니다|이고|이요|요|이|으로|로|씨|님|고객|회원)");

    /** 태그 사이 텍스트 노드가 이름 하나뿐: >장나예< */
    private static final Pattern NODE_ONLY = Pattern.compile(
            ">\\s*(" + NAME + ")\\s*<");

    /** 한 글자씩 끊어 읽기: 고,영,환 (첫 글자는 성씨) */
    private static final Pattern SPELLED = Pattern.compile(
            "(?<![가-힣,])([" + SURNAME_1 + "]\\s?,\\s?[가-힣]\\s?,\\s?[가-힣](?:\\s?,\\s?[가-힣])?)(?![가-힣])");

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

    /** 이름처럼 보이지만 이름이 아닌 일반어 */
    private static final Set<String> STOPWORDS = Set.of(
            "고객", "선생", "기사", "사장", "부장", "과장", "차장", "원장", "반장", "조장", "대리", "주임", "실장", "팀장",
            "이번", "이분", "저분", "정말", "지금", "문의", "신청", "확인", "전화", "안내", "한번", "오늘", "내일",
            "이상", "이체", "이용", "연락", "변경", "명의", "본인", "성함", "상담", "계좌", "카드", "우리", "남편",
            "아들", "여보", "주소", "결제", "정상", "고객님", "상담원", "이름", "이거", "이게", "그거", "저희", "제가",
            "이쪽", "저쪽", "방금", "조금", "정도", "도착", "배송", "환불", "안녕", "감사", "죄송", "이제", "이미",
            "전부", "전체", "최근", "최대", "최소", "한도", "인증", "임시", "장기", "단기", "신규", "기존", "정보",
            "강제", "조회", "주문", "주말", "평일", "오전", "오후", "고객센터", "유선", "무선", "문자", "공인", "민원",
            "회원", "회원님", "일번", "삼번", "사번", "오번", "구번", "공번", "별표", "우물정");

    /** 숫자 읽기 음절: "이,삼,사" 같은 숫자 끊어 읽기를 이름으로 오인하지 않도록 */
    private static final String DIGIT_SYLLABLES = "공영일이삼사오육칠팔구";

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
        collect(SUFFIX, text, spans);
        collect(PREFIX, text, spans);
        collect(NODE_ONLY, text, spans);
        collectSpelled(text, spans);
        collectRoman(text, spans);
        return spans;
    }

    private void collect(Pattern p, String text, List<Span> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            int s = m.start(1);
            int e = m.end(1);
            if (STOPWORDS.contains(text.substring(s, e))) {
                continue;
            }
            out.add(new Span(s, e, EntityType.NAME, name()));
        }
    }

    private void collectSpelled(String text, List<Span> out) {
        Matcher m = SPELLED.matcher(text);
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
