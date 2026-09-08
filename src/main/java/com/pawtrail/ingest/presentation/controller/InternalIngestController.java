package com.pawtrail.ingest.presentation.controller;

import com.pawtrail.common.response.CommonApiResponse;
import com.pawtrail.ingest.application.dto.output.IngestRunStartedOutput;
import com.pawtrail.ingest.application.service.IngestExecutor;
import com.pawtrail.ingest.application.service.IngestTriggerService;
import com.pawtrail.ingest.presentation.request.IngestTriggerRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수집을 시작하는 경로입니다.
 *
 * 게이트웨이는 /internal 을 라우팅하지 않습니다.
 * 브라우저에서 부를 수 없고 같은 VPC 안에서만 닿습니다.
 * 이 서비스는 사람이 쓰는 화면이 아예 없어 공개 경로가 하나도 없습니다.
 *
 * 부르는 것은 Jenkins 잡입니다.
 * 감지는 스케줄로 하고 실행은 사람이 승인한다는 방침이라
 * 애플리케이션 안에 스케줄러를 두지 않았습니다.
 * 두면 개발 중에도 돌아 모르는 사이 쿼터가 소모됩니다.
 */
@Slf4j
@RestController
@RequestMapping("/internal/ingest")
@RequiredArgsConstructor
public class InternalIngestController {

    private final IngestTriggerService ingestTriggerService;
    private final IngestExecutor ingestExecutor;

    /**
     * 수집을 시작합니다.
     *
     * 곧바로 202 를 돌려주고 수집은 뒤에서 돕니다.
     * 한국관광공사 소스는 상세를 3,240회 부르는데 오퍼레이션마다 하루 1,000건이라
     * 완료까지 기다리게 할 수 없습니다.
     *
     * 두 서비스를 여기서 차례로 부르는 이유가 있습니다.
     * 앞엣것이 트랜잭션이고 뒤엣것이 비동기라, 한 서비스 안에서 이어 부르면
     * 아직 커밋되지 않은 실행 기록을 뒤에서 읽으려다 못 찾습니다.
     * 컨트롤러에는 트랜잭션이 없어 앞이 커밋된 뒤에 뒤가 시작됩니다.
     *
     * 진행 확인은 GET /internal/ingest/runs 가 맡습니다.
     */
    @PostMapping("/trigger")
    public ResponseEntity<CommonApiResponse<IngestRunStartedOutput>> trigger(
            @Valid @RequestBody IngestTriggerRequest request) {

        UUID runId = ingestTriggerService.startRun(request.source(), request.runType());
        ingestExecutor.execute(runId);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(CommonApiResponse.success(new IngestRunStartedOutput(runId)));
    }
}
