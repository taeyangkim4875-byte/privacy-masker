package com.piimask.app.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * DB 접속 비밀번호 암호화(AES-256-GCM).
 * 마스터키: 환경변수 PIIMASK_MASTER_KEY(Base64 32바이트) → 없으면 data-dir/master.key 파일(없으면 생성).
 * 운영 배포 시에는 환경변수로 주입하고 키파일은 쓰지 않는다(4단계).
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${piimask.data-dir}") String dataDir) throws IOException {
        this.key = new SecretKeySpec(loadKey(Path.of(dataDir)), "AES");
    }

    private byte[] loadKey(Path dataDir) throws IOException {
        String env = System.getenv("PIIMASK_MASTER_KEY");
        if (env != null && !env.isBlank()) {
            byte[] k = Base64.getDecoder().decode(env.trim());
            if (k.length != 32) {
                throw new IllegalStateException("PIIMASK_MASTER_KEY 는 Base64 로 인코딩한 32바이트여야 합니다");
            }
            return k;
        }
        Path file = dataDir.resolve("master.key");
        if (Files.exists(file)) {
            return Base64.getDecoder().decode(Files.readString(file).trim());
        }
        Files.createDirectories(dataDir);
        byte[] k = new byte[32];
        random.nextBytes(k);
        Files.writeString(file, Base64.getEncoder().encodeToString(k));
        log.warn("마스터키 파일을 새로 만들었습니다: {} (로컬 테스트용. 운영에서는 PIIMASK_MASTER_KEY 사용)", file.toAbsolutePath());
        return k;
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    public String decrypt(String enc) {
        if (enc == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("복호화 실패: 마스터키가 바뀌었는지 확인하세요", e);
        }
    }
}
