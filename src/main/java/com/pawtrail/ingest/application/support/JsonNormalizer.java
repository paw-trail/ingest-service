package com.pawtrail.ingest.application.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.common.exception.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 원본을 정해진 형태로 직렬화하고 그 문자열의 해시를 뜹니다.
 *
 * 이 클래스가 하나뿐이어야 합니다.
 * 직렬화 규칙이 두 곳에 생기면 같은 내용에 다른 해시가 나오고,
 * 그 증상이 "아무것도 안 바뀌었는데 전량이 다시 추출됨" 이라
 * 원인이 드러나지 않습니다.
 *
 * 지키는 규칙은 넷입니다.
 *
 * 첫째, 키를 사전순으로 정렬합니다.
 * 소스가 필드 순서를 바꿔 보내도 같은 내용이면 같은 해시가 나와야 합니다.
 *
 * 둘째, 공백 없이 직렬화합니다.
 * 들여쓰기가 섞이면 같은 내용에 다른 해시가 나옵니다.
 *
 * 셋째, 빈 문자열을 null 로 바꿉니다.
 * 공공 데이터는 값이 없을 때 빈 문자열을 보냅니다.
 * 우리 판정은 정보 없음을 거짓과 구분해 다루므로 담기 전에 null 로 통일합니다.
 * 이 변환을 먼저 하고 나서 해시를 떠야 둘이 어긋나지 않습니다.
 *
 * 넷째, DB 에 넣은 뒤 다시 읽어서 뜨지 않습니다.
 * jsonb 는 저장하면서 키 순서를 바꾸므로 읽어온 값으로 해시를 뜨면 값이 달라집니다.
 * 그래서 payload 를 문자열로 들고 다닙니다.
 * 여기서 만든 문자열이 그대로 저장되고 해시도 그 문자열에서 나옵니다.
 *
 * 애플리케이션 공용 ObjectMapper 를 쓰지 않습니다.
 * 정렬 설정을 공용에 걸면 API 응답 직렬화까지 함께 바뀝니다.
 */
@Component
public class JsonNormalizer {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final ObjectMapper mapper;

    public JsonNormalizer() {
        this.mapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .disable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 원본을 저장할 형태의 문자열로 만듭니다.
     *
     * 빈 문자열을 null 로 바꾼 뒤 키를 정렬해 공백 없이 직렬화합니다.
     * 중첩된 맵과 배열 안까지 훑습니다.
     */
    public String normalize(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload 는 필수입니다.");
        }
        try {
            return mapper.writeValueAsString(blankToNull(payload));
        } catch (JsonProcessingException e) {
            throw new CustomException(CommonErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * 정규화한 문자열의 SHA-256 을 16진수 소문자로 돌려줍니다. 64자입니다.
     *
     * 반드시 normalize 가 돌려준 문자열을 그대로 넘깁니다.
     * 다른 경로로 만든 문자열을 넘기면 규칙이 갈립니다.
     */
    public String hash(String normalizedJson) {
        if (normalizedJson == null) {
            throw new IllegalArgumentException("normalizedJson 은 필수입니다.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] bytes = digest.digest(normalizedJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 자바 구현이 반드시 제공하므로 실제로는 닿지 않음
            throw new CustomException(CommonErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * 값이 빈 문자열이거나 공백뿐이면 null 로 바꿉니다.
     *
     * 원본을 고치지 않고 새 구조를 만들어 돌려줍니다.
     * 수집기가 넘긴 맵을 그대로 두어야 그쪽에서 다시 쓸 때 놀라지 않습니다.
     *
     * 반환 타입에 LinkedHashMap 을 쓰지만 순서에 기대지는 않습니다.
     * 직렬화 시점에 어차피 사전순으로 정렬됩니다.
     */
    private Object blankToNull(Object value) {
        if (value instanceof String text) {
            return text.isBlank() ? null : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, nested) -> copied.put(String.valueOf(key), blankToNull(nested)));
            return copied;
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            list.forEach(nested -> copied.add(blankToNull(nested)));
            return copied;
        }
        return value;
    }
}
