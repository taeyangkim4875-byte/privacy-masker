package com.piimask.app.security;

import com.piimask.app.security.AccountService.PasswordRequest;
import com.piimask.app.security.AccountService.UserView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 로그인 사용자 정보·본인 비밀번호·최초 설정. 로그인·로그아웃 자체는 SecurityConfig 의 필터가 처리한다 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SetupRequest(String token, String username, String password, String displayName) {
    }

    private final AccountService accounts;
    private final SetupService setup;

    public AuthController(AccountService accounts, SetupService setup) {
        this.accounts = accounts;
        this.setup = setup;
    }

    @GetMapping("/me")
    public UserView me() {
        return accounts.current(CurrentUser.name());
    }

    @PostMapping("/password")
    public void changePassword(@RequestBody PasswordRequest r, jakarta.servlet.http.HttpSession session) {
        accounts.changeOwnPassword(CurrentUser.name(), r);
        // 본인이 바꾼 경우 지금 세션은 유지(다른 세션만 끊긴다)
        session.setAttribute(AccountRefreshFilter.LOGIN_AT, java.time.LocalDateTime.now());
    }

    @GetMapping("/setup-status")
    public Map<String, Boolean> setupStatus() {
        return Map.of("setupRequired", accounts.setupRequired());
    }

    @PostMapping("/setup")
    public void setup(@RequestBody SetupRequest r) {
        if (r == null) {
            throw com.piimask.app.support.ApiException.badRequest("요청 본문이 없습니다");
        }
        setup.setup(r.token(), r.username(), r.password(), r.displayName());
    }
}
