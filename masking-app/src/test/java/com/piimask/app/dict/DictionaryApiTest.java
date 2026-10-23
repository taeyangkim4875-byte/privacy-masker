package com.piimask.app.dict;

import com.piimask.app.security.AccountService;
import com.piimask.app.security.Role;
import com.piimask.app.security.UserRepository;
import com.piimask.core.EntityType;
import com.piimask.core.MaskingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사용자 이름 사전: 탐지 반영, 중복·오류 처리, 감사 로그·오류 메시지에 단어 미기록, 실행 중 엔진 고정 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "piimask.data-dir=./build/test-data/dictionary")
@AutoConfigureMockMvc
class DictionaryApiTest {

    /** 테스트용 가짜 단어 */
    private static final String FORCED = "홍길순";
    private static final String EXCEPTED = "구독권";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    JdbcTemplate meta;
    @Autowired
    EngineProvider engines;

    @BeforeEach
    void setUp() {
        if (users.findByUsername("dict-conf").isEmpty()) {
            users.insert("dict-conf", "{noop}unused", "dict-conf", EnumSet.of(Role.CONFIGURER), AccountService.LOCAL);
        }
        meta.update("DELETE FROM name_dictionary");
        engines.reload();
    }

    private static RequestPostProcessor conf() {
        return user("dict-conf").roles(Role.CONFIGURER.name());
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).with(conf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String detect(String text) throws Exception {
        return mvc.perform(json(post("/api/detect"), "{\"text\":\"" + text + "\",\"policies\":{\"NAME\":\"TAG\"}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void add(String kind, String... terms) throws Exception {
        String list = String.join(",", java.util.Arrays.stream(terms).map(t -> "\"" + t + "\"").toList());
        mvc.perform(json(post("/api/dictionary"), "{\"kind\":\"" + kind + "\",\"terms\":[" + list + "]}"))
                .andExpect(status().isOk());
    }

    private long idOf(String term) {
        return meta.queryForObject("SELECT id FROM name_dictionary WHERE term = ?", Long.class, term);
    }

    @Test
    void exception_word_is_applied_immediately_and_removed_on_delete() throws Exception {
        assertTrue(detect(EXCEPTED + "입니다.").contains("[이름]"), "사전 추가 전에는 규칙에 걸린다");
        add("EXCEPT", EXCEPTED);
        assertFalse(detect(EXCEPTED + "입니다.").contains("[이름]"));
        // 같은 문장의 진짜 이름은 그대로
        assertTrue(detect("구민서 고객님 " + EXCEPTED + "입니다.").contains("[이름] 고객님"));

        mvc.perform(delete("/api/dictionary/" + idOf(EXCEPTED)).with(csrf()).with(conf())).andExpect(status().isOk());
        assertTrue(detect(EXCEPTED + "입니다.").contains("[이름]"));
    }

    @Test
    void forced_term_detects_without_context() throws Exception {
        assertFalse(detect("어제 " + FORCED + "한테 들었어요").contains("[이름]"));
        add("FORCE", FORCED);
        assertTrue(detect("어제 " + FORCED + "한테 들었어요").contains("어제 [이름]한테"));
    }

    @Test
    void duplicates_are_skipped_and_blank_lines_ignored() throws Exception {
        add("FORCE", FORCED);
        mvc.perform(json(post("/api/dictionary"), "{\"kind\":\"FORCE\",\"terms\":[\"" + FORCED + "\",\"  " + FORCED + " \",\"\",\"김철수\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.skipped").value(2));
        // 같은 단어라도 종류가 다르면 따로 둔다
        add("EXCEPT", FORCED);
        assertEquals(3, meta.queryForObject("SELECT COUNT(*) FROM name_dictionary", Integer.class));
    }

    @Test
    void invalid_lines_are_reported_by_line_number_without_the_word() throws Exception {
        String body = mvc.perform(json(post("/api/dictionary"), "{\"kind\":\"EXCEPT\",\"terms\":[\"정상단어\",\"*비밀점\",\"한\"]}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("2번째 줄") && body.contains("3번째 줄"), "줄 번호로 알린다");
        assertFalse(body.contains("비밀점") || body.contains("정상단어"), "오류 응답에 단어가 들어가면 안 됨");
        // 하나라도 틀리면 아무것도 넣지 않는다
        assertEquals(0, meta.queryForObject("SELECT COUNT(*) FROM name_dictionary", Integer.class));
        mvc.perform(json(post("/api/dictionary"), "{\"kind\":\"OTHER\",\"terms\":[\"홍길동\"]}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/dictionary"), "{\"kind\":\"FORCE\",\"terms\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/dictionary/987654").with(csrf()).with(conf())).andExpect(status().isNotFound());
    }

    @Test
    void audit_log_and_meta_do_not_contain_words() throws Exception {
        add("FORCE", FORCED);
        add("EXCEPT", EXCEPTED);
        mvc.perform(delete("/api/dictionary/" + idOf(FORCED)).with(csrf()).with(conf())).andExpect(status().isOk());
        List<String> targets = meta.queryForList(
                "SELECT target FROM audit_log WHERE action IN ('DICT_ADD', 'DICT_DELETE') ORDER BY id DESC LIMIT 3", String.class);
        assertEquals(3, targets.size());
        assertTrue(targets.stream().noneMatch(t -> t.contains(FORCED) || t.contains(EXCEPTED)), "감사 로그에 단어가 있음");
        assertEquals("dict-conf", meta.queryForObject(
                "SELECT actor FROM audit_log WHERE action = 'DICT_DELETE' ORDER BY id DESC LIMIT 1", String.class));

        add("FORCE", FORCED);
        String m = mvc.perform(get("/api/meta").with(conf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(m.contains("사용자 추가 탐지어 1개"));
        assertFalse(m.contains(FORCED), "모든 역할이 보는 /api/meta 에 단어가 있음");

        String list = mvc.perform(get("/api/dictionary").with(conf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(list.contains(FORCED) && list.contains("\"builtIn\""));
    }

    @Test
    void request_size_is_limited() throws Exception {
        String many = String.join(",", java.util.Collections.nCopies(DictionaryService.MAX_PER_REQUEST + 1, "\"가나다\""));
        mvc.perform(json(post("/api/dictionary"), "{\"kind\":\"EXCEPT\",\"terms\":[" + many + "]}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, meta.queryForObject("SELECT COUNT(*) FROM name_dictionary", Integer.class));
    }

    /** 추가·삭제·오류 처리 중 애플리케이션 로그(콘솔)에 단어가 남지 않는다 */
    @Test
    void words_never_reach_application_logs(CapturedOutput out) throws Exception {
        add("FORCE", FORCED);
        add("FORCE", FORCED);
        mvc.perform(json(post("/api/dictionary"), "{\"kind\":\"FORCE\",\"terms\":[\"" + FORCED + "\",\"*" + FORCED + "\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/dictionary"), "{\"kind\":\"BAD\",\"terms\":[\"" + FORCED + "\"]}"))
                .andExpect(status().isBadRequest());
        detect("어제 " + FORCED + "한테 들었어요");
        mvc.perform(delete("/api/dictionary/" + idOf(FORCED)).with(csrf()).with(conf())).andExpect(status().isOk());
        assertFalse(out.getAll().contains(FORCED), "로그에 사전 단어가 있음");
    }

    /** 배치는 실행을 만들 때 받은 엔진을 끝까지 쓴다: 받아 둔 엔진은 사전이 바뀌어도 그대로여야 한다 */
    @Test
    void engine_taken_before_a_change_keeps_its_dictionary() throws Exception {
        MaskingEngine running = engines.current();
        add("FORCE", FORCED);
        String text = "어제 " + FORCED + "한테 들었어요";
        assertTrue(running.detect(text, Set.of(EntityType.NAME)).isEmpty());
        assertEquals(1, engines.current().detect(text, Set.of(EntityType.NAME)).size());
    }
}
