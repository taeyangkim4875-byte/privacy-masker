package com.piimask.app.dict;

import com.piimask.app.dict.DictionaryRepository.Entry;
import com.piimask.app.dict.DictionaryRepository.Kind;
import com.piimask.app.security.CurrentUser;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.AuditService;
import com.piimask.core.dict.NameDictionary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 이름 사전 관리. 단어는 실명일 수 있으므로 오류 메시지·감사 로그에는 종류·건수·id 만 남긴다.
 * 바뀌면 엔진을 다시 만든다(실행 중인 배치는 시작할 때의 엔진을 그대로 쓴다).
 * 추가·삭제는 synchronized: 상한 검사와 삽입, 삭제와 감사 기록이 서로 끼어들지 않게 한다(한 인스턴스 전제).
 */
@Service
public class DictionaryService {

    static final int MAX_PER_REQUEST = 1000;
    static final int MAX_TOTAL = 10_000;

    public record AddResult(int added, int skipped) {
    }

    private final DictionaryRepository repo;
    private final EngineProvider engines;
    private final AuditService audit;

    public DictionaryService(DictionaryRepository repo, EngineProvider engines, AuditService audit) {
        this.repo = repo;
        this.engines = engines;
        this.audit = audit;
    }

    public Map<String, Object> view() {
        NameDictionary builtIn = NameDictionary.builtIn();
        return Map.of(
                "builtIn", Map.of("surnames", builtIn.surnames().size(), "exceptions", builtIn.exceptions().size()),
                "entries", repo.list());
    }

    public synchronized AddResult add(String kindRaw, List<String> raw) {
        Kind kind = parseKind(kindRaw);
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("추가할 단어가 없습니다");
        }
        if (raw.size() > MAX_PER_REQUEST) {
            throw ApiException.badRequest("한 번에 " + MAX_PER_REQUEST + "줄까지 추가할 수 있습니다");
        }
        // 줄 번호로만 알린다(단어 자체는 메시지에 넣지 않음)
        Set<String> clean = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String line = raw.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                clean.add(NameDictionary.cleanTerm(line));
            } catch (IllegalArgumentException e) {
                if (errors.size() < 5) {
                    errors.add((i + 1) + "번째 줄: " + e.getMessage());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join(" / ", errors));
        }
        if (clean.isEmpty()) {
            throw ApiException.badRequest("추가할 단어가 없습니다");
        }
        Set<String> existing = new HashSet<>(repo.terms(kind));
        List<String> fresh = clean.stream().filter(t -> !existing.contains(t)).toList();
        int skipped = raw.size() - (int) raw.stream().filter(l -> l == null || l.isBlank()).count() - fresh.size();
        if (repo.count() + fresh.size() > MAX_TOTAL) {
            throw ApiException.badRequest("사용자 사전은 " + MAX_TOTAL + "개까지 둘 수 있습니다");
        }
        if (!fresh.isEmpty()) {
            try {
                repo.insertAll(kind, fresh, CurrentUser.name());
            } catch (DuplicateKeyException e) {
                // 동시에 같은 단어를 넣은 경우. 예외 메시지에 단어가 들어 있으므로 그대로 내보내지 않는다
                throw ApiException.conflict("이미 등록된 단어가 있습니다. 목록을 새로고침한 뒤 다시 시도하세요");
            } catch (DataAccessException e) {
                // 무결성 오류 메시지에 입력값이 들어갈 수 있으므로 원인을 응답·로그에 넘기지 않는다
                throw ApiException.badRequest("사전에 저장하지 못했습니다");
            }
            engines.reload();
        }
        audit.log("DICT_ADD", "dictionary " + kind + " +" + fresh.size() + " (skip " + skipped + ")", "OK");
        return new AddResult(fresh.size(), skipped);
    }

    public synchronized void delete(long id) {
        Entry e = repo.find(id).orElseThrow(() -> ApiException.notFound("사전 항목이 없습니다"));
        if (!repo.delete(id)) {
            throw ApiException.notFound("사전 항목이 없습니다");
        }
        engines.reload();
        audit.log("DICT_DELETE", "dictionary " + e.kind() + " #" + id, "OK");
    }

    private static Kind parseKind(String raw) {
        try {
            return Kind.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("kind 는 EXCEPT 또는 FORCE 입니다");
        }
    }
}
