package com.pawtrail.ingest.infrastructure.provider.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.exception.PermanentSourceErrorException;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.PetTourListPage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 오류 응답을 어떻게 가르는지와, 어느 오퍼레이션에서 걸렸는지가 밖으로 나가는지를 봅니다.
 *
 * 오퍼레이션 이름이 중요한 이유가 있습니다.
 * 허용량이 오퍼레이션마다 따로 걸려서, 어느 것을 부르다 멈췄는지가
 * 그대로 진행 기록의 열쇠가 되고 다음 날 이어받는 근거가 됩니다.
 *
 * 실제 서버를 띄워 확인합니다.
 * 상태 코드와 본문이 함께 와야 재현되는 문제가 있어 흉내로는 잡히지 않습니다.
 */
class PetTourApiClientTest {

    private static final String LIST_SUCCESS = """
            {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
             "body":{"items":{"item":[{"contentid":"1019041","title":"와룡공원"}]},
             "numOfRows":1,"pageNo":1,"totalCount":1}}}""";

    private static final String DETAIL_SUCCESS = """
            {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
             "body":{"items":{"item":[{"contentid":"1019041","acmpyTypeCd":"전구역 동반가능"}]},
             "numOfRows":1,"pageNo":1,"totalCount":1}}}""";

    private HttpServer server;
    private final List<Response> queued = new ArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> recordedAttempts = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int index = requestCount.getAndIncrement();
            Response response = queued.get(Math.min(index, queued.size() - 1));
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("목록 응답을 쪽으로 옮긴다")
    void readsListPage() {
        given(new Response(200, LIST_SUCCESS));

        PetTourListPage page = client(2).fetchSyncList(1, 10, recordedAttempts::add);

        assertThat(page.totalCount()).isEqualTo(1);
        assertThat(page.items().get(0)).containsEntry("contentid", "1019041");
    }

    @Test
    @DisplayName("상세 응답을 항목으로 옮긴다")
    void readsDetailItem() {
        given(new Response(200, DETAIL_SUCCESS));

        Map<String, Object> item =
                client(2).fetchPetTourDetail("1019041", recordedAttempts::add);

        assertThat(item).containsEntry("acmpyTypeCd", "전구역 동반가능");
    }

    @Test
    @DisplayName("소개 정보가 없으면 빈 지도를 돌려준다")
    void returnsEmptyMapWhenIntroIsMissing() {
        given(new Response(200, """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                 "body":{"items":"","numOfRows":0,"pageNo":1,"totalCount":0}}}"""));

        Map<String, Object> item =
                client(0).fetchIntroDetail("2752049", "28", recordedAttempts::add);

        // 실패가 아니라 그 콘텐츠에 소개 정보가 등록되지 않은 것뿐임
        assertThat(item).isEmpty();
    }

    @Test
    @DisplayName("200 으로 온 일시 오류는 다시 시도한다")
    void retriesTransientErrorSentWithOkStatus() {
        given(new Response(200, topLevelError("23")),
                new Response(200, LIST_SUCCESS));

        client(2).fetchSyncList(1, 10, recordedAttempts::add);

        assertThat(requestCount).hasValue(2);
    }

    @Test
    @DisplayName("상태 코드로 튕긴 일시 오류도 다시 시도한다")
    void retriesTransientErrorSentWithErrorStatus() {
        given(new Response(429, gatewayError("23", "초당 호출 초과")),
                new Response(429, gatewayError("23", "초당 호출 초과")),
                new Response(200, LIST_SUCCESS));

        PetTourListPage page = client(2).fetchSyncList(1, 10, recordedAttempts::add);

        // catch 절 안에서 던지면 그대로 밖으로 나가 다시 시도가 한 번도 안 일어남
        assertThat(page.items()).hasSize(1);
        assertThat(requestCount).hasValue(3);
    }

    @Test
    @DisplayName("끝내 실패하면 어느 경로든 같은 예외로 감싼다")
    void wrapsExhaustedRetriesTheSameWay() {
        given(new Response(429, gatewayError("23", "초당 호출 초과")));

        assertThatThrownBy(() -> client(2).fetchSyncList(1, 10, recordedAttempts::add))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(IngestErrorCode.SOURCE_API_FAILED));

        assertThat(requestCount).hasValue(3);
    }

    @Test
    @DisplayName("고쳐야 하는 오류는 다시 시도하지 않는다")
    void doesNotRetryPermanentError() {
        given(new Response(403, gatewayError("30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR")));

        assertThatThrownBy(() -> client(2).fetchCommonDetail("1019041", recordedAttempts::add))
                .isInstanceOf(PermanentSourceErrorException.class)
                .hasMessageContaining("code=30");

        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("허용량 초과는 어느 오퍼레이션에서 걸렸는지를 알린다")
    void tellsWhichOperationRanOutOfQuota() {
        given(new Response(403, gatewayError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS")));

        assertThatThrownBy(
                () -> client(2).fetchIntroDetail("1019041", "12", recordedAttempts::add))
                .isInstanceOf(QuotaExhaustedException.class)
                .satisfies(e -> assertThat(((QuotaExhaustedException) e).getOperation())
                        .isEqualTo(PetTourApiClient.DETAIL_INTRO_OPERATION));

        // 허용량이 오퍼레이션마다 따로 걸려 어느 것인지가 재개의 근거가 됨
        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("요청을 내보낼 때마다 그 오퍼레이션 이름으로 기록한다")
    void recordsEveryAttemptWithOperationName() {
        given(new Response(429, gatewayError("23", "초당 호출 초과")),
                new Response(200, DETAIL_SUCCESS));

        client(2).fetchPetTourDetail("1019041", recordedAttempts::add);

        assertThat(recordedAttempts).containsExactly(
                PetTourApiClient.DETAIL_PET_TOUR_OPERATION,
                PetTourApiClient.DETAIL_PET_TOUR_OPERATION);
    }

    private void given(Response... responses) {
        queued.addAll(List.of(responses));
    }

    private PetTourApiClient client(int maxRetries) {
        IngestProperties properties = new IngestProperties(
                20, 0, maxRetries, 1, 5,
                new IngestProperties.PetTour(baseUrl(), "test-only", 1000),
                new IngestProperties.GoCamping("http://localhost", "test-only", 3200),
                new IngestProperties.Culture("build/tmp/test-culture.csv"));
        return new PetTourApiClient(RestClient.builder(), new ObjectMapper(), properties);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String topLevelError(String code) {
        return """
                {"responseTime":"2026-09-09T15:59:36.927","resultCode":"%s",
                 "resultMsg":"ERROR"}""".formatted(code);
    }

    private String gatewayError(String code, String message) {
        return """
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
                 "errMsg":"%s","returnReasonCode":"%s"}}}""".formatted(message, code);
    }

    private record Response(int status, String body) {
    }
}
