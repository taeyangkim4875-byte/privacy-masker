package com.piimask.app.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 최초 관리자: 설정 토큰 파일로 한 번만 만들 수 있다 */
@SpringBootTest(properties = "piimask.data-dir=./build/test-data/setup")
@AutoConfigureMockMvc
class SetupTest {

    private static final Path TOKEN = Path.of("build/test-data/setup/setup.token");

    @Autowired
    MockMvc mvc;

    private static String body(String token) {
        return "{\"token\":\"" + token + "\",\"username\":\"first-admin\",\"password\":\"first-admin-pass\"}";
    }

    @Test
    void setup_token_creates_the_first_admin_exactly_once() throws Exception {
        assertTrue(Files.exists(TOKEN), "계정이 없으면 기동 시 토큰 파일을 만든다");
        mvc.perform(get("/api/auth/setup-status")).andExpect(jsonPath("$.setupRequired").value(true));

        mvc.perform(post("/api/auth/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("wrong-token")))
                .andExpect(status().isForbidden());

        String token = Files.readString(TOKEN).trim();
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isOk());
        assertFalse(Files.exists(TOKEN), "설정 후 토큰 파일 삭제");
        mvc.perform(get("/api/auth/setup-status")).andExpect(jsonPath("$.setupRequired").value(false));

        mvc.perform(post("/api/auth/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isConflict());

        mvc.perform(formLogin("/api/auth/login").user("first-admin").password("first-admin-pass"))
                .andExpect(status().isNoContent());
    }
}
