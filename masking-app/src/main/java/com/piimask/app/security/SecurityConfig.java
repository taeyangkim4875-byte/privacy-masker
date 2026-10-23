package com.piimask.app.security;

import com.piimask.app.support.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/**
 * 인증·권한 설정. 역할별 허용 API 는 이 파일의 표가 전부다(화면 메뉴 숨김은 편의일 뿐).
 *
 * <pre>
 * API                                         설정자 실행자 감사자 관리자
 * 현황·메타·작업 목록/상세                        O     O     O     O
 * DB 접속 전체, 스키마 탐색                       O
 * 작업 생성/수정/삭제, DDL, 처리 위치 초기화        O
 * 미리보기·탐지 테스트(원문 노출)                  O
 * 실행·중지                                            O
 * 실행 이력                                       O     O     O
 * 감사 로그                                                  O     O
 * 사용자 관리                                                       O
 * </pre>
 *
 * 인증: 로컬 계정 폼 로그인(JSON 응답) + 세션. SAML 을 붙일 때는 여기에 saml2Login() 을 추가하고
 * 응답 사용자를 app_user(auth_source=SAML)에 연결하면 된다. 역할·감사 로그는 인증 방식과 무관하게 동작한다.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private static final String CONFIGURER = Role.CONFIGURER.name();
    private static final String OPERATOR = Role.OPERATOR.name();
    private static final String AUDITOR = Role.AUDITOR.name();
    private static final String ADMIN = Role.ADMIN.name();

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AccountService accounts, AuditService audit,
                                           UserRepository users) throws Exception {
        http
                .csrf(c -> c.spa())
                .authorizeHttpRequests(a -> a
                        // 로그인 전
                        .requestMatchers(HttpMethod.GET, "/api/auth/setup-status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/setup").permitAll()
                        // 본인
                        .requestMatchers("/api/auth/me", "/api/auth/password").authenticated()
                        // 모두 조회
                        .requestMatchers(HttpMethod.GET, "/api/meta", "/api/dashboard", "/api/jobs", "/api/jobs/*").authenticated()
                        // 실행 이력
                        .requestMatchers(HttpMethod.GET, "/api/executions", "/api/jobs/*/executions").hasAnyRole(CONFIGURER, OPERATOR, AUDITOR)
                        // 실행·중지
                        .requestMatchers(HttpMethod.POST, "/api/jobs/*/run", "/api/jobs/*/stop").hasRole(OPERATOR)
                        // 설정(미리보기·탐지 테스트는 원문이 보이므로 설정자만)
                        .requestMatchers("/api/connections", "/api/connections/**").hasRole(CONFIGURER)
                        .requestMatchers(HttpMethod.POST, "/api/jobs", "/api/jobs/preview", "/api/jobs/ddl",
                                "/api/jobs/*/preview", "/api/jobs/*/reset-watermark", "/api/detect").hasRole(CONFIGURER)
                        .requestMatchers(HttpMethod.PUT, "/api/jobs/*").hasRole(CONFIGURER)
                        .requestMatchers(HttpMethod.DELETE, "/api/jobs/*").hasRole(CONFIGURER)
                        // 이름 사전(추가 탐지어는 실명일 수 있어 조회도 설정자만)
                        .requestMatchers("/api/dictionary", "/api/dictionary/**").hasRole(CONFIGURER)
                        // 감사·계정
                        .requestMatchers(HttpMethod.GET, "/api/audit").hasAnyRole(AUDITOR, ADMIN)
                        .requestMatchers("/api/users", "/api/users/**").hasRole(ADMIN)
                        // 표에 없는 API 는 막는다. 나머지는 화면 정적 파일
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().permitAll())
                .formLogin(f -> f
                        .loginPage("/") // 화면(SPA)이 로그인 폼을 그린다. 기본 로그인 페이지 생성 안 함
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler((req, res, auth) -> {
                            accounts.loginSucceeded(auth.getName());
                            req.getSession().setAttribute(AccountRefreshFilter.LOGIN_AT, java.time.LocalDateTime.now());
                            res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        })
                        .failureHandler((req, res, ex) -> {
                            boolean locked = ex instanceof LockedException;
                            accounts.loginFailed(req.getParameter("username"), locked ? "LOCKED"
                                    : ex instanceof org.springframework.security.authentication.DisabledException ? "DISABLED"
                                    : "BAD_CREDENTIALS");
                            json(res, HttpServletResponse.SC_UNAUTHORIZED, locked
                                    ? "로그인 시도가 많아 잠시 잠겼습니다. " + AccountService.LOCK_MINUTES + "분 뒤 다시 시도하세요"
                                    : "아이디 또는 비밀번호가 맞지 않습니다");
                        }))
                .logout(l -> l
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> {
                            if (auth != null) {
                                audit.logAs(auth.getName(), "LOGOUT", "user " + auth.getName(), "OK");
                            }
                            res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        }))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                json(res, HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다"))
                        .accessDeniedHandler((req, res, ex) -> json(res, HttpServletResponse.SC_FORBIDDEN,
                                ex instanceof CsrfException ? "보안 토큰이 없거나 만료되었습니다. 새로고침 후 다시 시도하세요"
                                        : "이 기능을 쓸 권한이 없습니다")))
                // 401 마다 요청을 세션에 저장하지 않는다(SPA 라 로그인 후 원래 요청으로 돌려보낼 일이 없다)
                .requestCache(c -> c.requestCache(new org.springframework.security.web.savedrequest.NullRequestCache()))
                .addFilterBefore(new AccountRefreshFilter(users), AuthorizationFilter.class);
        return http.build();
    }

    /** 오류 응답은 기존 API 와 같은 {"error": "..."} 형식. 메시지는 이 클래스의 고정 문구뿐이라 이스케이프가 필요 없다 */
    static void json(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
