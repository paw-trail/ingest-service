package com.pawtrail.ingest.infrastructure.provider.internal;

import com.pawtrail.common.response.CommonApiResponse;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.provider.PlaceLinkClient;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import com.pawtrail.ingest.domain.provider.dto.PlaceLinkResult;
import com.pawtrail.ingest.infrastructure.config.PlaceLinkProperties;
import com.pawtrail.common.exception.CustomException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 도메인이 선언한 약속을 장소 서비스 호출로 구현합니다.
 *
 * internal 아래에 두는 것은 우리가 만든 다른 서비스이기 때문입니다.
 * external 은 공공데이터포털처럼 바깥 시스템을 부르는 자리입니다.
 *
 * 이 서비스가 다른 서비스를 부르는 첫 자리입니다.
 * 지금까지의 클라이언트는 전부 바깥 API 라 주소를 직접 적고 재시도를 손으로 짰습니다.
 */
@Slf4j
@Component
public class PlaceLinkClientImpl implements PlaceLinkClient {

    private static final String BASE_URL = "lb://place-service";

    // 연결을 맺기까지 기다리는 시간임
    //
    // 공통 설정과 같은 값임
    // 요청 팩터리를 갈아 끼우면 그쪽 값이 통째로 날아가 여기서 다시 세워야 함
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;

    /**
     * 빌더를 주입받아 RestClient 를 만듭니다.
     *
     * RestClient.builder() 를 직접 부르지 않습니다.
     * 그러면 인증 헤더도 lb:// 해석도 시간 제한도 붙지 않습니다.
     * 공통 모듈이 그 셋을 미리 걸어 둔 빌더를 내어 줍니다.
     *
     * Qualifier 를 반드시 붙여야 합니다.
     * 같은 타입의 빈이 셋이고 그중 하나가 기본으로 지정되어 있습니다.
     * 빠뜨리면 아무것도 얹히지 않은 그 빌더가 조용히 주입되어
     * lb:// 를 풀지 못하고 기동이 아니라 호출하는 순간에 실패합니다.
     *
     * 롬복의 생성자 애노테이션을 쓰지 않는 것도 그 때문입니다.
     * 그것이 만드는 생성자에는 한정자가 붙지 않습니다.
     */
    public PlaceLinkClientImpl(
            @Qualifier("internalRestClientBuilder") RestClient.Builder builder,
            PlaceLinkProperties properties) {

        this.restClient = builder
                .baseUrl(BASE_URL)
                .requestFactory(timeoutFactory(properties.readTimeoutSeconds()))
                .build();
    }

    /**
     * 돌려받은 짝이 약속대로인지 봅니다.
     *
     * 어긋나면 그 자리에서 멈춥니다. 고쳐서 쓰지 않습니다.
     * 한 건이 약속과 다르다는 것은 응답 전체를 믿을 수 없다는 뜻이고,
     * 이 값으로 우리 표의 장소 식별자를 채우기 때문에 잘못 채우면 되돌릴 방법이 없습니다.
     *
     * 세 가지를 봅니다.
     *
     * 장소 식별자가 비어 있으면 안 됩니다.
     * 그대로 채우면 이미 이어 둔 연결을 지우게 되는데 오류가 나지 않아 드러나지 않습니다.
     * 장소를 만들지 못한 레코드는 짝 자체가 오지 않기로 되어 있습니다.
     *
     * 보낸 적 없는 것이 오면 안 됩니다.
     * 소스까지 함께 봅니다. 소스 식별자만 보면 다른 데이터셋의 같은 번호를 우리 것으로 착각합니다.
     *
     * 같은 짝이 두 번 오면 안 됩니다.
     * 그러면 채운 건수가 부풀려져 실행 기록의 숫자를 믿을 수 없게 됩니다.
     */
    private void verify(List<PlaceBulkItem> items, PlaceLinkResult result) {
        Set<String> sent = items.stream()
                .map(item -> key(item.source(), item.sourceId()))
                .collect(Collectors.toSet());

        Set<String> seen = new HashSet<>();
        for (PlaceLinkResult.SourceLink link : result.links()) {
            if (link.placeId() == null) {
                reject("장소 식별자가 비어 있습니다", link);
            }
            String key = key(link.source(), link.sourceId());
            if (!sent.contains(key)) {
                reject("보낸 적 없는 레코드입니다", link);
            }
            if (!seen.add(key)) {
                reject("같은 레코드가 두 번 왔습니다", link);
            }
        }
    }

    private void reject(String reason, PlaceLinkResult.SourceLink link) {
        log.error("장소 서비스 응답이 약속과 다릅니다: {} source={} sourceId={} placeId={}",
                reason, link.source(), link.sourceId(), link.placeId());
        throw new CustomException(IngestErrorCode.PLACE_LINK_FAILED);
    }

    // 소스와 소스 식별자를 한 열쇠로 묶음
    //
    // 소스 식별자만으로는 데이터셋이 다른 같은 번호를 가를 수 없음
    private static String key(Object source, String sourceId) {
        return source + "\u0000" + sourceId;
    }

    /**
     * 이 호출만 쓰는 시간 제한을 만듭니다.
     *
     * 읽기만 늘리고 연결은 공통 값을 그대로 씁니다.
     * 상대가 떠 있지 않으면 기다릴 이유가 없고 그 판단은 금방 납니다.
     * 반대로 응답은 천 건을 받아 정규화하고 병합까지 하는 동안 기다려야 합니다.
     */
    private static SimpleClientHttpRequestFactory timeoutFactory(long readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }

    /**
     * 한 묶음을 보냅니다.
     *
     * 실패를 값으로 돌려주지 않고 예외를 던집니다.
     *
     * 수집기가 바깥 소스를 부를 때는 실패한 건만 건너뛰고 계속하는 것이 맞습니다.
     * 소스가 건마다 다른 응답을 주기 때문입니다.
     * 여기는 상대가 한 서비스라 한 묶음이 실패하면 다음 묶음도 같은 이유로 실패합니다.
     * 계속 보내면 같은 오류를 쌓기만 하고 끝난 뒤에도 무엇이 들어갔는지 알 수 없습니다.
     *
     * 잡는 범위를 넓게 둡니다.
     * 연결 거부, 시간 초과, 서비스를 못 찾는 것, 응답 형태가 다른 것까지
     * 우리가 할 일이 같습니다. 멈추고 실행을 실패로 마감하는 것입니다.
     */
    @Override
    public PlaceLinkResult send(List<PlaceBulkItem> items) {
        try {
            CommonApiResponse<PlaceLinkResult> response = restClient.post()
                    .uri("/internal/places/bulk")
                    .body(Map.of("items", items))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || response.getData() == null) {
                log.error("장소 서비스 응답이 비어 있습니다: {}건", items.size());
                throw new CustomException(IngestErrorCode.PLACE_LINK_FAILED);
            }

            PlaceLinkResult result = response.getData();
            verify(items, result);
            return result;

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("장소 서비스에 넘기지 못했습니다: {}건, reason={}",
                    items.size(), e.getMessage());
            throw new CustomException(IngestErrorCode.PLACE_LINK_FAILED);
        }
    }
}
