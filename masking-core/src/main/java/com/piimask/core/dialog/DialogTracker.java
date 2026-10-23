package com.piimask.core.dialog;

import com.piimask.core.DetectionContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 통화별 대화 상태: "직전 상담사 발화가 이름 질문이었는지"만 기억한다(원문은 기억하지 않음).
 * 발화를 순서대로 {@link #next} 에 넣으면 그 발화에 쓸 문맥을 돌려준다. 질문 다음 고객 발화 1건에만 적용하고 지운다.
 * 열린 통화는 최대 maxCalls 개(오래 안 본 통화부터 잊음). 스레드 안전하지 않다(배치 스텝 하나에서 순서대로 쓴다).
 */
public final class DialogTracker {

    private enum State { SEEN, ASKED }

    private final Map<String, State> calls;

    public DialogTracker(int maxCalls) {
        this.calls = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
                return size() > maxCalls;
            }
        };
    }

    /** 이번 실행에서 이 통화를 이미 봤는지(처음 보는 통화의 첫 고객 발화는 원천의 직전 행으로 문맥을 이어 붙인다) */
    public boolean seen(String callId) {
        return callId != null && calls.containsKey(callId);
    }

    /** 실행 경계 너머의 직전 발화로 상태를 채운다(직전 행이 상담사의 이름 질문이면 ASKED) */
    public void prime(String callId, boolean agent, String text) {
        if (callId != null) {
            calls.put(callId, agent && NameQuestion.isNameQuestion(text) ? State.ASKED : State.SEEN);
        }
    }

    /** 발화 하나를 넣고, 그 발화에 쓸 문맥을 받는다. 상담사 발화 자체는 문맥 없이 탐지한다 */
    public DetectionContext next(String callId, boolean agent, String text) {
        if (callId == null) {
            return DetectionContext.NONE;
        }
        if (agent) {
            calls.put(callId, NameQuestion.isNameQuestion(text) ? State.ASKED : State.SEEN);
            return DetectionContext.NONE;
        }
        State before = calls.put(callId, State.SEEN);
        return before == State.ASKED ? DetectionContext.NAME_ANSWER : DetectionContext.NONE;
    }
}
