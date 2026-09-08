package com.pawtrail.ingest.infrastructure.provider.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.PetTourListPage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 한국관광공사 반려동물 동반여행 서비스를 부릅니다.
 *
 * external 아래에 둡니다. 우리가 만든 다른 서비스가 아니라 바깥 시스템입니다.
 *
 * 이 서비스는 공공데이터포털 게이트웨이를 거치지 않습니다.
 * 같은 기관의 고캠핑은 거치는데 여기만 다릅니다.
 * 그래서 오류 코드 체계가 달라 아래 값들을 이 클래스에만 둡니다.
 */
@Slf4j
@Component
public class PetTourApiClient {

    // 목록 동기화 조회입니다.
    //
    // 지역기반 목록에도 같은 항목이 오지만 그쪽에는 표출 여부가 없습니다.
    // 없으면 감춰진 콘텐츠가 목록에서 그냥 사라져,
    // 없어진 것과 원래 없던 것을 구분할 수 없습니다.
    // 그러면 나중에 장소를 폐업으로 표시할 신호가 없어집니다.
    public static final String SYNC_LIST_OPERATION = "petTourSyncList2";

    private static final String SUCCESS_CODE = "0000";

    // 다시 불러도 결과가 같은 실패입니다. 고쳐야 나아집니다.
    // 다시 시도하면 호출 허용량만 씁니다.
    private static final Set<String> PERMANENT_ERROR_CODES = Set.of(
            "10",   // 파라미터 값이나 형식이 잘못됨
            "11",   // 필수 파라미터가 빠짐
            "12",   // 없는 서비스. 주소 오타
            "20",   // 이용 권한 없음. 일시중지
            "30",   // 등록되지 않은 인증키
            "31");  // 인증키 사용 기한 만료

    // 일일 호출 허용량을 다 썼습니다. 다시 시도하면 안 됩니다.
    private static final String QUOTA_EXCEEDED_CODE = "22";

    // 기관 쪽이 응답하지 않았습니다. 잠시 뒤 다시 부르면 됩니다.
    private static final String TIMEOUT_CODE = "05";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final IngestProperties properties;

    /**
     * 인증키를 미리 인코딩해 둡니다.
     *
     * 설정에는 원본 그대로인 키를 두고 여기서 한 번 인코딩합니다.
     * 더하기와 빗금과 등호가 각각 %2B, %2F, %3D 로 바뀝니다.
     *
     * 이 값을 주소 조립에 그대로 씁니다. 다시 인코딩되면 안 됩니다.
     * 백분율 기호 자체가 인코딩 대상이라 한 번 더 거치면 %2B 가 %252B 가 되고,
     * 받는 쪽은 등록되지 않은 키라고 답합니다.
     */
    private final String encodedServiceKey;

