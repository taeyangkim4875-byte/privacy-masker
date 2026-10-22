package com.piimask.app.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 무인 설치: PIIMASK_ADMIN_USER/PASSWORD 로 첫 기동 때 관리자 생성, 토큰 파일 없음 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "piimask.data-dir=./build/test-data/envadmin",
        "piimask.admin.username=env-admin",
        "piimask.admin.password=env-admin-pass-1"})
class EnvAdminSetupTest {

    @Autowired
    UserRepository users;
    @Autowired
    SetupService setup;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate meta;

    @Test
    void env_vars_create_the_first_admin() {
        var u = users.findByUsername("env-admin").orElseThrow();
        assertTrue(u.roles().contains(Role.ADMIN) && u.roles().size() == 1, "관리자 역할만");
        assertTrue(u.passwordHash().startsWith("{bcrypt}"));
        assertFalse(Files.exists(Path.of("build/test-data/envadmin/setup.token")));

        // 리뷰 M4: 사용 중인 관리자가 없으면 다음 기동 때 환경변수 계정으로 복구(같은 계정 상태를 이어서 쓰므로 한 테스트에서)
        meta.update("UPDATE app_user SET enabled = FALSE, roles = 'AUDITOR' WHERE username = 'env-admin'");
        setup.run(null); // 기동 시와 같은 경로
        var r = users.findByUsername("env-admin").orElseThrow();
        assertTrue(r.enabled() && r.roles().contains(Role.ADMIN) && r.roles().contains(Role.AUDITOR), "관리자 복구, 기존 역할 유지");
    }
}
