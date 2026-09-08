package com.pawtrail.ingest.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 수집 동작을 조절하는 값입니다. 설정 저장소의 ingest-service.yml 에서 내려옵니다.
 *
 * 코드에 상수로 박지 않는 이유는 대부분 실측으로 정할 값이기 때문입니다.
 * 공공데이터포털에 초당 호출 제한이 따로 있는데 초당 몇 건인지가 문서에 없습니다.
 * 실제로 불러 보며 맞춰야 하는데 그때마다 다시 빌드하고 싶지 않습니다.
 *
 * @param chunkSize      한 트랜잭션에 저장할 건수이자 한 번에 받아 올 목록 크기입니다.
 *                       둘을 같은 값으로 두는 것이 중요합니다.
 *                       진행 기록은 목록을 한 번 받을 때마다 남고 저장은 청크마다 일어나는데,
 *                       크기가 어긋나면 한 번 받아 온 것이 여러 청크에 걸칩니다.
 *                       그러면 아직 저장되지 않은 항목까지 처리한 것으로 기록되어
 *                       이어받을 때 그 자리를 건너뜁니다.
 * @param callIntervalMs 호출 사이에 쉬는 시간입니다. 초당 제한을 피하려고 둡니다.
 *                       늘리면 청크가 도는 시간이 길어져 도중에 죽었을 때 잃는 양도 커집니다.
 * @param maxRetries     일시적인 실패에 다시 시도하는 횟수입니다.
 *                       호출 허용량 초과나 인증 오류에는 다시 시도하지 않습니다.
 *                       고쳐야 나아지는 것이라 다시 불러도 결과가 같고 허용량만 씁니다.
 * @param retryBackoffMs 다시 시도하기 전에 기다리는 시간입니다. 시도할 때마다 배로 늘어납니다.
 * @param petTour        한국관광공사 반려동물 동반여행 서비스 접속 정보입니다.
 */
@Validated
@ConfigurationProperties(prefix = "app.ingest")
public record IngestProperties(

        @Positive(message = "app.ingest.chunk-size 는 양수여야 합니다")
        int chunkSize,

        @Min(value = 0, message = "app.ingest.call-interval-ms 는 0 이상이어야 합니다")
        long callIntervalMs,

        @Min(value = 0, message = "app.ingest.max-retries 는 0 이상이어야 합니다")
        int maxRetries,

        @Positive(message = "app.ingest.retry-backoff-ms 는 양수여야 합니다")
        long retryBackoffMs,

        PetTour petTour) {

    /**
     * @param baseUrl    서비스 경로입니다. 오퍼레이션 이름은 부르는 쪽이 붙입니다.
     * @param serviceKey 공공데이터포털 인증키입니다.
     *                   원본 그대로인 쪽을 넣습니다. 미리 인코딩된 쪽을 넣으면
     *                   전송 시점에 한 번 더 인코딩되어 더하기 기호가 이중으로 바뀌고 거절당합니다.
     *                   기본값을 두지 않아 환경변수를 빠뜨리면 기동이 실패합니다.
     *                   기본값이 있으면 빠뜨린 채로 떠서 호출이 다 실패한 뒤에야 드러납니다.
     */
    public record PetTour(
            @NotBlank(message = "app.ingest.pet-tour.base-url 이 필요합니다")
            String baseUrl,

            @NotBlank(message = "INGEST_PET_TOUR_SERVICE_KEY 환경변수가 필요합니다")
            String serviceKey) {
    }
}
