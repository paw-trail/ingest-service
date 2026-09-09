package com.pawtrail.ingest.application.service;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.CollectionInterruptedException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.SourceCollector;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 수집을 실제로 돌립니다.
 *
 * 트리거 서비스와 갈라 둔 이유는 스프링의 비동기 프록시가
 * 같은 객체 안의 호출에는 걸리지 않기 때문입니다.
 * 한 클래스에 두면 조용히 같은 스레드에서 돌아 트리거 응답이 수집이 끝날 때까지 막힙니다.
 * 한국관광공사 소스는 이틀 걸립니다.
 *
 * 비동기 자체는 공통 모듈이 켭니다.
 * CommonAsyncAutoConfiguration 이 @EnableAsync 를 달고 있고 실행자는 따로 만들지 않습니다.
 * Boot 의 applicationTaskExecutor 를 그대로 쓰며
 * 필요하면 spring.task.execution.pool.* 로 서비스마다 조절합니다.
 *
 * 상태 전이는 여기서만 일어납니다.
 * 수집기에는 실행 엔티티를 넘기지 않고 진행을 기록하는 통로만 넘깁니다.
 *
 * 이 메서드에는 트랜잭션이 없습니다.
 * 수집이 오래 도는 작업이라 한 트랜잭션으로 묶으면 커넥션을 그동안 쥐고 있게 되고,
 * 하나가 실패하면 그때까지 받은 것이 통째로 사라집니다.
 * 저장은 청크마다 별도 트랜잭션으로 일어납니다.
 */
@Slf4j
@Service
public class IngestExecutor {

    private final IngestRunRepository ingestRunRepository;
    private final ChunkWriter chunkWriter;

    // 증분이 서 있는 전제를 표본으로 확인합니다.
    // 전량 수집이거나 표본 크기가 0 이면 아무것도 하지 않습니다
    private final PetTourSampleVerifier sampleVerifier;
    private final Map<SourceType, SourceCollector> collectors;

    public IngestExecutor(
            IngestRunRepository ingestRunRepository,
            ChunkWriter chunkWriter,
            PetTourSampleVerifier sampleVerifier,
            List<SourceCollector> sourceCollectors) {

        this.ingestRunRepository = ingestRunRepository;
        this.chunkWriter = chunkWriter;
        this.sampleVerifier = sampleVerifier;
        this.collectors = new EnumMap<>(SourceType.class);
        sourceCollectors.forEach(collector -> {
            SourceCollector previous = this.collectors.put(collector.source(), collector);
            if (previous != null) {
                // 소스 하나에 수집기가 둘이면 어느 쪽이 도는지가 빈 등록 순서에 달림
                // 기동 시점에 터뜨려 배포 전에 드러나게 함
                throw new IllegalStateException(
                        "한 소스에 수집기가 둘입니다: " + collector.source());
            }
        });
    }

    /**
     * 실행 하나를 처음부터 끝까지 돌립니다.
     *
     * 트리거 서비스가 실행을 만들고 커밋한 뒤에 불립니다.
     * 여기서 식별자로 다시 읽는 이유는 트랜잭션이 이미 끝나 있어
     * 앞에서 만든 엔티티를 그대로 들고 오면 준영속 상태이기 때문입니다.
     *
     * 예외를 밖으로 내보내지 않습니다.
     * 비동기라 받아 줄 곳이 없고, 실패는 실행 기록에 남기는 것이 우리 방식입니다.
     *
     * 마감하는 길이 넷입니다.
     * 끝까지 마친 것과, 허용량에 걸린 것과, 스스로 멈춘 것과, 예상하지 못한 오류입니다.
     * 앞의 셋은 실패가 아니며 뒤의 하나만 재개 지점을 물려주지 않습니다.
     */
    @Async
    public void execute(UUID runId) {
        IngestRun run = readRun(runId);
        SourceCollector collector = collectors.get(run.getSource());

        // 트리거가 이미 확인하지만 그 사이에 빈 구성이 바뀔 수 있으므로 한 번 더 봄
        if (collector == null) {
            chunkWriter.fail(runId, Map.of(), "등록된 수집기가 없습니다: " + run.getSource());
            return;
        }

        CollectionContext context = buildContext(run);
        log.info("수집을 시작합니다. runId={} source={} runType={}",
                runId, run.getSource(), run.getRunType());

        try {
            collector.collect(context, chunk -> {
                if (chunk == null || chunk.isEmpty()) {
                    return;
                }
                chunkWriter.write(runId, chunk, context.snapshot());
            });
            // 수집을 끝까지 마친 실행에서만 표본을 확인합니다.
            //
            // 허용량으로 끊긴 실행에서 표본까지 쓰면 이어받는 날마다 또 그만큼을 쓰고,
            // 그 몫이 진짜 대상에서 빠집니다.
            // 아래 catch 로 흘러간 실행은 여기 닿지 않으므로 그것만으로 조건이 됩니다.
            List<String> notes = sampleVerifier.verify(context);
            chunkWriter.complete(runId, context.snapshot(), context.skipped(), notes);

        } catch (QuotaExhaustedException e) {
            // 실패가 아님. 대상이 한도보다 많아 한 번에 못 끝내는 것이 정상임
            chunkWriter.stopByQuota(runId, context.snapshot(), e.getOperation(), context.skipped());

        } catch (CollectionInterruptedException e) {
            // 실패가 아님. 더 부르는 것이 낭비라고 보고 수집기가 스스로 멈춘 것임
            chunkWriter.interrupt(runId, context.snapshot(), e.getMessage(), context.skipped());

        } catch (Exception e) {
            log.error("수집 중 오류가 났습니다. runId={}", runId, e);
            chunkWriter.fail(runId, context.snapshot(), toMessage(e));
        }
    }

