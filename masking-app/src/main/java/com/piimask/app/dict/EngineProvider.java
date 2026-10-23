package com.piimask.app.dict;

import com.piimask.app.dict.DictionaryRepository.Kind;
import com.piimask.core.MaskingEngine;
import com.piimask.core.dict.NameDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 지금 쓰는 마스킹 엔진: 기본 엔진(EngineConfig) + 기본 사전 + 사용자 사전.
 * 사용자 사전이 바뀌면 {@link #reload()} 로 새 엔진을 만들어 바꿔 끼운다(엔진은 불변).
 * 탐지 테스트·미리보기는 요청마다 {@link #current()} 를 쓰고, 배치는 실행을 만들 때 한 번 받아 끝까지 쓴다.
 */
@Component
public class EngineProvider {

    private static final Logger log = LoggerFactory.getLogger(EngineProvider.class);

    private final MaskingEngine base;
    private final DictionaryRepository repo;
    private final AtomicReference<MaskingEngine> current = new AtomicReference<>();

    public EngineProvider(MaskingEngine base, DictionaryRepository repo) {
        this.base = base;
        this.repo = repo;
        reload();
    }

    public MaskingEngine current() {
        return current.get();
    }

    public synchronized void reload() {
        NameDictionary dict = NameDictionary.builtIn().withUser(valid(Kind.EXCEPT), valid(Kind.FORCE));
        current.set(base.withDictionary(dict));
    }

    /** 규칙이 바뀌었거나 DB 를 직접 고친 행 하나 때문에 기동이 막히지 않도록, 잘못된 단어는 건너뛰고 건수만 남긴다 */
    private List<String> valid(Kind kind) {
        List<String> out = new ArrayList<>();
        int skipped = 0;
        for (String t : repo.terms(kind)) {
            try {
                out.add(NameDictionary.cleanTerm(t));
            } catch (IllegalArgumentException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("name_dictionary {}: 규칙에 맞지 않는 단어 {}개를 건너뜀", kind, skipped);
        }
        return out;
    }
}
