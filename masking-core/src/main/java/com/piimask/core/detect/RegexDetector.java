package com.piimask.core.detect;

import com.piimask.core.EntityType;
import com.piimask.core.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정규식 + 검증기 탐지기. 검증기는 매치 문자열과 원문 전체를 받아
 * 체크섬·문맥 같은 추가 조건을 확인한다.
 */
public final class RegexDetector implements Detector {

    @FunctionalInterface
    public interface MatchValidator {
        boolean isValid(String matched, String text, int start, int end);

        MatchValidator ALWAYS = (m, t, s, e) -> true;
    }

    private final String name;
    private final EntityType type;
    private final Pattern pattern;
    private final int group;
    private final MatchValidator validator;

    public RegexDetector(String name, EntityType type, String regex, MatchValidator validator) {
        this(name, type, Pattern.compile(regex), 0, validator);
    }

    public RegexDetector(String name, EntityType type, Pattern pattern, int group, MatchValidator validator) {
        this.name = name;
        this.type = type;
        this.pattern = pattern;
        this.group = group;
        this.validator = validator == null ? MatchValidator.ALWAYS : validator;
    }

    @Override
    public EntityType type() {
        return type;
    }

    @Override
    public String name() {
        return name;
    }

    public String regex() {
        return pattern.pattern();
    }

    @Override
    public List<Span> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int s = m.start(group);
            int e = m.end(group);
            if (s < 0 || e <= s) {
                continue;
            }
            if (validator.isValid(text.substring(s, e), text, s, e)) {
                spans.add(new Span(s, e, type, name));
            }
        }
        return spans;
    }
}