    /**
     * 이 실행이 어디서부터 시작할지 정합니다.
     *
     * 바로 앞 실행이 스스로 멈춘 것이면 그 재개 지점을 물려받습니다.
     * 물려받지 않으면 처음부터 다시 받게 되고, 이미 쓴 쿼터를 한 번 더 쓰는 셈입니다.
     * 되돌릴 수 없는 자원이라 그 낭비가 그날 몫을 통째로 날릴 수 있습니다.
     *
     * 호출 수는 물려받지 않습니다. 재개 지점만 가져옵니다.
     * 그 규칙과 근거는 CollectionContext.resumeFrom 에 적어 두었습니다.
     *
     * 물려받는 상태가 둘입니다.
     * 허용량에 걸려 멈춘 것과, 연달아 실패해 수집기가 스스로 접은 것입니다.
     * 둘 다 우리가 상황을 보고 멈춘 것이고 어디까지 저장했는지가 분명합니다.
     *
     * 실패한 실행의 재개 지점은 쓰지 않습니다.
     * 그 자리에서 무엇이 잘못됐는지 아직 모르는 상태라
     * 그대로 이어받으면 문제가 난 구간을 조용히 건너뜁니다.
     * 이미 쓴 쿼터를 다시 쓰게 되지만, 무엇을 빠뜨렸는지 모르는 채로 두는 것보다 낫습니다.
     *
     * 앞 실행이 끝까지 마쳤으면 처음부터 시작합니다.
     */
    private CollectionContext buildContext(IngestRun run) {
        return ingestRunRepository.findPreviousRun(run.getSource(), run.getId())
                .filter(previous -> previous.getStatus() == RunStatus.QUOTA_STOPPED
                        || previous.getStatus() == RunStatus.INTERRUPTED)
                .map(previous -> {
                    log.info("앞 실행의 재개 지점을 물려받습니다. previousRunId={} status={} progress={}",
                            previous.getId(), previous.getStatus(), previous.getProgress());
                    return CollectionContext.resumeFrom(run.getRunType(), previous.getProgress());
                })
                .orElseGet(() -> CollectionContext.startFresh(run.getRunType()));
    }

    /**
     * 실행 기록을 읽습니다.
     *
     * 트랜잭션을 열지 않습니다.
     * 조회 한 번이고 지연 로딩 대상이 없어 저장소가 여는 것으로 충분합니다.
     * 여기에 애노테이션을 붙여도 같은 객체 안의 호출이라 어차피 걸리지 않으므로,
     * 붙여 두면 걸린다고 오해하게 됩니다.
     */
    private IngestRun readRun(UUID runId) {
        return ingestRunRepository.findById(runId)
                .orElseThrow(() -> new CustomException(IngestErrorCode.INGEST_RUN_NOT_FOUND));
    }

    /**
     * 실행 기록에 남길 문구를 만듭니다.
     *
     * 스택트레이스는 로그에 이미 남으므로 여기에는 담지 않습니다.
     * 이 값은 관리자 화면에 그대로 보일 수 있어 짧아야 합니다.
     */
    private String toMessage(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