    public PetTourApiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            IngestProperties properties) {

        this.objectMapper = objectMapper;
        this.properties = properties;
        this.encodedServiceKey =
                URLEncoder.encode(properties.petTour().serviceKey(), StandardCharsets.UTF_8);
        this.restClient = builder
                .baseUrl(properties.petTour().baseUrl())
                .requestFactory(timeoutFactory())
                .build();
    }

    /**
     * 목록 한 쪽을 받아 옵니다.
     *
     * 실제로 나가는 주소를 남깁니다.
     * 인코딩이 맞는지는 이 로그로만 확인할 수 있습니다.
     * 인증키는 가려서 남깁니다. 설정 저장소가 공개라 값이 드러나면 안 됩니다.
     */
    public PetTourListPage fetchSyncList(int pageNo, int numOfRows) {
        return toPage(callWithRetry(pageNo, numOfRows));
    }

    /**
     * 일시적인 실패에만 다시 시도합니다.
     *
     * 다시 시도하지 않는 것이 둘 있습니다.
     * 호출 허용량 초과는 다시 부르는 것이 곧 낭비이고,
     * 인증키나 파라미터 문제는 고쳐야 나아지므로 다시 불러도 결과가 같습니다.
     *
     * 그 판단이 응답 본문에 있어 상태 코드만으로는 가릴 수 없습니다.
     * 이 서비스는 실패를 200 으로 주기도 하고 403 으로 주기도 하는데,
     * 403 일 때도 본문에 이유가 담겨 오므로 먼저 읽습니다.
     */
    private JsonNode callWithRetry(int pageNo, int numOfRows) {
        long backoff = properties.retryBackoffMs();
        RuntimeException last = null;

        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                return call(pageNo, numOfRows);

            } catch (RestClientResponseException e) {
                // 상태 코드로 튕겨도 본문에 이유가 있음
                // 다시 시도해도 같은 결과인 실패면 여기서 흐름이 끊김
                inspectErrorBody(e);
                last = e;
                logRetry(pageNo, attempt, e.getMessage());

            } catch (QuotaExhaustedException | CustomException e) {
                // 다시 시도해도 같은 결과인 실패임
                throw e;

            } catch (RuntimeException e) {
                last = e;
                logRetry(pageNo, attempt, e.getMessage());
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
     *
     * 문자열로 받으면 그 경계를 아예 지나가지 않습니다.
     * 읽는 것은 이 클래스가 주입받은 것으로 하고, 그것이 원본을 저장할 때 쓰는 것과 같습니다.
     * 도구가 하나로 모여야 읽은 값과 저장한 값이 어긋나지 않습니다.
     */
    private JsonNode call(int pageNo, int numOfRows) {
        URI uri = buildUri(pageNo, numOfRows);
        log.info("목록을 부릅니다. uri={}", maskKey(uri.toString()));

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
            // 소스가 형식을 바꿨거나 오류 안내를 다른 형태로 보낸 것임
            // 앞부분만 남깁니다. 본문 전체를 남기면 로그가 응답으로 뒤덮입니다
            log.warn("응답을 읽지 못했습니다. body={}",
                    body.substring(0, Math.min(body.length(), 300)));
            throw new IllegalStateException("응답 형식이 올바르지 않음", e);
        }

        // 결과 코드 확인이 여기 있어야 합니다.
        //
        // 이 소스는 실패를 상태 코드로도 주고 200 으로도 주는데,
        // 확인을 재시도 바깥에 두면 200 으로 온 일시적인 실패가 한 번도 다시 시도되지 않고
        // 그대로 밖으로 나갑니다. 초당 제한이나 기관 무응답이 그렇습니다.
        // 게다가 그 예외는 우리 오류 타입으로 감싸이지도 않아 부르는 쪽 계약이 깨집니다.
        //
        // 안으로 들여놓으면 두 경로의 판정이 한곳에 모이고,
        // 다시 시도할 값이 있는 것은 루프가 받아 주고
        // 끝내 실패하면 아래에서 우리 오류로 바뀝니다.
        verifyResultCode(root);
        return root;
    }

    /**
     * 주소를 직접 조립합니다.
     *
     * 라이브러리의 주소 빌더를 쓰지 않습니다.
     * 그쪽에 값을 넘기면 어떤 형태로든 다시 손을 대는데, 인증키에는 그것이 치명적입니다.
     *
     * 세 가지를 차례로 겪고 나서 이 방식으로 왔습니다.
     * 파라미터로 넘기면 더하기와 빗금을 그대로 두어 받는 쪽이 더하기를 공백으로 읽고,
     * 미리 인코딩해 넘기면 백분율 기호를 또 인코딩해 %2B 가 %252B 가 됩니다.
     * 이미 인코딩됐다고 표시하는 방법도 없습니다.
     * 그 자리에 있는 것은 인자를 받는 다른 메서드라 값이 조용히 무시됩니다.
     *
     * 완성된 주소를 넘기면 라이브러리가 그대로 씁니다.
     *
     * *다른 값도 인코딩을 안 받습니다.
     *  지금은 전부 영문과 숫자라 문제가 없으나
     *  나중에 지역 이름 같은 한글 값을 넣으면 그 자리에서 깨집니다.
     *  그때는 그 값만 따로 인코딩해 붙입니다.
     */
    private URI buildUri(int pageNo, int numOfRows) {
        String query = "serviceKey=" + encodedServiceKey
                + "&numOfRows=" + numOfRows
                + "&pageNo=" + pageNo
                + "&MobileOS=ETC"
                + "&MobileApp=pawtrail"
                + "&_type=json";

        return URI.create(properties.petTour().baseUrl()
                + "/" + SYNC_LIST_OPERATION + "?" + query);
    }

    /**
     * 상태 코드로 튕긴 응답의 본문을 읽습니다.
     *
     * 이 서비스는 실패를 세 가지 형태로 줍니다.
     * 성공과 같은 구조에 코드만 다른 것, 최상위에 코드가 있는 것,
     * 그리고 인증에서 막혔을 때 아래처럼 또 다른 구조로 오는 것입니다.
     *
     * <pre>
     * { "OpenAPI_ServiceResponse": { "cmmMsgHeader": {
     *     "errMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
     *     "returnReasonCode": "30" } } }
     * </pre>
     *
     * 이 구조를 안 읽으면 등록되지 않은 키인데도 일시적인 실패로 보고 계속 다시 부릅니다.
     * 고쳐야 나아지는 것이라 몇 번을 불러도 같은 답이 옵니다.
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
            if (code != null) {
                handleErrorCode(code, header.path("errMsg").asText(""));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // 본문이 JSON 이 아니면 판단할 근거가 없으므로 일시적인 실패로 봄
        }
    }

    /**
     * 성공과 실패를 가릅니다.
     *
     * 성공은 감싼 구조 안에 결과 코드가 있고 실패는 최상위에 바로 있습니다.
     * 감싼 쪽만 보고 짜면 실패일 때 통째로 비어,
     * 응답은 정상인데 아무것도 없는 상태로 나타납니다.
     * 그래서 최상위부터 확인합니다.
     */
    private void verifyResultCode(JsonNode root) {
        if (root == null) {
            throw new CustomException(IngestErrorCode.SOURCE_API_FAILED);
        }

        // 실패 응답은 감싼 구조 없이 최상위에 결과 코드가 옵니다
        //
        // 값을 보지 않고 오류로 단정하면 안 됩니다.
        // 최상위에 성공 코드가 담겨 오는 응답이 있으면 정상인데도 실패로 처리되고,
        // 알 수 없는 코드로 분류되어 다시 시도까지 하게 됩니다.
        JsonNode topLevelCode = root.get("resultCode");
        if (topLevelCode != null && !SUCCESS_CODE.equals(topLevelCode.asText())) {
            handleErrorCode(topLevelCode.asText(), text(root, "resultMsg"));
        }

        JsonNode header = root.path("response").path("header");
        String code = header.path("resultCode").asText(null);
        if (code == null) {
            throw new CustomException(IngestErrorCode.SOURCE_API_FAILED);
        }
        if (!SUCCESS_CODE.equals(code)) {
            handleErrorCode(code, header.path("resultMsg").asText(""));
        }
    }

    private void handleErrorCode(String code, String message) {
        if (QUOTA_EXCEEDED_CODE.equals(code)) {
            throw new QuotaExhaustedException(SYNC_LIST_OPERATION);
        }
        if (PERMANENT_ERROR_CODES.contains(code)) {
            log.error("고쳐야 하는 오류입니다. code={} message={}", code, message);
            throw new CustomException(IngestErrorCode.SOURCE_API_FAILED);
        }
        // 초당 제한 초과와 기관 무응답이 여기 옵니다. 다시 시도할 값이 있습니다
        if (!TIMEOUT_CODE.equals(code)) {
            log.warn("알 수 없는 오류 코드입니다. code={} message={}", code, message);
        }
        throw new IllegalStateException("소스 오류 code=" + code + " message=" + message);
    }

    /**
     * 항목을 원본 그대로 지도로 옮깁니다.
     *
     * 항목이 없을 때 빈 객체가 아니라 빈 문자열로 오는 경우가 있습니다.
     * 타입 있는 객체로 받으면 그때 역직렬화가 깨지므로 형태를 먼저 확인합니다.
     */
    private PetTourListPage toPage(JsonNode root) {
        JsonNode body = root.path("response").path("body");
        JsonNode item = body.path("items").path("item");

        List<Map<String, Object>> items = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(node -> items.add(toMap(node)));
        } else if (item.isObject()) {
            // 한 건만 오면 배열로 감싸지 않는 경우가 있습니다
            items.add(toMap(item));
        }

        return new PetTourListPage(
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

    private void logRetry(int pageNo, int attempt, String reason) {
        log.warn("목록 호출에 실패했습니다. pageNo={} attempt={} reason={}",
                pageNo, attempt + 1, reason);
    }

    /**
     * 인증키를 가려 로그에 남깁니다. 설정 저장소가 공개라 값이 드러나면 안 됩니다.
     */
    private String maskKey(String uri) {
        return uri.replaceAll("(serviceKey=)[^&]+", "$1***");
    }

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
