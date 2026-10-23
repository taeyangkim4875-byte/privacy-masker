package com.piimask.core.dict;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 이름 탐지용 사전 묶음(불변).
 *  - surnames: 성씨(1·2글자). 이름 규칙 탐지기가 후보를 만드는 데 쓴다
 *  - exceptions: 이름이 아닌 말. 이 단어가 나온 자리 안의 규칙 이름 스팬을 버린다. "*점" 은 "점" 으로 끝나는 스팬
 *  - forced: 문맥 단서 없이도 이름으로 잡을 말(사용자 추가 탐지어). 예외 사전보다 우선한다
 * 기본 사전은 리소스 파일(surnames.txt, name-exceptions.txt), 사용자 사전은 앱의 메타 DB 에서 온다.
 * 사전 단어는 실명일 수 있으므로 로그·예외 메시지에 넣지 않는다.
 */
public final class NameDictionary {

    public static final int MIN_TERM = 2;
    public static final int MAX_TERM = 50;
    public static final String SUFFIX_MARK = "*";

    private static final NameDictionary BUILT_IN = new NameDictionary(
            load("surnames.txt"), load("name-exceptions.txt"), Set.of());

    private final Set<String> surnames;
    private final Set<String> exceptions;
    private final Set<String> forced;

    private NameDictionary(Set<String> surnames, Set<String> exceptions, Set<String> forced) {
        this.surnames = Set.copyOf(surnames);
        this.exceptions = Set.copyOf(exceptions);
        this.forced = Set.copyOf(forced);
    }

    /** 기본 사전(리소스 파일) */
    public static NameDictionary builtIn() {
        return BUILT_IN;
    }

    /** 사전을 직접 지정(테스트·비교용) */
    public static NameDictionary of(Collection<String> surnames, Collection<String> exceptions, Collection<String> forced) {
        return new NameDictionary(Set.copyOf(surnames), Set.copyOf(exceptions), Set.copyOf(forced));
    }

    /** 기본 사전에 사용자 예외어·추가 탐지어를 더한 사전. 단어는 {@link #cleanTerm} 을 거친다 */
    public NameDictionary withUser(Collection<String> userExceptions, Collection<String> userForced) {
        Set<String> ex = new LinkedHashSet<>(exceptions);
        userExceptions.forEach(t -> ex.add(cleanTerm(t)));
        Set<String> fo = new LinkedHashSet<>(forced);
        userForced.forEach(t -> fo.add(cleanTerm(t)));
        return new NameDictionary(surnames, ex, fo);
    }

    /** 예외어를 뺀 같은 사전(회귀 비교용) */
    public NameDictionary withoutExceptions() {
        return new NameDictionary(surnames, Set.of(), forced);
    }

    public Set<String> surnames() {
        return surnames;
    }

    public Set<String> exceptions() {
        return exceptions;
    }

    public Set<String> forced() {
        return forced;
    }

    /**
     * 사용자 사전 단어 정리: NFC, 앞뒤 공백 제거, 연속 공백 하나로.
     * 2~50자, 제어 문자·"*" 불가. 오류 메시지에는 단어를 넣지 않는다.
     */
    public static String cleanTerm(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("단어가 비어 있습니다");
        }
        // 엑셀·웹에서 붙여 넣으면 섞이는 NBSP 등 유니코드 공백(\p{Z})은 공백으로, BOM·제로폭(\p{Cf})은 지운다
        String t = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replaceAll("\\p{Cf}", "")
                .replaceAll("[\\s\\p{Z}]+", " ")
                .strip();
        if (t.length() < MIN_TERM || t.length() > MAX_TERM) {
            throw new IllegalArgumentException(MIN_TERM + "~" + MAX_TERM + "자여야 합니다");
        }
        if (t.contains(SUFFIX_MARK) || t.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("* 와 제어 문자는 쓸 수 없습니다");
        }
        return t;
    }

    private static Set<String> load(String file) {
        Set<String> out = new LinkedHashSet<>();
        try (InputStream in = NameDictionary.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("사전 리소스가 없습니다: " + file);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add(Normalizer.normalize(t, Normalizer.Form.NFC));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
