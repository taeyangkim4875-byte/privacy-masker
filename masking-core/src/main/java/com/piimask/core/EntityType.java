package com.piimask.core;

/**
 * 탐지 대상 개인정보 유형.
 * priority 가 낮을수록 겹치는 스팬 병합 시 우선한다.
 */
public enum EntityType {
    RRN("주민등록번호", 1),
    CARD("카드번호", 2),
    PHONE("전화번호", 3),
    ACCOUNT("계좌번호", 4),
    EMAIL("이메일", 5),
    NAME("이름", 6);

    private final String label;
    private final int priority;

    EntityType(String label, int priority) {
        this.label = label;
        this.priority = priority;
    }

    public String label() {
        return label;
    }

    public int priority() {
        return priority;
    }
}
