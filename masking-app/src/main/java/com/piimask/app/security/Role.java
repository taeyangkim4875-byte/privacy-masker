package com.piimask.app.security;

/**
 * 역할. 한 계정이 여러 역할을 가질 수 있다. 권한 규칙은 {@link SecurityConfig} 한 곳에 모은다.
 */
public enum Role {
    /** 계정 관리 + 감사 로그 조회. 설정·실행은 못 한다(직무 분리) */
    ADMIN("관리자"),
    /** DB 접속·작업 설정, 미리보기·탐지 테스트(원문이 보이는 기능) */
    CONFIGURER("설정자"),
    /** 작업 실행·중지 */
    OPERATOR("실행자"),
    /** 실행 이력·감사 로그 조회만 */
    AUDITOR("감사자");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
