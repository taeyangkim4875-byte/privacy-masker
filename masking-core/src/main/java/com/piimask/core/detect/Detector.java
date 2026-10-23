package com.piimask.core.detect;

import com.piimask.core.DetectionContext;
import com.piimask.core.EntityType;
import com.piimask.core.Span;

import java.util.List;

/**
 * 개인정보 탐지기. 구현체는 상태가 없어야 하며 여러 스레드에서 동시에 호출된다.
 */
public interface Detector {

    /** 이 탐지기가 찾는 유형 */
    EntityType type();

    /** 탐지기 식별 이름(화면·이력 표시용) */
    String name();

    List<Span> detect(String text);

    /** 문맥을 쓰는 탐지기만 다시 정의한다. 기본은 문맥 무시 */
    default List<Span> detect(String text, DetectionContext ctx) {
        return detect(text);
    }
}
