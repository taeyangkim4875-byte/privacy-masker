package com.piimask.core.detect;

import com.piimask.core.EntityType;
import com.piimask.core.Span;
import com.piimask.core.dict.TermIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 사용자 추가 탐지어: 문맥 단서 없이 단어가 나오면 이름으로 잡는다.
 * 단어 앞이 한글이면 잡지 않는다("박김태양" 의 "김태양" 제외). 한글 단어 뒤는 조사가 붙으므로 보지 않는다.
 * 예외 사전을 적용하지 않는다(사용자가 직접 넣은 말이므로 Recall 우선).
 */
public final class DictionaryNameDetector implements Detector {

    private final TermIndex index;
    private final int size;

    public DictionaryNameDetector(Collection<String> terms) {
        this.index = new TermIndex(terms);
        this.size = terms.size();
    }

    public int size() {
        return size;
    }

    @Override
    public EntityType type() {
        return EntityType.NAME;
    }

    @Override
    public String name() {
        return "이름(사전)";
    }

    @Override
    public List<Span> detect(String text) {
        List<Span> out = new ArrayList<>();
        for (int[] o : index.find(text, true)) {
            out.add(new Span(o[0], o[1], EntityType.NAME, name()));
        }
        return out;
    }
}
