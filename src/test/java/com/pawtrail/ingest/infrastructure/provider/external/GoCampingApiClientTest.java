package com.pawtrail.ingest.infrastructure.provider.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.exception.PermanentSourceErrorException;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.GoCampingListPage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 오류 응답을 어떻게 가르는지를 확인합니다.
 *
 * 이 클래스에 시험이 없어서 결함이 하나 지나갔습니다.
 * 상태 코드로 튕긴 응답을 읽는 자리가 catch 절 안인데 거기서 예외를 던지면
 * 같은 try 의 다른 catch 절이 잡지 못하고 그대로 밖으로 나갑니다.
 * 그래서 같은 오류 코드인데 응답이 200 으로 오면 다시 시도되고
 * 사백대로 오면 한 번도 다시 시도되지 않았습니다.
 *
 * 실제 서버를 띄워 확인합니다.
 * 상태 코드와 본문이 함께 와야 재현되는 문제라 흉내로는 잡히지 않습니다.
 * 자바에 들어 있는 것을 쓰므로 의존성이 늘지 않습니다.
 */
class GoCampingApiClientTest {

    private static final String SUCCESS_BODY = """
            {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
             "body":{"items":{"item":[{"contentId":"2758","facltNm":"주문진글램핑"}]},
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
    @DisplayName("정상 응답을 항목으로 옮긴다")
    void readsSuccessfulResponse() {
        given(new Response(200, SUCCESS_BODY));

        GoCampingListPage page = client(2).fetchBasedList(1, 10, recordedAttempts::add);

        assertThat(page.totalCount()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0)).containsEntry("contentId", "2758");
        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("200 으로 온 일시 오류는 다시 시도한다")
    void retriesTransientErrorSentWithOkStatus() {
        given(new Response(200, topLevelError("23")),
                new Response(200, topLevelError("23")),
                new Response(200, SUCCESS_BODY));

        GoCampingListPage page = client(2).fetchBasedList(1, 10, recordedAttempts::add);

        assertThat(page.items()).hasSize(1);
        assertThat(requestCount).hasValue(3);
    }

    @Test
    @DisplayName("상태 코드로 튕긴 일시 오류도 다시 시도한다")
    void retriesTransientErrorSentWithErrorStatus() {
        given(new Response(429, gatewayError("23", "초당 호출 초과")),
                new Response(429, gatewayError("23", "초당 호출 초과")),
                new Response(200, SUCCESS_BODY));

        GoCampingListPage page = client(2).fetchBasedList(1, 10, recordedAttempts::add);

        // 같은 코드인데 상태 코드에 따라 처리가 갈리면 안 됨
        // catch 절 안에서 던지면 그대로 밖으로 나가 다시 시도가 한 번도 안 일어남
        assertThat(page.items()).hasSize(1);
        assertThat(requestCount).hasValue(3);
    }

    @Test
    @DisplayName("끝내 실패하면 어느 경로든 같은 예외로 감싼다")
    void wrapsExhaustedRetriesTheSameWay() {
        given(new Response(429, gatewayError("23", "초당 호출 초과")));

        assertThatThrownBy(() -> client(2).fetchBasedList(1, 10, recordedAttempts::add))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(IngestErrorCode.SOURCE_API_FAILED));

        // 재시도를 다 쓴 것임 — 처음 한 번에 두 번 더
        assertThat(requestCount).hasValue(3);
    }

    @Test
    @DisplayName("고쳐야 하는 오류는 상태 코드로 와도 다시 시도하지 않는다")
    void doesNotRetryPermanentError() {
        given(new Response(403, gatewayError("30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR")));

        assertThatThrownBy(() -> client(2).fetchBasedList(1, 10, recordedAttempts::add))
                .isInstanceOf(PermanentSourceErrorException.class)
                .hasMessageContaining("code=30");

        // 다시 불러도 결과가 같음. 허용량만 씀
        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("허용량 초과는 상태 코드로 와도 다시 시도하지 않는다")
    void doesNotRetryQuotaExceeded() {
        given(new Response(403, gatewayError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS")));

        assertThatThrownBy(() -> client(2).fetchBasedList(1, 10, recordedAttempts::add))
                .isInstanceOf(QuotaExhaustedException.class);

        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("200 으로 온 고쳐야 하는 오류도 다시 시도하지 않는다")
    void doesNotRetryPermanentErrorSentWithOkStatus() {
        // 10 은 파라미터 형식 오류입니다.
        // 반려동물 동반여행 쪽에 있는 11(필수 파라미터 누락)을 쓰면 안 됩니다.
        // 이쪽은 게이트웨이를 거쳐 영구 오류 목록이 서로 다릅니다.
        given(new Response(200, topLevelError("10")));

        assertThatThrownBy(() -> client(2).fetchBasedList(1, 10, recordedAttempts::add))
                .isInstanceOf(PermanentSourceErrorException.class);

        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("요청을 내보낼 때마다 기록 통로를 부른다")
    void recordsEveryAttempt() {
        given(new Response(429, gatewayError("23", "초당 호출 초과")),
                new Response(429, gatewayError("23", "초당 호출 초과")),
                new Response(200, SUCCESS_BODY));

        client(2).fetchBasedList(1, 10, recordedAttempts::add);

        // 다시 시도하면 요청이 그만큼 더 나감
        // 한 번만 세면 기록이 실제로 나간 요청 수보다 작아짐
        assertThat(recordedAttempts).containsExactly(
                GoCampingApiClient.BASED_LIST_OPERATION,
                GoCampingApiClient.BASED_LIST_OPERATION,
                GoCampingApiClient.BASED_LIST_OPERATION);
    }

    @Test
    @DisplayName("항목이 빈 문자열로 와도 깨지지 않는다")
    void handlesEmptyItemsAsBlankString() {
        given(new Response(200, """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                 "body":{"items":"","numOfRows":0,"pageNo":1,"totalCount":0}}}"""));

        GoCampingListPage page = client(0).fetchBasedList(1, 10, recordedAttempts::add);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    private void given(Response... responses) {
        queued.addAll(List.of(responses));
    }

    private GoCampingApiClient client(int maxRetries) {
        IngestProperties properties = new IngestProperties(
                20, 0, maxRetries, 1, 5,
                new IngestProperties.PetTour("http://localhost", "test-only", 100, 0),
                new IngestProperties.GoCamping(baseUrl(), "test-only", 3200),
                new IngestProperties.Culture("build/tmp/test-culture.csv"));
        return new GoCampingApiClient(RestClient.builder(), new ObjectMapper(), properties);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 응답이 200 인데 실패인 형태입니다. 감싼 구조 없이 최상위에 코드가 옵니다.
     */
    private String topLevelError(String code) {
        return """
                {"responseTime":"2026-09-09T15:59:36.927","resultCode":"%s",
                 "resultMsg":"ERROR"}""".formatted(code);
    }

    /**
     * 인증 계층에서 튕길 때 나오는 형태입니다. 상태 코드도 사백대입니다.
     */
    private String gatewayError(String code, String message) {
        return """
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
                 "errMsg":"%s","returnReasonCode":"%s"}}}""".formatted(message, code);
    }

    private record Response(int status, String body) {
    }
}
