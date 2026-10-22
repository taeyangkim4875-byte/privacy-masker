package com.piimask.core;

/**
 * 원문 내 탐지 구간. start 포함, end 미포함(String#substring 규약).
 * 원문 값 자체는 담지 않는다. 로그·직렬화로 원문이 새지 않게 하기 위함.
 */
public record Span(int start, int end, EntityType type, String detector) {

    public Span {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid span range: " + start + ".." + end);
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(Span other) {
        return start < other.end && other.start < end;
    }
}
