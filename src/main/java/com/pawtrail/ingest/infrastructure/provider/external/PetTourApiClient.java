package com.pawtrail.ingest.infrastructure.provider.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.PetTourListPage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    public PetTourApiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            IngestProperties properties) {

        this.objectMapper = objectMapper;
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.petTour().baseUrl())
                .requestFactory(timeoutFactory())
                .build();
    }

    /**
     * 목록 한 쪽을 받아 옵니다.
     *
     * 인증키를 문자열로 이어 붙이지 않고 파라미터로 넘깁니다.
     * 원본 키에는 더하기와 빗금이 들어 있어 주소에 그대로 두면 뜻이 달라집니다.
     * 넘기면 전송 시점에 한 번만 인코딩됩니다.
     *
     * 실제로 나가는 주소를 남깁니다.
     * 인코딩이 한 번인지 두 번인지는 이 로그로만 확인할 수 있고,
     * 두 번이면 더하기가 %252B 로 보입니다.
     * 인증키는 가려서 남깁니다.
     */
    public PetTourListPage fetchSyncList(int pageNo, int numOfRows) {
        JsonNode root = callWithRetry(pageNo, numOfRows);
        verifyResultCode(root);
        return toPage(root);
    }

    /**
     * 일시적인 실패에만 다시 시도합니다.
     *
     * 다시 시도하지 않는 것이 둘 있습니다.
     * 호출 허용량 초과는 다시 부르는 것이 곧 낭비이고,
     * 인증키나 파라미터 문제는 고쳐야 나아지므로 다시 불러도 결과가 같습니다.
     *
     * 그 판단은 응답 본문에 있습니다.
     * 이 서비스는 실패해도 상태 코드를 200 으로 주기 때문에
     * 예외 종류만으로는 가릴 수 없어 본문을 먼저 읽습니다.
     */
    private JsonNode callWithRetry(int pageNo, int numOfRows) {
        long backoff = properties.retryBackoffMs();
        RuntimeException last = null;

        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                return call(pageNo, numOfRows);

            } catch (QuotaExhaustedException | CustomException e) {
                // 다시 시도해도 같은 결과인 실패임
                throw e;

            } catch (RuntimeException e) {
                last = e;
                log.warn("목록 호출에 실패했습니다. pageNo={} attempt={} reason={}",
                        pageNo, attempt + 1, e.getMessage());

                if (attempt < properties.maxRetries()) {
                    sleep(backoff);
                    backoff *= 2;
                }
            }
        }
        throw new CustomException(IngestErrorCode.SOURCE_API_FAILED, last);
    }

    private JsonNode call(int pageNo, int numOfRows) {
        return restClient.get()
                .uri(uriBuilder -> {
                    var uri = uriBuilder
                            .path("/" + SYNC_LIST_OPERATION)
                            .queryParam("serviceKey", properties.petTour().serviceKey())
                            .queryParam("numOfRows", numOfRows)
                            .queryParam("pageNo", pageNo)
                            .queryParam("MobileOS", "ETC")
                            .queryParam("MobileApp", "pawtrail")
                            .queryParam("_type", "json")
                            .build();
                    log.info("목록을 부릅니다. uri={}", maskKey(uri.toString()));
                    return uri;
                })
                .retrieve()
                .body(JsonNode.class);
    }

    /**
     * 성공과 실패를 가릅니다.
     *
     * 두 응답의 형태가 아예 다릅니다.
     * 성공은 감싼 구조 안에 결과 코드가 있고 실패는 최상위에 바로 있습니다.
     * 감싼 쪽만 보고 짜면 실패일 때 통째로 비어,
     * 응답은 정상인데 아무것도 없는 상태로 나타납니다.
     * 그래서 최상위부터 확인합니다.
     */
    private void verifyResultCode(JsonNode root) {
        if (root == null) {
            throw new CustomException(IngestErrorCode.SOURCE_API_FAILED);
        }

        // 실패 응답은 최상위에 결과 코드가 있습니다
        JsonNode topLevelCode = root.get("resultCode");
        if (topLevelCode != null) {
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
