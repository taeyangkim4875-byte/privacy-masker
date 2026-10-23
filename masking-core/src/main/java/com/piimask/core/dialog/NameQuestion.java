package com.piimask.core.dialog;

import java.util.regex.Pattern;

/**
 * 상담사 발화가 이름을 묻는 말인지(규칙). "성함 말씀해 주시겠어요?", "예금주 성함 알려 주세요", "성함이요?".
 * STT 결과에는 물음표가 없는 경우가 많아 요청 표현("여쭤", "말씀", "알려")과 "성함이요" 로 끝나는 꼴도 질문으로 본다.
 */
public final class NameQuestion {

    private static final Pattern NAME_WORD = Pattern.compile("성함|존함|이름|명의자분?|예금주");
    private static final Pattern ASK = Pattern.compile(
            "말씀|알려|확인|어떻게|되세요|되시|되십|부탁|불러|여쭤|여쭙|뭐|누구|[?？]");
    /** "…성함이요", "성함은요" 로 끝나는 짧은 되물음(문장부호 없음) */
    private static final Pattern ENDS_WITH_NAME_WORD = Pattern.compile("(성함|존함|이름)(이|은)?요\\s*[.?？!]*\\s*$");
    /** 이름 단어 없이 묻는 말 */
    private static final Pattern WHO = Pattern.compile("누구세요|누구시죠|누구신가요");
    /** 이름을 묻지 않고 안내·자기소개만 하는 말 */
    private static final Pattern NOT_ASK = Pattern.compile(
            "확인(되었|됐|했|하였)|감사합니다|으로 (발송|등록)|확인해 (보|드리)겠|어렵|변경|제 이름은|상담(원|사) \\S+(입니다|이에요|예요)");

    private NameQuestion() {
    }

    public static boolean isNameQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (NOT_ASK.matcher(text).find()) {
            return false;
        }
        if (WHO.matcher(text).find() || ENDS_WITH_NAME_WORD.matcher(text).find()) {
            return true;
        }
        return NAME_WORD.matcher(text).find() && ASK.matcher(text).find();
    }
}
