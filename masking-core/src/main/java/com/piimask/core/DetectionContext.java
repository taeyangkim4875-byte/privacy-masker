package com.piimask.core;

/**
 * 탐지 문맥: 이 발화 앞뒤 대화에서 얻은 단서. 원문은 담지 않는다.
 *
 * @param nameExpected 직전 상담사 발화가 이름을 묻는 말이었다(이 발화는 그 답)
 */
public record DetectionContext(boolean nameExpected) {

    public static final DetectionContext NONE = new DetectionContext(false);
    public static final DetectionContext NAME_ANSWER = new DetectionContext(true);
}
