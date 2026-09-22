package com.pawtrail.ingest.presentation.controller;

import com.pawtrail.common.response.CommonApiResponse;
import com.pawtrail.ingest.application.dto.output.IngestRunStartedOutput;
import com.pawtrail.ingest.application.dto.output.IngestRunsOutput;
import com.pawtrail.ingest.application.service.IngestQueryService;
import com.pawtrail.ingest.application.service.IngestRunLauncher;
import com.pawtrail.ingest.presentation.request.IngestTriggerRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 화면 「운영」 의 「공사 데이터 최신 수집」 카드가 부르는 입구입니다.
 *
 * 한국관광공사 OpenAPI 를 그 자리에서 불러 바뀐 원문을 가져옵니다.
 * 수집은 원문 적재에서 끝나고 장소 연결 · 조건 추출로 이어지지 않습니다.
 *
 * ADMIN 만 부를 수 있습니다.
 * common 의 보안 설정이 /api/v1/admin/** 을 막아 두어 여기서 따로 확인하지 않습니다.
 * search 의 관리자 재색인 입구와 같습니다.
 *
 * 요청 · 응답 모양은 /internal 과 같습니다. 프론트가 그 모양으로 부릅니다.
 * 다른 것은 거치는 규칙입니다. 조합 · 10분 잠금 · 실행 중 순서로 막으며 IngestRunLauncher 에 있습니다.
 */
@RestController
@RequestMapping("/api/v1/admin/ingest")
@RequiredArgsConstructor
public class AdminIngestController {

    private static final int DEFAULT_RUN_SIZE = 20;

    private final IngestRunLauncher ingestRunLauncher;
    private final IngestQueryService ingestQueryService;

    /**
     * 수집을 겁니다. 곧바로 202 를 돌려주고 수집은 뒤에서 돕니다.
     *
     * 받는 조합은 둘뿐입니다 — 반려동물 동반여행 INCREMENTAL · 고캠핑 FULL.
     * 막히면 400 INGEST_RUN_NOT_ALLOWED · 429 INGEST_COOLDOWN · 409 INGEST_ALREADY_RUNNING 입니다.
     */
    @PostMapping("/runs")
    public ResponseEntity<CommonApiResponse<IngestRunStartedOutput>> run(
            @Valid @RequestBody IngestTriggerRequest request) {

        UUID runId = ingestRunLauncher.launch(request.source(), request.runType());
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(CommonApiResponse.success(new IngestRunStartedOutput(runId)));
    }

    /**
     * 공사 API 를 부른 수집 기록을 새것부터 돌려줍니다.
     *
     * 반려동물 동반여행 · 고캠핑의 받아 오기만 보입니다. 누가 걸었든 함께 보입니다.
     * 쪽 번호를 받지 않습니다. 화면은 최근 열 건을 봅니다.
     */
    @GetMapping("/runs")
    public ResponseEntity<CommonApiResponse<IngestRunsOutput>> findRuns(
            @RequestParam(defaultValue = "" + DEFAULT_RUN_SIZE) int size) {

        return ResponseEntity.ok(
                CommonApiResponse.success(ingestQueryService.findRecentCollectRuns(size)));
    }
}
