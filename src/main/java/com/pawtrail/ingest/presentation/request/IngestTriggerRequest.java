package com.pawtrail.ingest.presentation.request;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import jakarta.validation.constraints.NotNull;

/**
 * 수집을 시작해 달라는 요청입니다.
 *
 * 두 입구가 같은 모양으로 받습니다.
 * /internal 트리거는 사람이 전량 · 넘기기 · 바로 보내기를 걸 때 쓰고,
 * 관리자 입구는 관리자 화면이 두 조합(반려동물 동반여행 증분 · 고캠핑 목록)만 걸 때 씁니다.
 * 어느 조합을 받을지는 입구가 정합니다. 관리자 쪽 규칙은 IngestRunLauncher 에 있습니다.
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
