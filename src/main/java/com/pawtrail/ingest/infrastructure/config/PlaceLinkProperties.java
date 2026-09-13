package com.pawtrail.ingest.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 받아 둔 것을 장소 서비스로 넘길 때 쓰는 값입니다. 설정 저장소의 ingest-service.yml 에서 내려옵니다.
 *
 * 수집 설정과 나눈 이유가 둘입니다.
 *
 * 수집은 바깥을 부르는 단계라 호출 허용량과 초당 제한이 값을 정하는 기준입니다.
 * 이 단계는 우리 표를 읽어 다른 서비스에 보낼 뿐이라 그 기준이 하나도 걸리지 않습니다.
 * 수집 쪽 청크 크기 주석이 "진행 기록의 재개 지점" 을 설명하는데
 * 이 단계는 재개 지점을 남기지 않아 그 설명이 절반만 맞는 말이 됩니다.
 *
 * 그리고 한 레코드에 값을 더하면 그것을 만드는 검사가 전부 깨집니다.
 * 소스를 붙일 때마다 여섯 곳을 함께 고쳐 왔고 한 번은 두 곳을 빠뜨려 다시 돌았습니다.
 *
 * @param chunkSize 한 번에 장소 서비스로 보낼 건수입니다.
 *                  받는 쪽 상한이 1,000 이라 그보다 크면 요청이 통째로 거절됩니다.
 *                  *수집 쪽 청크와 기준이 다릅니다.
 *                   그쪽은 도중에 죽었을 때 잃는 호출을 줄이려고 작게 잡았지만,
 *                   여기는 잃을 호출이 없고 끊기면 처음부터 다시 하므로
 *                   왕복 횟수를 줄이는 쪽이 낫습니다.
 * @param readTimeoutSeconds
 *                  받는 쪽의 응답을 기다리는 시간입니다.
 *                  공통 설정의 읽기 제한이 5초인데 이 호출에는 턱없이 부족합니다.
 *                  천 건을 받아 정규화하고 병합까지 하는 요청이라 십몇 초가 걸립니다.
 *                  *그 값을 늘리지 않고 여기에 따로 두는 이유가 있습니다.
 *                   전역 값을 늘리면 나중에 생길 다른 서비스 호출까지 함께 느슨해집니다.
 *                   조회 한 번이 5초 안에 안 오면 실패시키는 것이 맞는데 그것까지 늘어납니다.
 *                  연결 제한은 늘리지 않습니다.
 *                  상대가 떠 있지 않으면 기다릴 이유가 없고 그 판단은 금방 납니다.
 */
@Validated
@ConfigurationProperties(prefix = "app.ingest.link")
public record PlaceLinkProperties(

        @Positive(message = "app.ingest.link.chunk-size 는 양수여야 합니다")
        @Max(value = 1000, message = "app.ingest.link.chunk-size 는 1000 이하여야 합니다")
        int chunkSize,

        @Positive(message = "app.ingest.link.read-timeout-seconds 는 양수여야 합니다")
        long readTimeoutSeconds) {
}
