package com.pawtrail.ingest.application.service;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 관리자 화면의 「최신 수집 실행」 과 매일 예약이 함께 쓰는 실행 걸기입니다.
 *
 * 둘 다 한국관광공사 OpenAPI 를 그 자리에서 부르는 입구라 같은 규칙을 지킵니다.
 * 규칙을 이 클래스 한 곳에 두어 두 입구가 어긋날 자리를 없앱니다.
 *
 * <pre>
 * 1  조합       공사 API 소스를 평소 방법으로만 — 반려동물 동반여행 증분 · 고캠핑 목록   400
 * 2  10분 잠금  같은 소스의 받아 오기가 끝난 지 잠금 시간이 안 됐으면                  429
 * 3  실행 중    같은 소스가 돌고 있으면 — 트리거 서비스가 봄                          409
 * </pre>
 *
 * 순서가 중요합니다.
 * 안 되는 조합에 「잠시 후 다시」 가 나가면 기다려도 안 된다는 것을 알 수 없습니다.
 * 10분 잠금을 실행 중 검사보다 먼저 보아도 결과는 어긋나지 않습니다.
 * 도는 실행은 끝난 시각이 없어 10분 잠금에 잡히지 않고 그대로 3 에서 막힙니다.
 *
 * /internal 트리거는 이 규칙을 거치지 않습니다.
 * 사람이 전량을 다시 받거나 넘기기를 부르는 자리이고, 게이트웨이가 그 경로를 라우팅하지 않습니다.
 *
 * 트랜잭션을 두지 않습니다.
 * 실행을 만드는 트리거 서비스가 커밋한 뒤에 실행기를 불러야 합니다.
 * 한 트랜잭션 안에서 이어 부르면 실행기가 아직 커밋되지 않은 기록을 식별자로 못 찾습니다.
 * /internal 컨트롤러가 둘을 차례로 부르는 것과 같은 이유입니다.
 */
@Slf4j
@Service
public class IngestRunLauncher {

    private final IngestTriggerService ingestTriggerService;
    private final IngestExecutor ingestExecutor;
    private final IngestRunRepository ingestRunRepository;
    private final Duration cooldown;

    /**
     * @param cooldown 같은 소스의 받아 오기가 끝난 뒤 다시 걸 수 없는 시간입니다.
     *                 config 의 app.ingest.cooldown 이고 없으면 10분입니다.
     *                 search 가 재색인 잠금 만료를 같은 모양(값 주입과 기본값)으로 받습니다
     */
    public IngestRunLauncher(
            IngestTriggerService ingestTriggerService,
            IngestExecutor ingestExecutor,
            IngestRunRepository ingestRunRepository,
            @Value("${app.ingest.cooldown:10m}") Duration cooldown) {

        if (cooldown.isNegative()) {
            // 음수면 잠금이 조용히 풀려 누를 때마다 돎 — 기동 때 드러나게 함
            throw new IllegalStateException("app.ingest.cooldown 은 0 이상이어야 합니다: " + cooldown);
        }
        this.ingestTriggerService = ingestTriggerService;
        this.ingestExecutor = ingestExecutor;
        this.ingestRunRepository = ingestRunRepository;
        this.cooldown = cooldown;
    }

    /**
     * 관리자 입구가 부릅니다. 규칙에 걸리면 그 예외를 그대로 올립니다.
     *
     * 막히는 응답은 셋입니다.
     * 안 되는 조합은 400 INGEST_RUN_NOT_ALLOWED, 잠금 시간 안이면 429 INGEST_COOLDOWN,
     * 같은 소스가 돌고 있으면 409 INGEST_ALREADY_RUNNING 입니다.
     *
     * @return 만든 실행의 식별자. 수집은 뒤에서 돕니다
     */
    public UUID launch(SourceType source, RunType runType) {
        if (!isAllowed(source, runType)) {
            throw new CustomException(IngestErrorCode.INGEST_RUN_NOT_ALLOWED);
        }
        if (isCoolingDown(source)) {
            throw new CustomException(IngestErrorCode.INGEST_COOLDOWN);
        }

        UUID runId = ingestTriggerService.startRun(source, runType);
        ingestExecutor.execute(runId);
        return runId;
    }

    /**
     * 매일 예약이 부릅니다. 소스마다 평소 방법을 골라 겁니다.
     *
     * 잠금에 걸리면 건너뛰고 로그 한 줄만 남깁니다. 예외를 올리지 않습니다.
     * 사람이 방금 눌렀거나 아직 돌고 있다는 뜻이라 그날 호출 이력은 이미 남았습니다.
     * search 의 매일 재색인이 잠금을 못 잡았을 때 건너뛰는 것과 같습니다.
     *
     * 그 밖의 실패는 그대로 올립니다.
     * 수집기가 없는 것처럼 고쳐야 나아지는 일이라 조용히 넘기면 안 됩니다.
     *
     * @return 건 실행의 식별자. 건너뛰었으면 비어 있음
     */
    public Optional<UUID> launchScheduled(SourceType source) {
        RunType runType = routineRunType(source);
        try {
            UUID runId = launch(source, runType);
            log.info("예약 수집을 걸었습니다. runId={} source={} runType={}", runId, source, runType);
            return Optional.of(runId);

        } catch (CustomException e) {
            if (e.getErrorCode() == IngestErrorCode.INGEST_COOLDOWN
                    || e.getErrorCode() == IngestErrorCode.INGEST_ALREADY_RUNNING) {
                log.info("예약 수집을 건너뜁니다. source={} 까닭={}", source, e.getErrorCode().getCode());
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * 그 소스를 평소에 받는 방법입니다. 증분이 되면 증분, 아니면 전량입니다.
     *
     * 반려동물 동반여행은 상세를 장소마다 세 번 불러 전량이 3천 회를 넘으므로 증분만 겁니다.
     * 고캠핑은 목록 한 번에 전량이 와서 전량이 곧 가장 적게 부르는 방법입니다.
     */
    static RunType routineRunType(SourceType source) {
        return source.supportsIncremental() ? RunType.INCREMENTAL : RunType.FULL;
    }

    private boolean isAllowed(SourceType source, RunType runType) {
        return source.isTourApi() && runType == routineRunType(source);
    }

    /**
     * 같은 소스의 받아 오기가 잠금 시간 안에 끝났는지 봅니다.
     *
     * 누가 걸었든 셉니다. 허용량은 관리자 · 예약 · /internal 어느 쪽이 불러도 같이 줄어듭니다.
     */
    private boolean isCoolingDown(SourceType source) {
        return ingestRunRepository.existsCollectFinishedSince(source, LocalDateTime.now().minus(cooldown));
    }
}
