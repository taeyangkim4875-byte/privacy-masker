package com.piimask.core.mask;

/**
 * 치환 정책. 모두 비가역이다.
 */
public enum MaskingPolicy {
    /** [전화번호] 처럼 유형 태그로 치환 */
    TAG("유형 태그"),
    /** 글자·숫자를 모두 * 로, 구분자는 유지 */
    FULL("전체 마스킹"),
    /** 유형별 관행에 따라 일부만 노출: 010-****-5678, 김*양 */
    PARTIAL("부분 마스킹"),
    /** 솔트 + SHA-256 앞 12자리. 같은 값은 같은 토큰이 되어 통계·조인에 쓸 수 있다 */
    HASH("해시 토큰");

    private final String label;

    MaskingPolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
