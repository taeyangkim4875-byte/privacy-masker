package com.piimask.core.detect;

import com.piimask.core.EntityType;

import java.util.List;

/**
 * 1단계 기본 탐지기 묶음(정규식 + 검증기).
 * 숫자 앞뒤에 다른 숫자가 붙어 있으면 매치하지 않도록 (?&lt;![0-9]) / (?![0-9]) 경계를 둔다.
 */
public final class DefaultDetectors {

    private DefaultDetectors() {
    }

    public static final String MOBILE =
            "(?<![0-9])01[016789][-.\\s]?[0-9]{3,4}[-.\\s]?[0-9]{4}(?![0-9])";

    public static final String LANDLINE =
            "(?<![0-9])0(?:2|3[1-3]|4[1-4]|5[1-5]|6[1-4]|70)[-.)\\s]?[0-9]{3,4}[-.\\s]?[0-9]{4}(?![0-9])";

    public static final String CARD_16 =
            "(?<![0-9])[0-9]{4}[-\\s]?[0-9]{4}[-\\s]?[0-9]{4}[-\\s]?[0-9]{4}(?![0-9])";

    public static final String CARD_15 =
            "(?<![0-9])3[47][0-9]{2}[-\\s]?[0-9]{6}[-\\s]?[0-9]{5}(?![0-9])";

    public static final String RRN =
            "(?<![0-9])[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])\\s?-?\\s?[1-8][0-9]{6}(?![0-9])";

    public static final String ACCOUNT_HYPHEN =
            "(?<![0-9-])[0-9]{2,6}-[0-9]{2,6}-[0-9]{2,8}(?:-[0-9]{1,3})?(?![0-9-])";

    public static final String ACCOUNT_PLAIN =
            "(?<![0-9])[0-9]{10,14}(?![0-9])";

    public static final String EMAIL =
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}";

    /** SSML: <say-as interpret-as="telephone">...</say-as> 안의 값은 길이와 관계없이 전화번호로 본다 */
    public static final String SSML_TELEPHONE =
            "<say-as\\b[^>]*interpret-as\\s*=\\s*[\"']telephone[\"'][^>]*>\\s*([^<]*?[0-9][^<]*?)\\s*</\\s*say-as\\s*>";

    private static final String[] ACCOUNT_KEYWORDS = {"계좌", "입금", "환불", "은행", "통장", "이체"};

    public static List<Detector> all() {
        return List.of(
                new RegexDetector("휴대폰", EntityType.PHONE, MOBILE, null),
                new RegexDetector("유선전화", EntityType.PHONE, LANDLINE, null),
                new RegexDetector("SSML 전화(say-as)", EntityType.PHONE,
                        java.util.regex.Pattern.compile(SSML_TELEPHONE, java.util.regex.Pattern.CASE_INSENSITIVE), 1, null),
                new RegexDetector("카드16(Luhn)", EntityType.CARD, CARD_16,
                        (m, t, s, e) -> Validators.luhn(m)),
                new RegexDetector("카드15(Luhn)", EntityType.CARD, CARD_15,
                        (m, t, s, e) -> Validators.luhn(m)),
                new RegexDetector("주민·외국인등록번호", EntityType.RRN, RRN,
                        (m, t, s, e) -> Validators.residentNumber(m)),
                new RegexDetector("계좌(하이픈)", EntityType.ACCOUNT, ACCOUNT_HYPHEN,
                        (m, t, s, e) -> {
                            int n = Validators.digitsOnly(m).length();
                            return n >= 10 && n <= 16;
                        }),
                new RegexDetector("계좌(문맥)", EntityType.ACCOUNT, ACCOUNT_PLAIN,
                        (m, t, s, e) -> Validators.keywordBefore(t, s, 20, ACCOUNT_KEYWORDS)),
                new RegexDetector("이메일", EntityType.EMAIL, EMAIL, null),
                new NameRuleDetector()
        );
    }
}
