package com.pawtrail.ingest.infrastructure.provider.convert;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 원본에서 값을 꺼낼 때 쓰는 도우미입니다.
 *
 * 세 변환기가 같은 일을 하므로 한곳에 모읍니다.
 *
 * 값을 못 찾으면 비웁니다. 예외를 던지지 않습니다.
 * 소스가 주지 않은 필드가 흔하고 그것이 잘못된 상태가 아니기 때문입니다.
 * 받는 쪽도 대부분의 필드를 선택으로 두고 있습니다.
 */
final class PayloadPicker {

    // 연-월-일 형태인지 보는 데 씀
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private PayloadPicker() {
    }

    /**
     * 후보 키를 차례로 보아 값이 있는 첫 번째를 돌려줍니다.
     *
     * 후보를 여럿 받는 이유는 소스가 같은 뜻을 다른 이름으로 주기 때문입니다.
     * 관광공사는 분류마다 운영시간 키가 다르고 문화정보원은 한글 이름이 판마다 조금씩 다릅니다.
     *
     * 문자열 "null" 도 빈 값으로 봅니다.
     * 실제 데이터에 그 글자가 들어 있어 그대로 두면 화면에 null 이라는 글자가 뜹니다.
     */
    static String pick(Map<String, Object> container, String... keys) {
        if (container == null) {
            return null;
        }
        for (String key : keys) {
            Object value = container.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty() || "null".equals(text)) {
                continue;
            }
            return text;
        }
        return null;
    }

    /**
     * 중첩된 조각을 맵으로 꺼냅니다.
     *
     * 관광공사만 응답을 넷으로 나눠 담습니다.
     * 없으면 빈 맵을 돌려주어 부르는 쪽이 널 검사를 하지 않아도 되게 합니다.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> section(Map<String, Object> payload, String key) {
        if (payload == null) {
            return Map.of();
        }
        Object value = payload.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 한 겹 싸여 있으면 벗기고 아니면 그대로 씁니다.
     *
     * 고캠핑과 문화정보원은 응답이 하나뿐이라 감싸지 않아도 되지만,
     * 수집할 때 모양을 맞추려고 같은 키로 담은 것이 있어 양쪽을 다 받아 줍니다.
     */
    static Map<String, Object> unwrap(Map<String, Object> payload, String key) {
        Map<String, Object> inner = section(payload, key);
        return inner.isEmpty() ? (payload == null ? Map.of() : payload) : inner;
    }

    /**
     * 날짜를 연-월-일로 맞춥니다. 못 알아보면 비웁니다.
     *
     * 소스가 점이나 빗금으로 구분하거나 뒤에 시각을 붙여 주는 경우가 있습니다.
     * 받는 쪽이 날짜 타입으로 읽으므로 형태가 어긋나면 그 청크가 통째로 거절됩니다.
     * 한 건의 기준일 때문에 나머지를 잃는 것보다 그 값을 비우는 편이 낫습니다.
     */
    static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.length() > 10) {
            text = text.substring(0, 10);
        }
        text = text.replace('/', '-').replace('.', '-');
        if (!DATE.matcher(text).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            // 형태는 맞는데 없는 날짜인 경우임, 2025-02-30 같은 값
            return null;
        }
    }
}
