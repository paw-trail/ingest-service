package com.pawtrail.ingest.application.dto.output;

import java.util.List;

/**
 * 최근 수집 실행 목록입니다. 새것부터 옵니다.
 *
 * 쪽 번호를 담지 않습니다.
 * 실행은 하루에 몇 건씩 쌓이므로 한 해가 지나도 수백 건이고,
 * 지금은 최근 몇 건만 보면 충분합니다.
 */
public record IngestRunsOutput(List<IngestRunOutput> runs) {
}
