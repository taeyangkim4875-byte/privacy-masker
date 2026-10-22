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
 * 성씨 + 1~2음절에, 앞뒤 문맥 단서("성함은 ~", "~ 고객님", "~입니다")가 있을 때만 이름으로 본다.
 * 3단계 NER 모델의 비교 기준이자 사전 라벨러로 쓴다. 문맥 없이 이름만 말하면 놓친다.
 */
public final class NameRuleDetector implements Detector {

    static final String SURNAME_2 = "남궁|황보|제갈|선우|독고|사공|서문";
    static final String SURNAME_1 =
            "김이박최정강조윤장임한오서신권황안송류유전홍고문양손배백허남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국";

    private static final String NAME =
            "(?:(?:" + SURNAME_2 + ")[가-힣]{1,2}|[" + SURNAME_1 + "][가-힣]{1,2})";

    /** 이름 뒤 문맥: "김태양 고객님", "김태양입니다", "김태양이에요" */
    private static final Pattern SUFFIX = Pattern.compile(
            "(?<![가-힣])(" + NAME + ")(?=\\s?(?:고객님|님|씨|고객|입니다|이에요|예요|이고요|이구요|이라고|라고|이요|이고))");

    /** 이름 앞 문맥: "성함은 김태양", "예금주 김태양" */
    private static final Pattern PREFIX = Pattern.compile(
            "(?:성함|이름|명의|예금주|고객명|본인)(?:은|는|이|이요|요|:)?\\s{0,3}(" + NAME + ")"
                    + "(?=$|[^가-힣]|이에요|입니다|이고|이요|요|이|으로|로|씨|님|고객)");

    /** 이름처럼 보이지만 이름이 아닌 일반어 */
    private static final Set<String> STOPWORDS = Set.of(
            "고객", "선생", "기사", "사장", "부장", "과장", "차장", "원장", "반장", "조장", "대리", "주임", "실장", "팀장",
            "이번", "이분", "저분", "정말", "지금", "문의", "신청", "확인", "전화", "안내", "한번", "오늘", "내일",
            "이상", "이체", "이용", "연락", "변경", "명의", "본인", "성함", "상담", "계좌", "카드", "우리", "남편",
            "아들", "여보", "주소", "결제", "정상", "고객님", "상담원", "이름", "이거", "이게", "그거", "저희", "제가",
            "이쪽", "저쪽", "방금", "조금", "정도", "도착", "배송", "환불", "안녕", "감사", "죄송", "이제", "이미",
            "전부", "전체", "최근", "최대", "최소", "한도", "인증", "임시", "장기", "단기", "신규", "기존", "정보",
            "강제", "조회", "주문", "주말", "평일", "오전", "오후", "고객센터", "유선", "무선", "문자", "공인", "민원");

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
        return spans;
    }

    private void collect(Pattern p, String text, List<Span> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            int s = m.start(1);
            int e = m.end(1);
            String candidate = text.substring(s, e);
            if (STOPWORDS.contains(candidate)) {
                continue;
            }
            out.add(new Span(s, e, EntityType.NAME, name()));
        }
    }
}
