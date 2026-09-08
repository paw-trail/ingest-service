package com.pawtrail.ingest.application.service;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.enums.SourceType;
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
    private final Map<SourceType, SourceCollector> collectors;

    public IngestExecutor(
            IngestRunRepository ingestRunRepository,
            ChunkWriter chunkWriter,
            List<SourceCollector> sourceCollectors) {

        this.ingestRunRepository = ingestRunRepository;
        this.chunkWriter = chunkWriter;
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

        CollectionContext context = new CollectionContext(run.getRunType(), run.getProgress());
        log.info("수집을 시작합니다. runId={} source={} runType={}",
                runId, run.getSource(), run.getRunType());

        try {
            collector.collect(context, chunk -> {
                if (chunk == null || chunk.isEmpty()) {
                    return;
                }
                chunkWriter.write(runId, chunk, context.snapshot());
            });
            chunkWriter.complete(runId, context.snapshot());

        } catch (QuotaExhaustedException e) {
            // 실패가 아님. 대상이 한도보다 많아 한 번에 못 끝내는 것이 정상임
            chunkWriter.stopByQuota(runId, context.snapshot(), e.getOperation());

        } catch (Exception e) {
            log.error("수집 중 오류가 났습니다. runId={}", runId, e);
            chunkWriter.fail(runId, context.snapshot(), toMessage(e));
        }
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
