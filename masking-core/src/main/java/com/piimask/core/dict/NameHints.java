package com.piimask.core.dict;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 이름 점수화에 쓰는 기본 목록(리소스 파일, 사용자가 바꾸지 않음).
 *  - givenSyllables: 이름에 흔한 음절(given-syllables.txt)
 *  - nonNameSyllables: 이름에 거의 안 쓰는 음절(non-name-syllables.txt)
 *  - places: 사람 이름이 아닌 고유어(non-person-words.txt: 지명·장소·금융사·기관). 약한 단서로만 잡힌 후보가 여기 있으면 버린다
 * 파일은 공백으로 구분한 토큰, "#" 줄은 주석.
 */
public final class NameHints {

    private static final NameHints BUILT_IN = new NameHints(
            load("given-syllables.txt"), load("non-name-syllables.txt"), load("non-person-words.txt"));

    private final Set<String> givenSyllables;
    private final Set<String> nonNameSyllables;
    private final Set<String> places;

    private NameHints(Set<String> givenSyllables, Set<String> nonNameSyllables, Set<String> places) {
        this.givenSyllables = Set.copyOf(givenSyllables);
        this.nonNameSyllables = Set.copyOf(nonNameSyllables);
        this.places = Set.copyOf(places);
    }

    public static NameHints builtIn() {
        return BUILT_IN;
    }

    public boolean commonGiven(char c) {
        return givenSyllables.contains(String.valueOf(c));
    }

    public boolean nonName(char c) {
        return nonNameSyllables.contains(String.valueOf(c));
    }

    public boolean place(String word) {
        return places.contains(word);
    }

    public Set<String> givenSyllables() {
        return givenSyllables;
    }

    public Set<String> nonNameSyllables() {
        return nonNameSyllables;
    }

    public Set<String> places() {
        return places;
    }

    private static Set<String> load(String file) {
        Set<String> out = new LinkedHashSet<>();
        try (InputStream in = NameHints.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("사전 리소스가 없습니다: " + file);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                for (String tok : t.split("\\s+")) {
                    out.add(Normalizer.normalize(tok, Normalizer.Form.NFC));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
