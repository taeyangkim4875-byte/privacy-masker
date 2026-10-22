package com.piimask.app.security;

import com.piimask.app.support.ApiException;
import com.piimask.app.support.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 최초 관리자 만들기.
 * 1) PIIMASK_ADMIN_USER/PASSWORD 가 있으면 첫 기동에서 그 값으로 만든다(무인 설치).
 * 2) 없으면 data-dir/setup.token 에 일회용 토큰을 쓰고, 화면에서 토큰을 입력해 만든다.
 * 토큰·비밀번호 값은 로그에 남기지 않는다(파일 위치만 안내). 첫 계정이 생기면 토큰 파일을 지운다.
 */
@Service
@Order(0)
public class SetupService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final AccountService accounts;
    private final Path tokenFile;
    private final String envUser;
    private final String envPassword;

    public SetupService(AccountService accounts, @Value("${piimask.data-dir}") String dataDir,
                        @Value("${piimask.admin.username:}") String envUser,
                        @Value("${piimask.admin.password:}") String envPassword) {
        this.accounts = accounts;
        this.tokenFile = Path.of(dataDir).toAbsolutePath().normalize().resolve("setup.token");
        this.envUser = envUser;
        this.envPassword = envPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!accounts.setupRequired()) {
            deleteToken();
            // 관리자가 모두 사용 중지됐으면 환경변수로만 복구할 수 있다
            if (accounts.noEnabledAdmin()) {
                if (!envUser.isBlank() && !envPassword.isEmpty()) {
                    try {
                        accounts.recoverAdmin(envUser, envPassword);
                        log.warn("사용 중인 관리자가 없어 환경변수 계정으로 복구했습니다: {}", envUser.trim());
                    } catch (ApiException e) {
                        log.warn("관리자 복구 실패({})", e.getMessage());
                    }
                } else {
                    log.warn("사용 중인 관리자가 없습니다. PIIMASK_ADMIN_USER/PIIMASK_ADMIN_PASSWORD 를 지정하고 다시 기동하면 복구됩니다");
                }
            }
            return;
        }
        if (!envUser.isBlank() && !envPassword.isEmpty()) {
            try {
                accounts.createInitialAdmin(envUser, envPassword, null);
                log.info("환경변수로 최초 관리자 계정을 만들었습니다: {}", envUser.trim());
                deleteToken();
                return;
            } catch (ApiException e) {
                log.warn("환경변수 관리자 생성 실패({}). 설정 토큰 방식으로 진행합니다", e.getMessage());
            }
        }
        try {
            if (!Files.exists(tokenFile)) {
                byte[] b = new byte[24];
                new SecureRandom().nextBytes(b);
                Files.createDirectories(tokenFile.getParent());
                Files.writeString(tokenFile, HexFormat.of().formatHex(b), StandardCharsets.UTF_8);
            }
            log.warn("계정이 없습니다. 화면에서 초기 관리자를 만드세요. 설정 토큰 파일: {}", tokenFile);
        } catch (IOException e) {
            log.error("설정 토큰 파일을 만들지 못했습니다: {}", Errors.safeMessage(e));
        }
    }

    public synchronized void setup(String token, String username, String password, String displayName) {
        if (!accounts.setupRequired()) {
            throw ApiException.conflict("이미 계정이 있습니다. 초기 설정은 한 번만 할 수 있습니다");
        }
        if (token == null || !matchesToken(token.trim())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "설정 토큰이 맞지 않습니다");
        }
        accounts.createInitialAdmin(username, password, displayName);
        deleteToken();
    }

    private boolean matchesToken(String given) {
        try {
            if (!Files.exists(tokenFile)) {
                return false;
            }
            byte[] expected = Files.readString(tokenFile, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
            return expected.length > 0 && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteToken() {
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            log.warn("설정 토큰 파일을 지우지 못했습니다: {}", tokenFile);
        }
    }
}
