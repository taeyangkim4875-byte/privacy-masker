package com.piimask.app.batch;

import com.piimask.core.DetectionContext;
import com.piimask.core.dialog.DialogTracker;

import java.util.Optional;

/**
 * 배치 한 실행의 대화 문맥: 행 값 배열에서 통화 ID·화자·발화 위치를 알고, 통화별 상태(DialogTracker)를 들고 있다.
 * 이전 실행이 있으면(시작 워터마크가 있으면), 처음 보는 통화의 첫 행이 고객 발화일 때 원천에서 그 통화의 직전 행 1건을
 * 시작 워터마크 이하 범위에서 읽어(SELECT) 문맥을 잇는다. 조회는 실행당 {@link #MAX_LOOKUPS} 번까지.
 * 원문은 기억하지 않는다(통화별 "직전이 이름 질문이었나" 만).
 * 행은 PK 순서로 한 번씩만 들어와야 한다: 스텝을 fault-tolerant(재시도·건너뛰기 재처리)로 바꾸면 상태가 어긋난다.
 */
final class DialogBinding {

    /** 원천의 직전 행(같은 통화, pk 보다 작고 upTo 이하인 PK 중 가장 큰 것)의 화자·발화 */
    interface PreviousRow {
        Optional<Object[]> find(String callId, String pk, String upTo);
    }

    static final int MAX_OPEN_CALLS = 10_000;
    static final int MAX_LOOKUPS = 1_000;

    private final int callIndex;
    private final int speakerIndex;
    private final int textIndex;
    private final String agentValue;
    private final PreviousRow previous;
    /** 이 실행의 시작 워터마크(첫 실행이면 null → 경계 너머 행이 없으므로 조회하지 않음) */
    private final String startWatermark;
    private final DialogTracker tracker = new DialogTracker(MAX_OPEN_CALLS);
    private int lookups;

    DialogBinding(int callIndex, int speakerIndex, int textIndex, String agentValue, PreviousRow previous,
                  String startWatermark) {
        this.callIndex = callIndex;
        this.speakerIndex = speakerIndex;
        this.textIndex = textIndex;
        this.agentValue = agentValue;
        this.previous = previous;
        this.startWatermark = startWatermark;
    }

    int lookups() {
        return lookups;
    }

    /** 원본 값(마스킹 전)으로 이 행에 쓸 문맥을 정한다. 행은 PK 순서로 들어온다 */
    DetectionContext contextFor(String pk, Object[] values) {
        String callId = str(values[callIndex]);
        String speaker = str(values[speakerIndex]);
        // 통화 ID·화자가 비면 상태를 건드리지 않는다
        if (callId == null || speaker == null) {
            return DetectionContext.NONE;
        }
        boolean agent = isAgent(speaker);
        String text = str(values[textIndex]);
        if (!agent && !tracker.seen(callId) && startWatermark != null && lookups < MAX_LOOKUPS) {
            lookups++;
            previous.find(callId, pk, startWatermark).ifPresent(p -> {
                String ps = str(p[0]);
                if (ps != null) {
                    tracker.prime(callId, isAgent(ps), str(p[1]));
                }
            });
        }
        return tracker.next(callId, agent, text);
    }

    boolean isAgent(String speaker) {
        return agentValue.equalsIgnoreCase(speaker.strip());
    }

    static String str(Object v) {
        return DialogValues.str(v);
    }
}
