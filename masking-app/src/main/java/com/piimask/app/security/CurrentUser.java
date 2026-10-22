package com.piimask.app.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 현재 요청의 사용자 이름. 인증 방식(로컬·SAML)과 무관하게 감사 로그·화면이 이것만 본다.
 * 사용자 요청이 아닌 곳(기동, 스케줄)에서는 시스템 이름을 쓴다.
 */
public final class CurrentUser {

    public static final String SYSTEM = "system";
    public static final String SCHEDULER = "system:scheduler";

    private CurrentUser() {
    }

    /** 로그인 사용자 이름, 없으면 {@link #SYSTEM} */
    public static String name() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
            return SYSTEM;
        }
        return a.getName();
    }
}
