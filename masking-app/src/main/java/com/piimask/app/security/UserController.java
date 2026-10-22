package com.piimask.app.security;

import com.piimask.app.security.AccountService.UserRequest;
import com.piimask.app.security.AccountService.UserView;
import com.piimask.app.support.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 사용자 관리(관리자). 삭제 대신 사용 중지만 둔다(감사 로그의 사용자 이름이 계속 의미를 갖도록) */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AccountService accounts;

    public UserController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<UserView> list() {
        return accounts.list();
    }

    @GetMapping("/roles")
    public List<Map<String, String>> roles() {
        return Arrays.stream(Role.values()).map(r -> Map.of("code", r.name(), "label", r.label())).toList();
    }

    @PostMapping
    public UserView create(@RequestBody UserRequest r) {
        if (r == null) {
            throw ApiException.badRequest("요청 본문이 없습니다");
        }
        return accounts.create(r);
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable long id, @RequestBody UserRequest r) {
        if (r == null) {
            throw ApiException.badRequest("요청 본문이 없습니다");
        }
        return accounts.update(id, r);
    }

    @PostMapping("/{id}/password")
    public void resetPassword(@PathVariable long id, @RequestBody Map<String, String> body) {
        accounts.resetPassword(id, body == null ? null : body.get("password"));
    }
}
