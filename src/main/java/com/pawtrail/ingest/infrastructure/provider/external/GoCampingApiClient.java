package com.pawtrail.ingest.infrastructure.provider.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.exception.PermanentSourceErrorException;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.GoCampingListPage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 한국관광공사 고캠핑 정보를 부릅니다.
 *
 * 반려동물 동반여행 서비스와 클래스를 나눈 이유가 있습니다.
 * 두 클래스에서 겹치는 것은 재시도 루프와 인증키 인코딩 정도이고 마흔 줄 남짓인데,
 * 응답 파싱과 오류 매핑은 서로 다릅니다.
 *
 * 그 마흔 줄을 함께 쓰려고 상속 구조로 바꾸면 저쪽 클래스를 건드려야 하는데,
 * 그 코드는 상세를 삼천 번 넘게 실제로 부르며 검증을 마친 상태입니다.
 * 다시 검증하려면 호출 허용량을 또 써야 하고 그것은 되돌릴 수 없는 자원입니다.
 * 세 번째 소스가 생길 때 무엇이 실제로 공통인지 보고 판단하는 편이 낫습니다.
 *
 * *이 서비스는 공공데이터포털 게이트웨이를 거칩니다.
 *  반려동물 동반여행 쪽은 거치지 않아 오류 코드 목록이 서로 다릅니다.
 *  다만 성공 응답의 모양은 같았습니다. 2026년 9월 9일 실호출에서 확인했습니다.
 *  게이트웨이 판이라 다를 것으로 봤는데 그렇지 않았습니다.
 */
@Slf4j
@Component
public class GoCampingApiClient {

    /**
     * 목록 조회입니다. 이 서비스에서 부르는 오퍼레이션은 이것 하나뿐입니다.
     *
     * 상세 조회가 따로 없습니다. 여든한 개 필드가 목록 응답에 전부 들어 있어
     * 한 번 부르면 그날 필요한 것이 다 옵니다.
     */
    public static final String BASED_LIST_OPERATION = "basedList";

    private static final String SUCCESS_CODE = "0000";

    private static final String COMMON_QUERY = "&MobileOS=ETC&MobileApp=pawtrail&_type=json";

    /**
     * 다시 불러도 결과가 같은 실패입니다.
     *
     * 반려동물 동반여행 쪽과 목록이 다릅니다.
     * 이쪽은 게이트웨이를 거쳐서 차단된 주소(29)와 허용되지 않은 요청 방식(04)이 더 있습니다.
     */
    private static final Set<String> PERMANENT_ERROR_CODES = Set.of(
            "04",   // 허용되지 않은 요청 방식
            "10",   // 파라미터 값이나 형식이 잘못됨
            "12",   // 없는 서비스. 주소 오타
            "20",   // 이용 권한 없음. 인증키 누락
            "29",   // 차단된 주소
            "30",   // 등록되지 않은 인증키
            "31");  // 인증키 사용 기한 만료

    private static final String QUOTA_EXCEEDED_CODE = "22";

    /**
     * 잠시 뒤 다시 부르면 되는 실패입니다.
     *
     * 01 은 게이트웨이 내부 오류이고 05 는 기관 쪽 무응답,
     * 23 은 초당 호출 제한입니다.
     * 이름을 적어 두지 않으면 알 수 없는 코드로 분류되어 그때마다 경고가 남습니다.
     */
    private static final Set<String> TRANSIENT_ERROR_CODES = Set.of("01", "05", "23");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final IngestProperties properties;

    /**
     * 인증키를 미리 인코딩해 둡니다.
     *
     * 설정에는 원본 그대로인 키를 두고 여기서 한 번 인코딩합니다.
     * 이 값을 주소 조립에 그대로 쓰며 다시 인코딩되면 안 됩니다.
     * 백분율 기호 자체가 인코딩 대상이라 한 번 더 거치면 거절당합니다.
     */
    private final String encodedServiceKey;

