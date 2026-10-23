package com.piimask.app.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 권한 표 전체 검사: 모든 API × (역할 4개 + 비로그인).
 * 허용이면 401/403 이 아니어야 하고(업무 오류 4xx 는 괜찮음), 거부면 비로그인 401 / 로그인 403.
 * 요청 본문은 일부러 비어 있거나 없는 id 라서 부작용이 없다.
 */
@SpringBootTest(properties = "piimask.data-dir=./build/test-data/access")
@AutoConfigureMockMvc
class AccessMatrixTest {

    private static final Set<Role> ALL = EnumSet.allOf(Role.class);
    private static final Set<Role> C = EnumSet.of(Role.CONFIGURER);
    private static final Set<Role> O = EnumSet.of(Role.OPERATOR);
    private static final Set<Role> EXEC_HISTORY = EnumSet.of(Role.CONFIGURER, Role.OPERATOR, Role.AUDITOR);
    private static final Set<Role> AUDIT = EnumSet.of(Role.AUDITOR, Role.ADMIN);
    private static final Set<Role> ADM = EnumSet.of(Role.ADMIN);
    private static final Set<Role> NONE = EnumSet.noneOf(Role.class);

    record Rule(HttpMethod method, String path, Set<Role> allowed) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.GET, "/api/meta", ALL),
            new Rule(HttpMethod.GET, "/api/dashboard", ALL),
            new Rule(HttpMethod.GET, "/api/jobs", ALL),
            new Rule(HttpMethod.GET, "/api/jobs/999", ALL),
            new Rule(HttpMethod.GET, "/api/auth/me", ALL),
            new Rule(HttpMethod.POST, "/api/auth/password", ALL),
            new Rule(HttpMethod.GET, "/api/executions", EXEC_HISTORY),
            new Rule(HttpMethod.GET, "/api/jobs/999/executions", EXEC_HISTORY),
            new Rule(HttpMethod.POST, "/api/jobs/999/run", O),
            new Rule(HttpMethod.POST, "/api/jobs/999/stop", O),
            new Rule(HttpMethod.GET, "/api/connections", C),
            new Rule(HttpMethod.POST, "/api/connections", C),
            new Rule(HttpMethod.PUT, "/api/connections/999", C),
            new Rule(HttpMethod.DELETE, "/api/connections/999", C),
            new Rule(HttpMethod.POST, "/api/connections/test", C),
            new Rule(HttpMethod.POST, "/api/connections/999/test", C),
            new Rule(HttpMethod.GET, "/api/connections/999/tables", C),
            new Rule(HttpMethod.GET, "/api/connections/999/tables/t/columns", C),
            new Rule(HttpMethod.POST, "/api/jobs", C),
            new Rule(HttpMethod.PUT, "/api/jobs/999", C),
            new Rule(HttpMethod.DELETE, "/api/jobs/999", C),
            new Rule(HttpMethod.POST, "/api/jobs/preview", C),
            new Rule(HttpMethod.POST, "/api/jobs/999/preview", C),
            new Rule(HttpMethod.POST, "/api/jobs/ddl", C),
            new Rule(HttpMethod.POST, "/api/jobs/999/reset-watermark", C),
            new Rule(HttpMethod.POST, "/api/detect", C),
            new Rule(HttpMethod.GET, "/api/labels/summary", EnumSet.of(Role.CONFIGURER, Role.AUDITOR)),
            new Rule(HttpMethod.POST, "/api/labels", C),
            new Rule(HttpMethod.POST, "/api/labels/query", C),
            new Rule(HttpMethod.DELETE, "/api/labels/999", C),
            new Rule(HttpMethod.GET, "/api/dictionary", C),
            new Rule(HttpMethod.POST, "/api/dictionary", C),
            new Rule(HttpMethod.DELETE, "/api/dictionary/999", C),
            new Rule(HttpMethod.GET, "/api/audit", AUDIT),
            new Rule(HttpMethod.GET, "/api/users", ADM),
            new Rule(HttpMethod.GET, "/api/users/roles", ADM),
            new Rule(HttpMethod.POST, "/api/users", ADM),
            new Rule(HttpMethod.PUT, "/api/users/999", ADM),
            new Rule(HttpMethod.POST, "/api/users/999/password", ADM),
            new Rule(HttpMethod.GET, "/api/not-in-table", NONE));

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;

    /** 역할마다 실제 계정이 있어야 한다(AccountRefreshFilter 가 매 요청 계정 테이블과 맞춘다) */
    @BeforeEach
    void users() {
        for (Role r : Role.values()) {
            String name = "u-" + r.name().toLowerCase();
            if (users.findByUsername(name).isEmpty()) {
                users.insert(name, "{noop}unused", name, EnumSet.of(r), AccountService.LOCAL);
            }
        }
    }

    private MockHttpServletRequestBuilder req(Rule rule) {
        MockHttpServletRequestBuilder b = request(rule.method(), rule.path()).with(csrf());
        if (rule.method() != HttpMethod.GET && rule.method() != HttpMethod.DELETE) {
            b.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return b;
    }

    @Test
    void every_endpoint_matches_the_role_table() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Rule rule : RULES) {
            int anon = mvc.perform(req(rule)).andReturn().getResponse().getStatus();
            if (anon != 401) {
                wrong.add(rule.method() + " " + rule.path() + " anonymous -> " + anon);
            }
            for (Role role : Role.values()) {
                int s = mvc.perform(req(rule).with(user("u-" + role.name().toLowerCase()).roles(role.name())))
                        .andReturn().getResponse().getStatus();
                boolean allowed = rule.allowed().contains(role);
                if (allowed ? (s == 401 || s == 403) : s != 403) {
                    wrong.add(rule.method() + " " + rule.path() + " " + role + " -> " + s + (allowed ? " (허용이어야 함)" : " (403 이어야 함)"));
                }
            }
        }
        assertTrue(wrong.isEmpty(), "권한 표와 다른 응답:\n" + String.join("\n", wrong));
    }
}
