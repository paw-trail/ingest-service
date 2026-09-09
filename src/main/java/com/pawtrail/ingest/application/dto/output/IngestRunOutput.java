package com.pawtrail.ingest.application.dto.output;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.OperationProgress;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 수집 실행 하나의 기록입니다.
 *
 * 사람이 승인을 판단하는 화면이 씁니다.
 * 감지는 스케줄이 하고 실행은 사람이 승인한다는 방침이라,
 * 바뀐 건수와 진행 상태를 보고 다음 실행을 부를지 정합니다.
 *
 * @param progress     오퍼레이션별 호출 수와 재개 지점.
 *                     허용량을 얼마나 썼고 어디까지 처리했는지가 여기 있습니다.
 *                     파일을 읽는 소스는 부를 오퍼레이션이 없어 비어 있습니다
 * @param errorMessage 사람이 봐야 할 문구.
 *                     이름은 오류 메시지이지만 오류가 아닌 것도 담습니다.
 *                     건너뛴 항목 목록이 그렇고 허용량으로 멈춘 것도 실패가 아닙니다
 */
public record IngestRunOutput(
        UUID id,
        SourceType source,
        RunType runType,
        RunStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        int fetchedCount,
        int changedCount,
        Map<String, OperationProgress> progress,
        String errorMessage) {
}
