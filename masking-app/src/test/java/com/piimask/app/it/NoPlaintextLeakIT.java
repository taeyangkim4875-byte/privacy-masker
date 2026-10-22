package com.piimask.app.it;

import com.piimask.app.batch.ExecutionView;
import com.piimask.core.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 4) 애플리케이션 로그·실행 이력·메타 DB 어디에도 원문(마스킹 전 값)이 남지 않는다.
 * 실행마다 새로 만든 가짜 개인정보(카나리)와, 원천 전체에서 엔진이 탐지한 원문 조각을 찾는다.
 * 발견 시 값은 출력하지 않고 위치·유형만 보고한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "piimask.data-dir=./build/it-data/leak",
        "logging.file.name=./build/it-data/leak/app.log",
        "logging.level.com.piimask=DEBUG"})
class NoPlaintextLeakIT extends BatchItSupport {

    private static final String SRC = "it_src_leak";
    private static final String TGT = "it_tgt_leak";
    private static final String TGT_FAIL = "it_tgt_leak_fail";
    private static final Path DATA_DIR = Path.of("build/it-data/leak");
    private static final int MIN_SECRET_LEN = 3;

    @Autowired
    JdbcTemplate meta;

    @AfterEach
    void cleanup() {
        db.dropUnlessKept(SRC, TGT, TGT_FAIL);
    }

    @Test
    void no_original_text_in_logs_or_history(CapturedOutput output) throws IOException {
        db.drop(TGT);
        db.drop(TGT_FAIL);
        db.createSource(SRC, 1);

        // 카나리: 이번 실행에서만 존재하는 가짜 값. 엔진이 실제로 잡는지 먼저 확인한다
        FakePii fake = new FakePii();
        List<String> canaries = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String phone = fake.phone();
            String card = fake.card();
            String rrn = fake.rrn();
            String email = fake.email();
            rows.add("연락처는 " + phone + "이요.");
            rows.add("카드번호 " + card + " 입니다.");
            rows.add("주민번호는 " + rrn + "입니다.");
            rows.add("이메일은 " + email + " 으로 보내주세요.");
            canaries.addAll(List.of(phone, card, rrn, email));
        }
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            String canary = canaries.get(i);
            assertTrue(engine.detect(row).stream().anyMatch(s -> row.substring(s.start(), s.end()).equals(canary)),
                    "카나리 " + i + "번을 엔진이 탐지하지 못함(테스트 전제 실패)");
        }
        db.insertRows(SRC, rows, "IT-CANARY");

        long connId = registerConnection("it-leak");

        // 정상 실행
        long okJob = createJob("it-leak", connId, SRC, TGT, 300);
        ExecutionView ok = runToEnd(okJob);
        assertEquals("SUCCESS", ok.status(), () -> "실행 실패: " + ok.errorMessage());
        for (int i = 0; i < canaries.size(); i++) {
            assertEquals(0, db.scalar("SELECT COUNT(*) FROM " + ItDb.t(TGT) + " WHERE LOCATE(?, utterance) > 0", canaries.get(i)),
                    "대상 테이블에 카나리 " + i + "번이 마스킹되지 않고 남음");
        }

        // 실패 실행: 마스킹 컬럼이 너무 짧은 대상 테이블 → Data too long → error_message 가 채워진다
        assertTrue(db.scalar("SELECT LOCATE('STRICT_TRANS_TABLES', @@GLOBAL.sql_mode)") > 0,
                "실패 경로 재현에 STRICT_TRANS_TABLES 가 필요합니다");
        db.exec("CREATE TABLE " + ItDb.t(TGT_FAIL) + " (id BIGINT NOT NULL PRIMARY KEY, call_id VARCHAR(32), seq INT, "
                + "speaker VARCHAR(10), utterance VARCHAR(5), created_at DATETIME, "
                + "masked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) DEFAULT CHARSET=utf8mb4");
        long failJob = createJob("it-leak-fail", connId, SRC, TGT_FAIL, 300);
        ExecutionView failed = runToEnd(failJob);
        assertEquals("FAILED", failed.status(), "짧은 컬럼으로 실패해야 함");
        assertNotNull(failed.errorMessage(), "실패 이력에 오류 메시지가 있어야 함");

        // 찾을 값: 카나리 + 원천 전체에서 탐지된 원문 조각
        Map<String, String> secrets = new LinkedHashMap<>();
        for (int i = 0; i < canaries.size(); i++) {
            secrets.put(canaries.get(i), "canary#" + i);
        }
        for (String text : db.texts(SRC)) {
            for (Span s : engine.detect(text)) {
                String v = text.substring(s.start(), s.end());
                if (v.length() >= MIN_SECRET_LEN) {
                    secrets.putIfAbsent(v, s.type().name());
                }
            }
        }
        assertTrue(secrets.size() > canaries.size(), "원천에서 탐지된 원문 조각이 있어야 함");

        // 검사 대상: 콘솔 로그, 로그 파일, 메타 DB 논리 덤프(SCRIPT), 메타 DB 물리 파일(BACKUP 사본)
        Map<String, String> corpora = new LinkedHashMap<>();
        corpora.put("console", output.getAll());
        Path logFile = DATA_DIR.resolve("app.log");
        corpora.put("log-file", Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "");
        corpora.put("meta-script", String.join("\n", meta.queryForList("SCRIPT", String.class)));
        corpora.put("meta-file", metaFileBytes());

        assertFalse(corpora.get("log-file").isEmpty(), "로그 파일이 비어 있음(설정 확인)");
        assertTrue(corpora.get("meta-script").contains("it-leak-fail"), "메타 DB 덤프에 작업 이력이 없음(검사 무효)");

        Set<String> leaks = new LinkedHashSet<>();
        for (Map.Entry<String, String> c : corpora.entrySet()) {
            for (Map.Entry<String, String> s : secrets.entrySet()) {
                if (c.getValue().contains(s.getKey())) {
                    leaks.add(c.getKey() + ":" + s.getValue());
                }
            }
        }
        assertTrue(leaks.isEmpty(), "원문 유출(위치:유형) " + leaks.size() + "건: " + leaks
                + " / 검사한 원문 조각 " + secrets.size() + "개");
    }

    /** H2 BACKUP 으로 메타 DB 파일 사본을 만들고 바이트를 문자열로(UTF-8 해석) 읽는다. 지워진 행의 잔여도 포함 */
    private String metaFileBytes() throws IOException {
        Path zip = DATA_DIR.resolve("meta-backup.zip").toAbsolutePath();
        Files.deleteIfExists(zip);
        meta.execute("BACKUP TO '" + zip.toString().replace("'", "''") + "'");
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            while (z.getNextEntry() != null) {
                sb.append(new String(z.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        assertTrue(sb.length() > 0, "메타 DB 백업이 비어 있음");
        return sb.toString();
    }
}
