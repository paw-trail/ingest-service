package com.pawtrail.ingest.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * @param chunkSize      한 트랜잭션에 저장할 건수입니다.
 *                       *지켜야 할 규칙은 하나입니다.
 *                        진행 기록의 재개 지점은 그 청크에 담아 함께 커밋한 것의
 *                        마지막이어야 합니다.
 *                        앞서 나가면 아직 저장되지 않은 것을 처리한 것으로 기록해
 *                        이어받을 때 그 자리를 건너뜁니다.
 *                       이 값을 줄이면 도중에 죽었을 때 잃는 양이 줄어듭니다.
 *                       건당 호출이 있는 소스는 커밋되지 않은 호출이 허용량만 쓰고
 *                       데이터는 남기지 않으므로 그만큼이 손해입니다.
 *                       한 번에 다 받는 소스는 잃을 호출이 없어 트랜잭션 크기 문제일 뿐입니다.
 * @param callIntervalMs 호출 사이에 쉬는 시간입니다. 초당 제한을 피하려고 둡니다.
 * @param maxRetries     일시적인 실패에 다시 시도하는 횟수입니다.
 *                       호출 허용량 초과나 인증 오류에는 다시 시도하지 않습니다.
 * @param retryBackoffMs 다시 시도하기 전에 기다리는 시간입니다. 시도할 때마다 배로 늘어납니다.
 * @param maxConsecutiveFailures
 *                       한 건씩 건너뛰다가 몇 번 연달아 실패하면 멈출지입니다.
 *                       흩어진 실패는 소스 사정이라 그 건만 건너뛰고 계속하는 편이 낫습니다.
 *                       그러나 연달아 실패하면 소스가 멈췄거나 우리 요청이 잘못된 것이라,
 *                       계속 부르면 남은 허용량을 전부 헛되이 씁니다.
 * @param petTour        한국관광공사 반려동물 동반여행 서비스 접속 정보입니다.
 * @param goCamping      한국관광공사 고캠핑 정보 조회서비스 접속 정보입니다.
 * @param culture        한국문화정보원 문화시설 CSV 정보입니다. 바깥을 부르지 않고 파일을 읽습니다.
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

        @Positive(message = "app.ingest.max-consecutive-failures 는 양수여야 합니다")
        int maxConsecutiveFailures,

        // @NotNull 과 @Valid 를 함께 붙입니다.
        //
        // 앞엣것이 없으면 묶음이 통째로 빠졌을 때 이 값이 null 로 들어옵니다.
        // 그러면 기동은 되고 클라이언트를 만드는 자리에서 널 참조로 터지는데,
        // 그때는 무엇이 빠졌는지가 메시지에 안 나옵니다.
        //
        // 뒤엣것이 없으면 안쪽의 검증이 아예 돌지 않습니다.
        // 중첩된 값은 바깥에서 들여다보라고 표시해 주어야 검사됩니다.
        @NotNull(message = "app.ingest.pet-tour 설정이 필요합니다")
        @Valid
        PetTour petTour,

        @NotNull(message = "app.ingest.gocamping 설정이 필요합니다")
        @Valid
        GoCamping goCamping,

        @NotNull(message = "app.ingest.culture 설정이 필요합니다")
        @Valid
        Culture culture) {

    /**
     * @param baseUrl      서비스 경로입니다. 오퍼레이션 이름은 부르는 쪽이 붙입니다.
     * @param serviceKey   공공데이터포털 인증키입니다.
     *                     원본 그대로인 쪽을 넣습니다. 미리 인코딩된 쪽을 넣으면
     *                     전송 시점에 한 번 더 인코딩되어 거절당합니다.
     *                     기본값을 두지 않아 환경변수를 빠뜨리면 기동이 실패합니다.
     * @param listPageSize 목록을 한 번에 몇 건씩 받을지입니다.
     *                     저장할 건수와 따로 둡니다.
     *                     목록을 훑는 단계는 대상만 고르고 아무것도 저장하지 않으므로
     *                     청크 경계와 아무 관계가 없습니다.
     *                     이 값이 실행마다 쓰는 목록 호출 수를 정합니다.
     */
    public record PetTour(
            @NotBlank(message = "app.ingest.pet-tour.base-url 이 필요합니다")
            String baseUrl,

            @NotBlank(message = "INGEST_PUBLIC_DATA_SERVICE_KEY 환경변수가 필요합니다")
            String serviceKey,

            @Positive(message = "app.ingest.pet-tour.list-page-size 는 양수여야 합니다")
            int listPageSize) {
    }

    /**
     * @param baseUrl      서비스 경로입니다.
     *                     반려동물 동반여행 쪽과 달리 공공데이터포털 게이트웨이를 거칩니다.
     *                     그래서 오류 코드 목록이 서로 다릅니다.
     * @param serviceKey   공공데이터포털 인증키입니다.
     *                     지금은 반려동물 동반여행 쪽과 같은 환경변수를 봅니다.
     *                     포털이 계정마다 키를 하나만 주기 때문입니다.
     *                     나중에 소스별로 계정을 나누고 싶어지면 설정에서 갈라 주면 되고
     *                     코드는 그대로 둡니다.
     * @param listPageSize 목록을 한 번에 몇 건씩 받을지입니다.
     *                     이 소스는 상세 조회가 없어 이 한 번에 그날 필요한 것이 다 옵니다.
     *                     전체가 삼천백여 건이라 넉넉히 잡으면 한 번에 끝납니다.
     *                     2026년 9월 9일 실측에서 삼천이백으로 요청해 전량을 받았고
     *                     7.27메가바이트에 1.2초 걸렸습니다.
     */
    public record GoCamping(
            @NotBlank(message = "app.ingest.gocamping.base-url 이 필요합니다")
            String baseUrl,

            @NotBlank(message = "INGEST_PUBLIC_DATA_SERVICE_KEY 환경변수가 필요합니다")
            String serviceKey,

            @Positive(message = "app.ingest.gocamping.list-page-size 는 양수여야 합니다")
            int listPageSize) {
    }

    /**
     * @param filePath 읽을 CSV 파일의 경로입니다.
     *                 이 소스는 바깥을 부르지 않습니다. 저장소에 함께 커밋한 파일을 읽습니다.
     *                 접속 정보도 인증키도 없어 경로 하나뿐입니다.
     *
     *                 상대경로면 애플리케이션을 띄운 자리를 기준으로 찾습니다.
     *                 컨테이너에서는 마운트한 자리로 덮어쓰면 되고 코드는 그대로 둡니다.
     *
     *                 파일 이름에 날짜가 들어 있어 어느 판인지가 드러납니다.
     *                 새 판이 나오면 파일을 바꾸고 이 값의 날짜만 고칩니다.
     */
    public record Culture(
            @NotBlank(message = "app.ingest.culture.file-path 가 필요합니다")
            String filePath) {
    }
}
