package com.piimask.core.normalize;

import java.util.Arrays;

/**
 * 정규화 텍스트의 각 글자가 원문의 어느 구간 [start, end) 에서 왔는지.
 * 정규화로 길이가 바뀌어도(예: "공일공에 " → "010-") 스팬을 원문 위치로 되돌릴 수 있다.
 */
public final class OffsetMap {

    private int[] starts;
    private int[] ends;
    private int size;

    OffsetMap(int capacity) {
        starts = new int[Math.max(16, capacity)];
        ends = new int[starts.length];
    }

    void add(int origStart, int origEnd) {
        if (size == starts.length) {
            starts = Arrays.copyOf(starts, size * 2);
            ends = Arrays.copyOf(ends, size * 2);
        }
        starts[size] = origStart;
        ends[size] = origEnd;
        size++;
    }

    public int size() {
        return size;
    }

    /** 정규화 텍스트 글자 i 의 원문 시작 위치 */
    public int originalStart(int i) {
        return starts[i];
    }

    /** 정규화 텍스트 글자 i 의 원문 끝 위치(미포함) */
    public int originalEnd(int i) {
        return ends[i];
    }

    /** 정규화 텍스트 스팬 [start, end) → 원문 구간 {start, end} */
    public int[] toOriginal(int start, int end) {
        if (start < 0 || end > size || end <= start) {
            throw new IllegalArgumentException("span out of range: " + start + ".." + end);
        }
        return new int[]{starts[start], ends[end - 1]};
    }
}