    public GoCampingApiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            IngestProperties properties) {

        this.objectMapper = objectMapper;
        this.properties = properties;
        this.encodedServiceKey =
                URLEncoder.encode(properties.goCamping().serviceKey(), StandardCharsets.UTF_8);
        this.restClient = builder
                .baseUrl(properties.goCamping().baseUrl())
                .requestFactory(timeoutFactory())
                .build();
    }

    /**
     * 목록 한 쪽을 받아 옵니다.
     *
     * 한 쪽에 전량이 들어오도록 크기를 크게 잡습니다.
     * 2026년 9월 9일 실측에서 삼천이백 건을 요청해 삼천백십오 건 전량을 받았고
     * 7.27메가바이트에 1.2초 걸렸습니다. 읽기 제한 삼십 초에 여유가 큽니다.
     *
     * 그래도 부르는 쪽이 쪽을 넘길 수 있게 열어 둡니다.
     * 소스가 자라 한 쪽에 안 들어가는 날이 오면 조용히 잘리는 것이 가장 나쁩니다.
     *
     * @param onAttempt 요청을 한 번 내보낼 때마다 오퍼레이션 이름과 함께 불립니다.
     */
    public GoCampingListPage fetchBasedList(
            int pageNo, int numOfRows, Consumer<String> onAttempt) {

        String query = "serviceKey=" + encodedServiceKey
                + "&numOfRows=" + numOfRows
                + "&pageNo=" + pageNo
                + COMMON_QUERY;

        JsonNode root = callWithRetry(
                uri(BASED_LIST_OPERATION, query), "pageNo=" + pageNo, onAttempt);
        return toPage(root);
    }

    /**
     * 일시적인 실패에만 다시 시도합니다.
     *
     * 다시 시도하지 않는 것이 둘 있습니다.
     * 호출 허용량 초과는 다시 부르는 것이 곧 낭비이고,
     * 인증키나 파라미터 문제는 고쳐야 나아지므로 다시 불러도 결과가 같습니다.
     *
     * 그 판단이 응답 본문에 있어 상태 코드만으로는 가릴 수 없습니다.
     *
     * @param onAttempt 요청을 보내기 직전에 부릅니다.
     *                  다시 시도하면 요청이 그만큼 더 나가는데 한 번만 세면
     *                  기록이 실제로 나간 요청 수보다 작아집니다.
     */
    private JsonNode callWithRetry(URI uri, String label, Consumer<String> onAttempt) {
        long backoff = properties.retryBackoffMs();
        RuntimeException last = null;

        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            onAttempt.accept(BASED_LIST_OPERATION);

            try {
                return call(uri);

            } catch (RestClientResponseException e) {
                // 상태 코드로 튕겨도 본문에 이유가 있음
                inspectErrorBody(e);
                last = e;
                logRetry(label, attempt, e.getMessage());

            } catch (QuotaExhaustedException | PermanentSourceErrorException
                     | CustomException e) {
                throw e;

            } catch (RuntimeException e) {
                last = e;
                logRetry(label, attempt, e.getMessage());
            }

            if (attempt < properties.maxRetries()) {
                sleep(backoff);
                backoff *= 2;
            }
        }
        throw new CustomException(IngestErrorCode.SOURCE_API_FAILED, last);
    }

    /**
     * 응답을 문자열로 받아 우리 손으로 읽습니다.
     *
     * 라이브러리에게 바로 객체로 바꿔 달라고 하면 안 됩니다.
     * 스프링이 응답을 바꿀 때 쓰는 것과 우리가 주입받아 쓰는 것이 서로 다른 세대의 도구라,
     * 우리가 원하는 타입을 그쪽이 만들지 못하고 정의 오류로 끊깁니다.
     */
    private JsonNode call(URI uri) {
        log.debug("소스를 부릅니다. uri={}", maskKey(uri.toString()));

        String body = restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);

        if (body == null || body.isBlank()) {
            throw new IllegalStateException("응답 본문이 비어 있음");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 앞부분만 남깁니다. 본문 전체를 남기면 로그가 응답으로 뒤덮입니다
            log.warn("응답을 읽지 못했습니다. body={}",
                    body.substring(0, Math.min(body.length(), 300)));
            throw new IllegalStateException("응답 형식이 올바르지 않음", e);
        }

        // 결과 코드 확인이 재시도 루프 안에 있어야 합니다.
        // 바깥에 두면 200 으로 온 일시적인 실패가 한 번도 다시 시도되지 않고 그대로 나갑니다.
        verifyResultCode(root);
        return root;
    }

    /**
     * 주소를 직접 조립합니다.
     *
     * 라이브러리의 주소 빌더를 쓰지 않습니다.
     * 그쪽에 값을 넘기면 어떤 형태로든 다시 손을 대는데 인증키에는 그것이 치명적입니다.
     * 파라미터로 넘기면 더하기를 그대로 두어 받는 쪽이 공백으로 읽고,
     * 미리 인코딩해 넘기면 백분율 기호를 또 인코딩합니다.
     *
     * *다른 값도 인코딩을 안 받습니다.
     *  지금은 전부 영문과 숫자라 문제가 없으나 한글 값을 넣으면 그 자리에서 깨집니다.
     */
    private URI uri(String operation, String query) {
        return URI.create(properties.goCamping().baseUrl() + "/" + operation + "?" + query);
    }

    /**
     * 상태 코드로 튕긴 응답의 본문을 읽습니다.
     *
     * 인증에서 막히면 성공과 다른 구조로 오고 상태 코드도 사백대입니다.
     * 이 구조를 안 읽으면 고쳐야 하는 오류인데도 일시적인 실패로 보고 계속 다시 부릅니다.
     */
    private void inspectErrorBody(RestClientResponseException e) {
        String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            JsonNode header = objectMapper.readTree(body)
                    .path("OpenAPI_ServiceResponse")
                    .path("cmmMsgHeader");
            String code = header.path("returnReasonCode").asText(null);
            if (code == null) {
                return;
            }

            // 일시 오류면 아무것도 던지지 않고 돌아갑니다.
            //
            // 이 메서드는 catch 절 안에서 불리는데, 거기서 던진 예외는
            // 같은 try 의 다른 catch 절이 잡지 못하고 그대로 밖으로 나갑니다.
            // 그러면 재시도가 한 번도 일어나지 않고 마지막 감싸기도 건너뜁니다.
            // 같은 코드인데 상태 코드가 200 이냐 아니냐에 따라 처리가 갈리게 됩니다.
            switch (classify(code, header.path("errMsg").asText(""))) {
                case QUOTA -> throw new QuotaExhaustedException(BASED_LIST_OPERATION);
                case PERMANENT ->
                        throw new PermanentSourceErrorException(
                                BASED_LIST_OPERATION, code, header.path("errMsg").asText(""));
                case TRANSIENT -> {
                    // 부르는 쪽의 catch 가 이어져 다시 시도함
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // 본문이 JSON 이 아니면 판단할 근거가 없으므로 일시적인 실패로 봄
        }
    }

    /**
     * 성공과 실패를 가릅니다.
     *
     * 성공은 감싼 구조 안에 결과 코드가 있습니다.
     * 실패가 최상위로 오는 경우도 있어 그쪽을 먼저 봅니다.
     */
    private void verifyResultCode(JsonNode root) {
        if (root == null) {
            throw new CustomException(IngestErrorCode.SOURCE_API_FAILED);
        }

        JsonNode topLevelCode = root.get("resultCode");
        if (topLevelCode != null && !SUCCESS_CODE.equals(topLevelCode.asText())) {
            throwFor(topLevelCode.asText(), text(root, "resultMsg"));
        }

        JsonNode header = root.path("response").path("header");
        String code = header.path("resultCode").asText(null);
        if (code == null) {
            throw new CustomException(IngestErrorCode.SOURCE_API_FAILED);
        }
        if (!SUCCESS_CODE.equals(code)) {
            throwFor(code, header.path("resultMsg").asText(""));
        }
    }

    /**
     * 200 으로 온 실패를 예외로 바꿉니다. 어느 부류든 던집니다.
     *
     * 이 자리는 try 블록 안이라 일시 오류를 던져도 아래 catch 가 받아 다시 시도합니다.
     */
    private void throwFor(String code, String message) {
        switch (classify(code, message)) {
            case QUOTA -> throw new QuotaExhaustedException(BASED_LIST_OPERATION);
            case PERMANENT ->
                    throw new PermanentSourceErrorException(BASED_LIST_OPERATION, code, message);
            case TRANSIENT ->
                    throw new IllegalStateException(
                            "소스 오류 code=" + code + " message=" + message);
        }
    }

    /**
     * 오류 코드가 어느 부류인지만 가리고 던지지는 않습니다.
     *
     * 던지는 일을 부르는 쪽에 맡기는 이유가 있습니다.
     * 이 판정이 두 자리에서 쓰이는데 일시 오류일 때 해야 할 일이 서로 다릅니다.
     *
     * 응답이 200 으로 온 자리에서는 던져야 합니다.
     * 그 자리는 try 블록 안이라 던지면 아래 catch 가 받아 다시 시도합니다.
     *
     * 상태 코드로 튕긴 자리에서는 던지면 안 됩니다.
     * 그 자리가 이미 catch 절 안인데, 자바에서 catch 절 안에서 던진 예외는
     * 같은 try 의 다른 catch 절이 잡지 못합니다.
     * 그대로 메서드 밖으로 나가 재시도가 한 번도 일어나지 않고,
     * 마지막 줄의 감싸기도 건너뛰어 부르는 쪽이 받는 예외 타입이 경로마다 달라집니다.
     *
     * 판정과 처리를 갈라 두면 어느 자리에서 무엇을 하는지가 코드에 드러납니다.
     */
    private ErrorKind classify(String code, String message) {
        if (QUOTA_EXCEEDED_CODE.equals(code)) {
            return ErrorKind.QUOTA;
        }
        if (PERMANENT_ERROR_CODES.contains(code)) {
            log.error("고쳐야 하는 오류입니다. code={} message={}", code, message);
            return ErrorKind.PERMANENT;
        }
        if (!TRANSIENT_ERROR_CODES.contains(code)) {
            // 모르는 코드는 일시 오류로 봅니다.
            // 다시 시도해 보는 편이 낫고, 정말 고쳐야 하는 것이면 재시도를 다 쓰고 실패합니다.
            log.warn("알 수 없는 오류 코드입니다. code={} message={}", code, message);
        }
        return ErrorKind.TRANSIENT;
    }

    /**
     * 오류 코드의 부류입니다.
     *
     * 부류마다 뒤이어 할 일이 정반대라 갈라 둡니다.
     * 허용량 초과는 오늘 몫을 다 쓴 것이고, 영구 오류는 고쳐야 나아지며,
     * 일시 오류만 다시 시도할 값이 있습니다.
     */
    private enum ErrorKind {
        QUOTA,
        PERMANENT,
        TRANSIENT
    }

    /**
     * 목록 한 쪽을 원본 그대로 옮깁니다.
     *
     * 항목이 없을 때 빈 객체가 아니라 빈 문자열로 오는 경우가 있습니다.
     * 반려동물 동반여행 쪽에서 실제로 겪었고 같은 기관이라 같은 방식으로 방어합니다.
     */
    private GoCampingListPage toPage(JsonNode root) {
        JsonNode body = root.path("response").path("body");
        JsonNode item = body.path("items").path("item");

        List<Map<String, Object>> items = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(node -> items.add(toMap(node)));
        } else if (item.isObject()) {
            items.add(toMap(item));
        }

        return new GoCampingListPage(
                body.path("totalCount").asInt(0),
                body.path("pageNo").asInt(0),
                body.path("numOfRows").asInt(0),
                items);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "" : value.asText();
    }

    private void logRetry(String label, int attempt, String reason) {
        log.warn("호출에 실패했습니다. operation={} {} attempt={} reason={}",
                BASED_LIST_OPERATION, label, attempt + 1, reason);
    }

    /**
     * 인증키를 가려 로그에 남깁니다. 설정 저장소가 공개라 값이 드러나면 안 됩니다.
     */
    private String maskKey(String uri) {
        return uri.replaceAll("(serviceKey=)[^&]+", "$1***");
    }

    /**
     * 응답이 7메가바이트를 넘어 읽기 제한을 넉넉히 둡니다.
     */
    private SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수집이 중단됐습니다", e);
        }
    }
}
