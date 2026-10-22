package com.piimask.app.security;

import com.piimask.app.security.UserRepository.AppUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 세션에 든 로그인 정보를 매 요청 계정 테이블과 맞춘다.
 * 사용 중지·삭제된 계정은 세션을 끊고, 역할이 바뀌었으면 바로 반영한다(다시 로그인할 필요 없음).
 * 사용자 이름 기준이라 SAML 로그인을 붙여도 그대로 쓴다.
 */
final class AccountRefreshFilter extends OncePerRequestFilter {

    /** 세션 속성: 로그인(또는 본인 비밀번호 변경) 시각 */
    static final String LOGIN_AT = "piimask.loginAt";

    private final UserRepository users;
    private final HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();

    AccountRefreshFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            Optional<AppUser> u = users.findByUsername(auth.getName());
            if (u.isEmpty() || !u.get().enabled() || loggedInBeforePasswordChange(req, u.get())) {
                HttpSession s = req.getSession(false);
                if (s != null) {
                    s.invalidate();
                }
                // 비로그인 상태로 계속 진행 → 보호된 API 는 401, 화면 정적 파일은 그대로
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(new AnonymousAuthenticationToken("piimask-anonymous", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
                SecurityContextHolder.setContext(ctx);
            } else {
                List<? extends GrantedAuthority> fresh = AccountService.authorities(u.get().roles());
                Set<String> now = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
                if (!now.equals(fresh.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toCollection(HashSet::new)))) {
                    SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                    ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(auth.getPrincipal(), null, fresh));
                    SecurityContextHolder.setContext(ctx);
                    repo.saveContext(ctx, req, res);
                }
            }
        }
        chain.doFilter(req, res);
    }

    /** 비밀번호가 바뀐 뒤에는 그 전에 로그인한 세션을 끊는다(관리자 재설정 = 탈취 의심 대응) */
    private static boolean loggedInBeforePasswordChange(HttpServletRequest req, AppUser u) {
        HttpSession s = req.getSession(false);
        Object at = s == null ? null : s.getAttribute(LOGIN_AT);
        return at instanceof LocalDateTime loginAt && u.passwordChangedAt() != null && loginAt.isBefore(u.passwordChangedAt());
    }
}
