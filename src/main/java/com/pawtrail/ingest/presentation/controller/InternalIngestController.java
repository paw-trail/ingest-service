package com.pawtrail.ingest.presentation.controller;

import com.pawtrail.common.response.CommonApiResponse;
import com.pawtrail.ingest.application.dto.output.IngestRunStartedOutput;
import com.pawtrail.ingest.application.dto.output.IngestRunsOutput;
import com.pawtrail.ingest.application.dto.output.PendingDocumentsOutput;
import com.pawtrail.ingest.application.dto.output.PlaceDocumentsOutput;
import com.pawtrail.ingest.application.dto.output.StatusUpdateOutput;
import com.pawtrail.ingest.application.service.IngestExecutor;
import com.pawtrail.ingest.application.service.PlaceLinkExecutor;
import com.pawtrail.ingest.application.service.IngestQueryService;
import com.pawtrail.ingest.application.service.IngestTriggerService;
import com.pawtrail.ingest.application.service.RawDocumentStatusService;
import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.presentation.request.IngestTriggerRequest;
import com.pawtrail.ingest.presentation.request.RawDocumentStatusRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수집을 시작하고 담아 둔 것을 꺼내는 경로입니다.
 *
 * 게이트웨이는 /internal 을 라우팅하지 않습니다.
 * 브라우저에서 부를 수 없고 같은 VPC 안에서만 닿습니다.
 * 이 서비스는 사람이 쓰는 화면이 아예 없어 공개 경로가 하나도 없습니다.
 *
 * 부르는 것이 셋입니다.
 * 수집 시작은 Jenkins 잡이, 문서 조회와 상태 갱신은 extract 가,
 * 실행 이력은 관리자 화면이 부릅니다.
 *
 * 보안 설정을 따로 두지 않습니다.
 * 공통 모듈이 /internal 을 인증 없이 열어 두고 실질적인 방어는
 * 게이트웨이와 보안그룹이 맡는다고 정해 두었습니다.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalIngestController {

    private static final int DEFAULT_DOCUMENT_SIZE = 100;
    private static final int DEFAULT_RUN_SIZE = 20;

    private final IngestTriggerService ingestTriggerService;
    private final IngestExecutor ingestExecutor;
    private final PlaceLinkExecutor placeLinkExecutor;
    private final IngestQueryService ingestQueryService;
    private final RawDocumentStatusService rawDocumentStatusService;

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
     */
    @PostMapping("/ingest/trigger")
    public ResponseEntity<CommonApiResponse<IngestRunStartedOutput>> trigger(
            @Valid @RequestBody IngestTriggerRequest request) {

        UUID runId = ingestTriggerService.startRun(request.source(), request.runType());

        // 실행 종류에 따라 이어받는 곳이 다름
        //
        // 앞의 둘은 바깥에서 받아 우리 표에 쌓고 마지막 하나는 쌓인 것을 다른 서비스에 보냄
        // 하는 일이 달라 실행기를 나눴고, 트리거와 실행 기록은 같은 것을 씀
        if (request.runType() == RunType.LINK) {
            placeLinkExecutor.execute(runId);
        } else {
            ingestExecutor.execute(runId);
        }

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(CommonApiResponse.success(new IngestRunStartedOutput(runId)));
    }

    /**
     * 최근 수집 실행을 새것부터 돌려줍니다.
     *
     * 감지는 스케줄이 하고 실행은 사람이 승인한다는 방침이라,
     * 사람이 바뀐 건수와 진행 상태를 보고 다음 실행을 부를지 정합니다.
     *
     * 쪽 번호를 받지 않습니다.
     * 실행은 하루에 몇 건씩 쌓이므로 한 해가 지나도 수백 건입니다.
     *
     * @param source 이 소스만 봅니다. 안 주면 전부 봅니다.
     *               소스가 넷이라 섞이면 스무 건을 받아 세 건만 남는 일이 생깁니다
     */
    @GetMapping("/ingest/runs")
    public ResponseEntity<CommonApiResponse<IngestRunsOutput>> findRuns(
            @RequestParam(required = false) SourceType source,
            @RequestParam(defaultValue = "" + DEFAULT_RUN_SIZE) int size) {

        return ResponseEntity.ok(
                CommonApiResponse.success(ingestQueryService.findRecentRuns(source, size)));
    }

    /**
     * 그 상태의 원본 문서를 오래된 것부터 돌려줍니다.
     *
     * extract 가 처리 대상을 가져가는 경로입니다.
     * 원본이 나가는 곳은 여기 하나입니다. 실행 이력에는 원본이 들어가지 않습니다.
     *
     * 쪽 번호를 받지 않습니다.
     * 처리하면 그 문서가 대기 목록에서 빠지므로, 쪽 번호로 넘기면
     * 뒤에 있던 것이 앞으로 밀려와 그만큼을 조용히 건너뜁니다.
     * 언제나 가장 오래된 것부터 주고, 처리한 만큼 다음이 올라옵니다.
     */
    @GetMapping("/raw")
    public ResponseEntity<CommonApiResponse<PendingDocumentsOutput>> findDocuments(
            @RequestParam DocumentStatus status,
            @RequestParam(defaultValue = "" + DEFAULT_DOCUMENT_SIZE) int size) {

        return ResponseEntity.ok(
                CommonApiResponse.success(ingestQueryService.findDocuments(status, size)));
    }

    /**
     * 처리 결과를 되돌려 씁니다.
     *
     * 자원 하나를 고치는 것이 아니라 처리 결과를 보고하는 것에 가까워
     * 경로에 식별자가 없고 본문에 목록이 들어갑니다.
     * REST 결에서 조금 벗어나지만 /internal 은 우리 서비스끼리 쓰는 자리라
     * 밖에 드러나는 계약이 아닙니다.
     *
     * 바꾸는 것은 처리 상태 하나뿐입니다. 원본과 표시용 본문은 이 경로로 바뀌지 않습니다.
     */
    @PatchMapping("/raw/status")
    public ResponseEntity<CommonApiResponse<StatusUpdateOutput>> updateStatus(
            @Valid @RequestBody RawDocumentStatusRequest request) {

        return ResponseEntity.ok(CommonApiResponse.success(
                rawDocumentStatusService.apply(request.done(), request.failed())));
    }

    /**
     * 그 장소가 어느 원본에서 왔는지를 돌려줍니다.
     *
     * 장소 서비스의 「근거 원문 전체 보기」가 씁니다.
     *
     * 사람이 읽는 문장만 담습니다.
     * 소스 응답 원본이 필요하면 위 목록 조회를 쓰는데 그쪽은 처리 배치가 쓰는 자리입니다.
     *
     * 표시 이름을 담지 않습니다. 부르는 쪽이 이미 가지고 있습니다.
     *
     * 문서가 없어도 200 입니다.
     * 이 서비스는 그 식별자가 실제로 있는 장소인지 알 방법이 없고,
     * 원본을 거치지 않는 소스로만 만들어진 장소는 원문이 아예 없습니다.
     */
    @GetMapping("/raw/{placeId}/documents")
    public ResponseEntity<CommonApiResponse<PlaceDocumentsOutput>> getPlaceDocuments(
            @PathVariable UUID placeId) {

        return ResponseEntity.ok(
                CommonApiResponse.success(ingestQueryService.getPlaceDocuments(placeId)));
    }
}
