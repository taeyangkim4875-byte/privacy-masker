package com.piimask.app.security;

import com.piimask.app.security.AccountService.UserRequest;
import com.piimask.app.security.AccountService.UserView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로그인·잠금·로그아웃·CSRF·비밀번호 저장·감사 사용자·계정 변경 반영 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "piimask.data-dir=./build/test-data/authflow")
@AutoConfigureMockMvc
class AuthFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    AccountService accounts;
    @Autowired
    UserRepository users;
    @Autowired
    JdbcTemplate meta;

    private String newUser(String prefix, String password, String... roles) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(new UserRequest(name, null, password, List.of(roles), true));
        return name;
    }

    private MockHttpSession login(String user, String password) throws Exception {
        MvcResult r = mvc.perform(formLogin("/api/auth/login").user(user).password(password))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String lastAuditActor(String action) {
        return meta.queryForObject("SELECT actor FROM audit_log WHERE action = ? ORDER BY id DESC LIMIT 1", String.class, action);
    }

    @Test
    void login_me_logout() throws Exception {
        String user = newUser("op", "operator-pass-1", "OPERATOR", "AUDITOR");
        MockHttpSession s = login(user, "operator-pass-1");
        mvc.perform(get("/api/auth/me").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(user))
                .andExpect(jsonPath("$.roles[0]").value("OPERATOR"));
        assertEquals(user, lastAuditActor("LOGIN"));

        mvc.perform(post("/api/auth/logout").session(s).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isUnauthorized());
        assertEquals(user, lastAuditActor("LOGOUT"));
    }

    @Test
    void wrong_password_five_times_locks_the_account() throws Exception {
        String user = newUser("lock", "correct-pass-1", "AUDITOR");
        for (int i = 0; i < AccountService.MAX_FAILURES; i++) {
            mvc.perform(formLogin("/api/auth/login").user(user).password("wrong-pass-" + i))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("아이디 또는 비밀번호가 맞지 않습니다"));
        }
        mvc.perform(formLogin("/api/auth/login").user(user).password("correct-pass-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("잠시 잠겼습니다")));
        assertEquals("LOCKED", meta.queryForObject(
                "SELECT outcome FROM audit_log WHERE action='LOGIN' AND target = ? AND outcome='LOCKED' LIMIT 1",
                String.class, "user " + user));

        // 관리자가 비밀번호를 재설정하면 잠금도 풀린다
        long id = users.findByUsername(user).orElseThrow().id();
        accounts.resetPassword(id, "brand-new-pass-1");
        login(user, "brand-new-pass-1");
    }

    @Test
    void unknown_user_gets_the_same_message_and_input_is_not_audited() throws Exception {
        // 아이디 칸에 비밀번호를 잘못 넣은 경우를 흉내 낸다
        String typo = "pw-in-user-field-" + UUID.randomUUID();
        mvc.perform(formLogin("/api/auth/login").user(typo).password("whatever-pass"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("아이디 또는 비밀번호가 맞지 않습니다"));
        String dump = String.join("\n", meta.queryForList("SCRIPT", String.class));
        assertFalse(dump.contains(typo), "없는 계정의 입력값이 감사 로그에 남음");
        assertEquals(AccountService.UNKNOWN_ACTOR, lastAuditActor("LOGIN"));
    }

    /** 리뷰 H1: 아이디에 공백을 붙여도 같은 계정의 실패로 센다(잠금 우회 방지) */
    @Test
    void padded_username_still_counts_toward_lockout() throws Exception {
        String user = newUser("pad", "correct-pass-1", "AUDITOR");
        for (int i = 0; i < AccountService.MAX_FAILURES; i++) {
            mvc.perform(formLogin("/api/auth/login").user((i % 2 == 0 ? " " : "") + user + "\t").password("wrong-" + i))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(formLogin("/api/auth/login").user(user).password("correct-pass-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("잠시 잠겼습니다")));
    }

    /** 리뷰 M3: 관리자가 비밀번호를 재설정하면 기존 세션이 끊기고, 본인이 바꾸면 지금 세션은 유지 */
    @Test
    void password_reset_ends_existing_sessions_but_own_change_keeps_current() throws Exception {
        String user = newUser("pwreset", "first-pass-001", "AUDITOR");
        MockHttpSession a = login(user, "first-pass-001");
        MockHttpSession b = login(user, "first-pass-001");

        mvc.perform(post("/api/auth/password").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"first-pass-001\",\"newPassword\":\"second-pass-02\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(a)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(b)).andExpect(status().isUnauthorized());

        accounts.resetPassword(users.findByUsername(user).orElseThrow().id(), "third-pass-003");
        mvc.perform(get("/api/auth/me").session(a)).andExpect(status().isUnauthorized());
        login(user, "third-pass-003");
    }

    /** 관리자는 본인 계정에서 관리자 역할을 빼거나 사용 중지할 수 없다. 다른 역할 추가는 된다 */
    @Test
    void admin_cannot_drop_own_admin_role_or_disable_self() throws Exception {
        accounts.create(new UserRequest("other-admin-" + UUID.randomUUID().toString().substring(0, 6), null,
                "other-admin-pass", List.of("ADMIN"), true));
        String admin = newUser("selfadm", "self-admin-pass", "ADMIN");
        long id = users.findByUsername(admin).orElseThrow().id();
        MockHttpSession s = login(admin, "self-admin-pass");

        for (String body : List.of("{\"roles\":[\"AUDITOR\"],\"enabled\":true}", "{\"roles\":[\"ADMIN\"],\"enabled\":false}")) {
            mvc.perform(put("/api/users/" + id).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("본인 계정")));
        }
        mvc.perform(put("/api/users/" + id).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ADMIN\",\"AUDITOR\"],\"enabled\":true}"))
                .andExpect(status().isOk());
        assertTrue(users.findByUsername(admin).orElseThrow().roles().contains(Role.ADMIN));
    }

    @Test
    void reserved_usernames_are_rejected() {
        for (String name : List.of("system", "system:scheduler", "SYSTEM-x", "unknown")) {
            var e = org.junit.jupiter.api.Assertions.assertThrows(com.piimask.app.support.ApiException.class,
                    () -> accounts.create(new UserRequest(name, null, "reserved-pass-1", List.of("AUDITOR"), true)));
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, e.status(), name);
        }
    }

    @Test
    void write_without_csrf_token_is_rejected() throws Exception {
        String user = newUser("cfg", "configurer-pass-1", "CONFIGURER");
        MockHttpSession s = login(user, "configurer-pass-1");
        mvc.perform(post("/api/connections").session(s).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("보안 토큰")));
    }

    @Test
    void audit_log_records_the_logged_in_user() throws Exception {
        String user = newUser("cfg", "configurer-pass-1", "CONFIGURER");
        MockHttpSession s = login(user, "configurer-pass-1");
        mvc.perform(post("/api/connections").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"host\":\"127.0.0.1\",\"port\":3306,\"database\":\"x\",\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isOk());
        assertEquals(user, lastAuditActor("CONNECTION_CREATE"));
    }

    @Test
    void passwords_are_stored_only_as_bcrypt_and_never_logged(CapturedOutput output) throws Exception {
        String secret = "plain-" + UUID.randomUUID();
        String user = newUser("hash", secret, "AUDITOR");
        String wrongCanary = "canary-" + UUID.randomUUID();
        mvc.perform(formLogin("/api/auth/login").user(user).password(wrongCanary)).andExpect(status().isUnauthorized());
        login(user, secret);

        String hash = users.findByUsername(user).orElseThrow().passwordHash();
        assertTrue(hash.startsWith("{bcrypt}$2"), "bcrypt 해시여야 함");
        String dump = String.join("\n", meta.queryForList("SCRIPT", String.class));
        assertFalse(dump.contains(secret) || dump.contains(wrongCanary), "메타 DB 에 비밀번호 평문이 있음");
        assertFalse(output.getAll().contains(secret) || output.getAll().contains(wrongCanary), "로그에 비밀번호가 있음");
    }

    @Test
    void disabling_or_changing_roles_applies_to_live_sessions() throws Exception {
        accounts.create(new UserRequest("keeper-admin-" + UUID.randomUUID().toString().substring(0, 6), null,
                "keeper-pass-01", List.of("ADMIN"), true));
        String user = newUser("live", "live-pass-0001", "AUDITOR");
        MockHttpSession s = login(user, "live-pass-0001");
        mvc.perform(get("/api/audit").session(s)).andExpect(status().isOk());

        long id = users.findByUsername(user).orElseThrow().id();
        accounts.update(id, new UserRequest(null, null, null, List.of("OPERATOR"), true));
        mvc.perform(get("/api/audit").session(s)).andExpect(status().isForbidden());

        accounts.update(id, new UserRequest(null, null, null, List.of("OPERATOR"), false));
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isUnauthorized());
        mvc.perform(formLogin("/api/auth/login").user(user).password("live-pass-0001")).andExpect(status().isUnauthorized());
    }

    @Test
    void last_enabled_admin_cannot_be_removed() {
        meta.update("UPDATE app_user SET enabled = FALSE WHERE roles LIKE '%ADMIN%'");
        String admin = newUser("solo", "solo-admin-pass", "ADMIN");
        long id = users.findByUsername(admin).orElseThrow().id();
        var e = org.junit.jupiter.api.Assertions.assertThrows(com.piimask.app.support.ApiException.class,
                () -> accounts.update(id, new UserRequest(null, null, null, List.of("AUDITOR"), true)));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.status());
        UserView v = accounts.list().stream().filter(u -> u.username().equals(admin)).findFirst().orElseThrow();
        assertTrue(v.roles().contains("ADMIN"));
    }
}
