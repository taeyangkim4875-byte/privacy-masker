package com.piimask.app.security;

import com.piimask.app.security.UserRepository.AppUser;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.AuditService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 계정 관리와 로컬 로그인 조회.
 * 역할은 인증 방식과 무관하게 항상 app_user 에서 온다. SAML 을 붙일 때도 같은 계정 행에 연결한다(auth_source=SAML).
 */
@Service
public class AccountService implements UserDetailsService {

    public static final String LOCAL = "LOCAL";
    static final String UNKNOWN_ACTOR = "unknown";
    static final int MIN_PASSWORD = 10;
    static final int MAX_FAILURES = 5;
    static final int LOCK_MINUTES = 10;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@\\-]{3,100}");

    public record UserView(long id, String username, String displayName, List<String> roles, boolean enabled,
                           String authSource, boolean locked, String lastLoginAt, String createdAt) {
    }

    public record UserRequest(String username, String displayName, String password, List<String> roles, Boolean enabled) {
    }

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AccountService(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    // ---------- 로그인 ----------

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser u = users.findByUsername(username)
                .filter(x -> LOCAL.equals(x.authSource()) && x.passwordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("not found"));
        return User.withUsername(u.username())
                .password(u.passwordHash())
                .disabled(!u.enabled())
                .accountLocked(u.lockedAt(LocalDateTime.now()))
                .authorities(authorities(u.roles()))
                .build();
    }

    static List<SimpleGrantedAuthority> authorities(Set<Role> roles) {
        return roles.stream().map(r -> new SimpleGrantedAuthority(r.authority())).toList();
    }

    void loginSucceeded(String username) {
        users.findByUsername(username).ifPresent(u -> users.loginSucceeded(u.id()));
        audit.logAs(username, "LOGIN", "user " + username, "OK");
    }

    /**
     * 실패 기록. 비밀번호는 어디에도 남기지 않는다.
     * 아이디는 Spring 로그인 필터와 같게 trim 한다(공백을 붙여 잠금을 피하지 못하게).
     * 없는 계정이면 입력값을 남기지 않는다(아이디 칸에 비밀번호를 잘못 넣는 경우, actor 위조 방지).
     */
    void loginFailed(String rawUsername, String reason) {
        String name = rawUsername == null ? "" : rawUsername.trim();
        Optional<AppUser> user = name.isEmpty() || name.length() > 100 ? Optional.empty() : users.findByUsername(name);
        if (user.isEmpty()) {
            audit.logAs(UNKNOWN_ACTOR, "LOGIN", "user (없는 계정)", "FAILED");
            return;
        }
        AppUser u = user.get();
        boolean lockedNow = "BAD_CREDENTIALS".equals(reason) && !u.lockedAt(LocalDateTime.now())
                && users.loginFailed(u.id(), MAX_FAILURES, LocalDateTime.now().plusMinutes(LOCK_MINUTES));
        String outcome = lockedNow || "LOCKED".equals(reason) ? "LOCKED" : "DISABLED".equals(reason) ? "DISABLED" : "FAILED";
        audit.logAs(u.username(), "LOGIN", "user " + u.username(), outcome);
    }

    // ---------- 계정 관리(관리자) ----------

    public List<UserView> list() {
        return users.findAll().stream().map(AccountService::view).toList();
    }

    public UserView current(String username) {
        return users.findByUsername(username).map(AccountService::view)
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"));
    }

    @Transactional
    public UserView create(UserRequest r) {
        String username = requireUsername(r.username());
        if (users.findByUsername(username).isPresent()) {
            throw ApiException.conflict("이미 있는 아이디입니다: " + username);
        }
        Set<Role> roles = requireRoles(r.roles());
        requirePassword(r.password());
        users.insert(username, encoder.encode(r.password()), displayName(r.displayName(), username), roles, LOCAL);
        audit.log("USER_CREATE", "user " + username + " " + UserRepository.rolesCsv(roles), "OK");
        return view(users.findByUsername(username).orElseThrow());
    }

    /** 마지막 관리자 검사와 변경 사이에 다른 변경이 끼지 않도록 직렬화한다(단일 인스턴스 전제) */
    @Transactional
    public synchronized UserView update(long id, UserRequest r) {
        AppUser u = get(id);
        Set<Role> roles = requireRoles(r.roles());
        boolean enabled = r.enabled() == null ? u.enabled() : r.enabled();
        // 본인 계정에서는 관리자 역할을 빼거나 사용 중지할 수 없다(관리자가 스스로 사라지는 일 방지)
        if (u.username().equals(CurrentUser.name()) && u.roles().contains(Role.ADMIN)
                && (!enabled || !roles.contains(Role.ADMIN))) {
            throw ApiException.conflict("본인 계정에서는 관리자 역할을 빼거나 사용 중지할 수 없습니다");
        }
        boolean losesAdmin = u.enabled() && u.roles().contains(Role.ADMIN) && (!enabled || !roles.contains(Role.ADMIN));
        if (losesAdmin && users.enabledAdmins() <= 1) {
            throw ApiException.conflict("사용 중인 관리자가 최소 한 명은 있어야 합니다");
        }
        users.update(id, displayName(r.displayName(), u.username()), roles, enabled);
        audit.log(enabled ? "USER_UPDATE" : "USER_DISABLE", "user " + u.username() + " " + UserRepository.rolesCsv(roles), "OK");
        return view(get(id));
    }

    /** 관리자가 다른 계정 비밀번호를 재설정(잠금도 풀린다) */
    @Transactional
    public void resetPassword(long id, String password) {
        AppUser u = get(id);
        requireLocal(u);
        requirePassword(password);
        users.updatePassword(id, encoder.encode(password), LocalDateTime.now());
        audit.log("PASSWORD_RESET", "user " + u.username(), "OK");
    }

    /** 본인 비밀번호 변경 */
    @Transactional
    public void changeOwnPassword(String username, PasswordRequest r) {
        AppUser u = users.findByUsername(username).orElseThrow(() -> ApiException.notFound("계정을 찾을 수 없습니다"));
        requireLocal(u);
        if (r == null || r.currentPassword() == null || !encoder.matches(r.currentPassword(), u.passwordHash())) {
            audit.log("PASSWORD_CHANGE", "user " + username, "FAILED");
            throw ApiException.badRequest("현재 비밀번호가 맞지 않습니다");
        }
        requirePassword(r.newPassword());
        users.updatePassword(u.id(), encoder.encode(r.newPassword()), LocalDateTime.now());
        audit.log("PASSWORD_CHANGE", "user " + username, "OK");
    }

    /** 최초 관리자(설정 토큰 또는 환경변수). 계정이 하나도 없을 때만 */
    @Transactional
    public void createInitialAdmin(String username, String password, String displayName) {
        if (users.count() > 0) {
            throw ApiException.conflict("이미 계정이 있습니다. 초기 설정은 한 번만 할 수 있습니다");
        }
        String name = requireUsername(username);
        requirePassword(password);
        users.insert(name, encoder.encode(password), displayName(displayName, name), EnumSet.of(Role.ADMIN), LOCAL);
        audit.logAs(name, "SETUP_ADMIN", "user " + name, "OK");
    }

    /**
     * 복구: 사용 중인 관리자가 하나도 없을 때(환경변수로만 호출). 계정이 있으면 사용·관리자 역할·비밀번호를 되살리고,
     * 없으면 새로 만든다. 다른 역할은 건드리지 않는다.
     */
    @Transactional
    public synchronized void recoverAdmin(String username, String password) {
        if (users.enabledAdmins() > 0) {
            return;
        }
        requirePassword(password);
        Optional<AppUser> existing = users.findByUsername(username.trim());
        if (existing.isEmpty()) {
            String name = requireUsername(username);
            users.insert(name, encoder.encode(password), name, EnumSet.of(Role.ADMIN), LOCAL);
        } else {
            AppUser u = existing.get();
            requireLocal(u);
            EnumSet<Role> roles = EnumSet.of(Role.ADMIN);
            roles.addAll(u.roles());
            users.update(u.id(), u.displayName(), roles, true);
            users.updatePassword(u.id(), encoder.encode(password), LocalDateTime.now());
        }
        audit.logAs(CurrentUser.SYSTEM, "ADMIN_RECOVERY", "user " + username.trim(), "OK");
    }

    public boolean noEnabledAdmin() {
        return users.enabledAdmins() == 0;
    }

    public boolean setupRequired() {
        return users.count() == 0;
    }

    // ---------- 검증 ----------

    private AppUser get(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("계정을 찾을 수 없습니다: " + id));
    }

    private static void requireLocal(AppUser u) {
        if (!LOCAL.equals(u.authSource())) {
            throw ApiException.badRequest("SSO 계정은 비밀번호를 쓰지 않습니다");
        }
    }

    private static String requireUsername(String username) {
        if (username == null || !USERNAME.matcher(username.trim()).matches()) {
            throw ApiException.badRequest("아이디는 영문·숫자·._@- 3~100자입니다");
        }
        String name = username.trim();
        // 감사 로그에서 시스템 기록·실패 기록과 구분되도록 예약
        String lower = name.toLowerCase();
        if (lower.startsWith(CurrentUser.SYSTEM) || lower.equals(UNKNOWN_ACTOR)) {
            throw ApiException.badRequest("system, unknown 으로 시작하는 아이디는 쓸 수 없습니다");
        }
        return name;
    }

    static void requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw ApiException.badRequest("비밀번호는 " + MIN_PASSWORD + "자 이상이어야 합니다");
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            // bcrypt 는 72바이트까지만 본다(한글은 1자 3바이트)
            throw ApiException.badRequest("비밀번호가 너무 깁니다(72바이트 이하, 영문 기준 72자)");
        }
    }

    private static Set<Role> requireRoles(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("역할을 하나 이상 고르세요");
        }
        EnumSet<Role> out = EnumSet.noneOf(Role.class);
        for (String s : raw) {
            try {
                out.add(Role.valueOf(s));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw ApiException.badRequest("알 수 없는 역할입니다: " + s);
            }
        }
        return out;
    }

    private static String displayName(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        return s.isEmpty() ? fallback : s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static UserView view(AppUser u) {
        return new UserView(u.id(), u.username(), u.displayName(), u.roles().stream().sorted().map(Role::name).toList(),
                u.enabled(), u.authSource(), u.lockedAt(LocalDateTime.now()), u.lastLoginAt(), u.createdAt());
    }
}
