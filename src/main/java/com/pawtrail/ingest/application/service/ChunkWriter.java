package com.pawtrail.ingest.application.service;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.application.support.JsonNormalizer;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.model.OperationProgress;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청크 하나를 트랜잭션으로 저장합니다.
 *
 * 실행기와 갈라 둔 이유는 스프링의 트랜잭션 프록시가 같은 객체 안의 호출에는
 * 걸리지 않기 때문입니다.
 * 한 클래스에 두면 청크마다 트랜잭션이 열리지 않고 실행 전체가 한 트랜잭션이 되어,
 * 하나가 실패하면 그때까지 받은 것이 통째로 사라집니다.
 * 받아 온 것을 잃는다는 것은 쿼터를 그만큼 버린다는 뜻입니다.
 *
 * 원본 저장과 진행 기록이 반드시 한 트랜잭션이어야 합니다.
 * 갈라 두면 저장은 롤백됐는데 호출 수만 오른 상태가 만들어지고,
 * 그것은 쿼터는 썼는데 데이터는 없다는 뜻입니다.
 * 쿼터는 되돌릴 수 없는 자원이라 나중에 알아채도 복구할 방법이 없습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkWriter {

    private final RawDocumentRepository rawDocumentRepository;
    private final IngestRunRepository ingestRunRepository;
    private final JsonNormalizer jsonNormalizer;

    /**
     * 청크를 저장하고 진행을 기록합니다.
     *
     * 이미 있는 문서면 고쳐 담습니다.
     * 내용이 그대로면 받아 온 시각만 새로 찍고 상태는 건드리지 않습니다.
     * 되돌리면 안 바뀐 문서를 추출이 다시 처리하게 되는데 그 비용이 모델 호출입니다.
     */
    @Transactional
    public void write(
            UUID runId,
            List<RawDocumentDraft> drafts,
            Map<String, OperationProgress> progressSnapshot) {

        IngestRun run = loadRun(runId);
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;

        for (RawDocumentDraft draft : drafts) {
            String payload = jsonNormalizer.normalize(draft.payload());
            String contentHash = jsonNormalizer.hash(payload);

            RawDocument existing = rawDocumentRepository
                    .findBySourceAndSourceId(draft.source(), draft.sourceId())
                    .orElse(null);

            if (existing == null) {
                rawDocumentRepository.save(RawDocument.create(
                        draft.source(),
                        draft.sourceId(),
                        payload,
                        draft.displayTitle(),
                        draft.displayBody(),
                        contentHash,
                        draft.sourceModified(),
                        now));
                changed++;
                continue;
            }

            boolean updated = existing.applyIfChanged(
                    payload,
                    draft.displayTitle(),
                    draft.displayBody(),
                    contentHash,
                    draft.sourceModified(),
                    now);
            if (updated) {
                changed++;
            }
        }

        run.applyChunk(drafts.size(), changed, progressSnapshot);
        log.debug("청크를 저장했습니다. runId={} fetched={} changed={}", runId, drafts.size(), changed);
    }

    /**
     * 끝까지 마친 것으로 마감합니다.
     */
    @Transactional
    public void complete(UUID runId, Map<String, OperationProgress> progressSnapshot) {
        IngestRun run = loadRun(runId);
        run.complete(progressSnapshot);
        log.info("수집을 마쳤습니다. runId={} fetched={} changed={}",
                runId, run.getFetchedCount(), run.getChangedCount());
    }

    /**
     * 일일 호출 허용량에 걸려 멈춘 것으로 마감합니다. 실패가 아닙니다.
     *
     * 다음 실행이 진행 기록의 재개 지점에서 이어받습니다.
     */
    @Transactional
    public void stopByQuota(
            UUID runId, Map<String, OperationProgress> progressSnapshot, String operation) {

        IngestRun run = loadRun(runId);
        run.stopByQuota(progressSnapshot, operation);
        log.info("쿼터로 멈췄습니다. runId={} operation={} fetched={}",
                runId, operation, run.getFetchedCount());
    }

    /**
     * 실패로 마감합니다.
     *
     * 진행 기록을 함께 남깁니다.
     * 실패해도 그때까지 쓴 쿼터는 이미 쓴 것이라 기록이 사라지면
     * 다음 실행이 아직 안 썼다고 판단합니다.
     */
    @Transactional
    public void fail(UUID runId, Map<String, OperationProgress> progressSnapshot, String message) {
        IngestRun run = loadRun(runId);
        run.fail(progressSnapshot, message);
        log.error("수집이 실패했습니다. runId={} message={}", runId, message);
    }

    private IngestRun loadRun(UUID runId) {
        return ingestRunRepository.findById(runId)
                .orElseThrow(() -> new CustomException(IngestErrorCode.INGEST_RUN_NOT_FOUND));
    }
}
