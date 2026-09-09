package com.pawtrail.ingest.application.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 해시가 흔들리면 아무것도 안 바뀌었는데 전량이 다시 추출됩니다.
 * 증상이 원인을 가리키지 않으므로 규칙을 테스트로 못 박아 둡니다.
 */
class JsonNormalizerTest {

    private final JsonNormalizer normalizer = new JsonNormalizer();

    @Test
    @DisplayName("키 순서가 달라도 같은 문자열이 나온다")
    void ordersKeys() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", 1);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 1);
        second.put("b", 2);

        assertThat(normalizer.normalize(first)).isEqualTo(normalizer.normalize(second));
    }

    @Test
    @DisplayName("중첩된 맵과 배열 안의 키도 정렬한다")
    void ordersNestedKeys() {
        Map<String, Object> nestedFirst = new LinkedHashMap<>();
        nestedFirst.put("y", 2);
        nestedFirst.put("x", 1);

        Map<String, Object> nestedSecond = new LinkedHashMap<>();
        nestedSecond.put("x", 1);
        nestedSecond.put("y", 2);

        String first = normalizer.normalize(Map.of("items", List.of(nestedFirst)));
        String second = normalizer.normalize(Map.of("items", List.of(nestedSecond)));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("빈 문자열과 공백만 있는 값은 null 이 된다")
    void turnsBlankIntoNull() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("empty", "");
        payload.put("space", "   ");
        payload.put("kept", "값");

        assertThat(normalizer.normalize(payload))
                .isEqualTo("{\"empty\":null,\"kept\":\"값\",\"space\":null}");
    }

    @Test
    @DisplayName("공백 없이 직렬화한다")
    void writesWithoutWhitespace() {
        String result = normalizer.normalize(Map.of("a", 1));

        assertThat(result).doesNotContain(" ").doesNotContain("\n");
    }

    @Test
    @DisplayName("원본 맵을 고치지 않는다")
    void keepsSourceUntouched() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("empty", "");

        normalizer.normalize(payload);

        assertThat(payload.get("empty")).isEqualTo("");
    }

    @Test
    @DisplayName("해시는 64자이고 같은 문자열이면 같다")
    void hashesToSixtyFourChars() {
        String normalized = normalizer.normalize(Map.of("a", 1));

        String hash = normalizer.hash(normalized);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(normalizer.hash(normalized));
    }

    @Test
    @DisplayName("내용이 다르면 해시도 다르다")
    void hashesDifferentlyForDifferentContent() {
        String first = normalizer.hash(normalizer.normalize(Map.of("a", 1)));
        String second = normalizer.hash(normalizer.normalize(Map.of("a", 2)));

        assertThat(first).isNotEqualTo(second);
    }
}
