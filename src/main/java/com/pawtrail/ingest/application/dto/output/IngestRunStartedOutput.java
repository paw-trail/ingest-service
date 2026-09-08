package com.pawtrail.ingest.application.dto.output;

import java.util.UUID;

/**
 * 수집을 시작했다는 응답입니다.
 *
 * 식별자만 돌려줍니다.
 * 수집은 비동기라 응답을 만드는 시점에는 아직 아무것도 진행되지 않았고,
 * 진행 요약을 실어도 전부 0 입니다.
 *
 * 동기로 두지 않은 이유는 한국관광공사 소스가 이틀 걸리기 때문입니다.
 * 상세를 3,240회 부르는데 오퍼레이션마다 하루 1,000건이라
 * HTTP 응답을 기다리게 할 수 없습니다.
 *
 * 진행 확인은 GET /internal/ingest/runs 가 맡습니다.
 */
public record IngestRunStartedOutput(UUID runId) {
}
