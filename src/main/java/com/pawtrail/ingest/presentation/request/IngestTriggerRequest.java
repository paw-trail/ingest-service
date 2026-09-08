package com.pawtrail.ingest.presentation.request;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import jakarta.validation.constraints.NotNull;

/**
 * 수집을 시작해 달라는 요청입니다.
 *
 * Jenkins 잡이 부릅니다.
 * 사람이 브라우저에서 부르는 경로가 아니라 게이트웨이 라우트를 두지 않았습니다.
 *
 * @param source  수집할 소스
 * @param runType 전량인지 증분인지.
 *                증분은 소스가 알려준 수정 시각이 우리가 가진 값보다 새로운 것만 받음.
 *                상세 호출에 쿼터가 걸려 있어 이 구분에 실질적인 의미가 있음
 */
public record IngestTriggerRequest(
        @NotNull(message = "source 는 필수입니다.") SourceType source,
        @NotNull(message = "runType 은 필수입니다.") RunType runType) {
}
