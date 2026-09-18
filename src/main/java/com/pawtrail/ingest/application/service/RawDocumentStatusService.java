package com.pawtrail.ingest.application.service;

import com.pawtrail.common.audit.AuditorProvider;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.application.dto.input.StatusMarkInput;
import com.pawtrail.ingest.application.dto.output.StatusUpdateOutput;
import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * extract 가 처리한 결과를 되돌려 씁니다.
 *
 * 이 서비스의 첫 쓰기 경로입니다.
 *
 * *마이그레이션 주석의 "관리자도 조회 전용" 과 어긋나지 않습니다.
 *  그 문장은 원본과 표시용 본문을 두고 한 말입니다.
 *  고치면 원문이 아니게 되어 원문보기의 신뢰가 무너지기 때문입니다.
 *
 *  여기서 바꾸는 것은 처리 상태 하나뿐입니다.
 *  그것은 소스가 준 값이 아니라 우리가 매기는 표시라 성격이 다릅니다.
 *  원본과 본문은 이 경로로 바뀌지 않습니다.
 *
 * *엔티티를 불러 고치지 않고 상태 칸 하나만 UPDATE 합니다.
 *  엔티티째 저장하면 모든 칸이 다시 쓰여, 그사이 재수집이나 장소 넘기기가 바꾼
 *  원본 · 장소 식별자를 옛 값으로 되덮을 수 있습니다.
 *  한 칸만 쓰면 그 틈이 없습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawDocumentStatusService {

    private final RawDocumentRepository rawDocumentRepository;
    private final AuditorProvider auditorProvider;

    /**
     * 처리 결과를 한 트랜잭션으로 씁니다.
     *
     * 없는 식별자가 하나라도 있으면 전체를 거절합니다.
     * 방금 우리에게 받아 간 것을 되돌려 주는 것인데 없다는 것은 무언가 어긋난 것입니다.
     * 조용히 건너뛰면 그 문서가 영영 대기로 남아 목록 맨 앞을 막습니다.
     *
     * 내용 해시가 지금 것과 다른 문서는 상태를 바꾸지 않고 건너뜁니다.
     * extract 가 가져간 사이에 재수집이 내용을 바꾼 것이라 옛 내용으로 뽑은 결과입니다.
     * 오류가 아니므로 거절하지 않고 건수로 알립니다.
     * 그 문서는 대기로 남아 다음 실행이 새 내용으로 다시 가져갑니다.
     *
     * @return 바꾼 건수와 건너뛴 건수
     */
    @Transactional
    public StatusUpdateOutput apply(List<StatusMarkInput> done, List<StatusMarkInput> failed) {
        Map<UUID, String> doneMarks = distinct(done);
        Map<UUID, String> failedMarks = distinct(failed);

        // 같은 문서를 처리 완료이자 실패라고 말하는 것은 부르는 쪽의 실수임
        // 둘 중 어느 것으로 둘지 우리가 고를 수 없으므로 거절함
        Set<UUID> overlap = new LinkedHashSet<>(doneMarks.keySet());
        overlap.retainAll(failedMarks.keySet());
        if (!overlap.isEmpty()) {
            log.warn("같은 식별자가 done 과 failed 에 함께 있습니다. ids={}", limited(overlap));
            throw new CustomException(IngestErrorCode.RAW_DOCUMENT_STATUS_CONFLICT);
        }

        Set<UUID> requested = new LinkedHashSet<>(doneMarks.keySet());
        requested.addAll(failedMarks.keySet());
        if (requested.isEmpty()) {
            // 처리할 것이 없었다는 뜻이라 오류가 아님
            return new StatusUpdateOutput(0, 0);
        }

        // 있는지만 봄. 원본을 백 건 올려 놓을 이유가 없음
        Set<UUID> existing = rawDocumentRepository.findExistingIds(requested);
        if (existing.size() != requested.size()) {
            Set<UUID> missing = new LinkedHashSet<>(requested);
            missing.removeAll(existing);
            log.warn("없는 문서가 섞여 있어 전체를 거절합니다. 요청={} 없음={} ids={}",
                    requested.size(), missing.size(), limited(missing));
            throw new CustomException(IngestErrorCode.RAW_DOCUMENT_NOT_FOUND);
        }

        // 한 요청의 감사 값은 하나로 묶음. 문서마다 시각이 갈릴 이유가 없음
        LocalDateTime now = LocalDateTime.now();
        String updatedBy = auditorProvider.current();

        List<UUID> skipped = new ArrayList<>();
        int updated = mark(doneMarks, DocumentStatus.DONE, now, updatedBy, skipped)
                + mark(failedMarks, DocumentStatus.FAILED, now, updatedBy, skipped);

        if (!skipped.isEmpty()) {
            log.warn("처리하는 사이에 내용이 바뀐 문서는 대기로 둡니다. 건너뜀={} ids={}",
                    skipped.size(), limited(skipped));
        }
        log.info("처리 결과를 반영했습니다. done={} failed={} 바꿈={} 건너뜀={}",
                doneMarks.size(), failedMarks.size(), updated, skipped.size());
        return new StatusUpdateOutput(updated, skipped.size());
    }

    /**
     * 해시가 같은 문서만 상태를 바꾸고, 다른 문서는 건너뛴 목록에 담습니다.
     *
     * @return 바꾼 건수
     */
    private int mark(Map<UUID, String> marks, DocumentStatus status,
                     LocalDateTime now, String updatedBy, List<UUID> skipped) {
        int updated = 0;
        for (Map.Entry<UUID, String> mark : marks.entrySet()) {
            if (rawDocumentRepository.markStatusIfUnchanged(
                    mark.getKey(), mark.getValue(), status, now, updatedBy)) {
                updated++;
            } else {
                skipped.add(mark.getKey());
            }
        }
        return updated;
    }

    /**
     * 중복을 걷어내고 순서를 지킵니다.
     *
     * 같은 식별자가 두 번 오는 것은 부르는 쪽의 실수이지만 결과가 달라지지 않으므로
     * 거절하지 않고 한 번으로 셉니다.
     * 해시가 서로 다르게 두 번 오면 먼저 온 것을 씁니다. 그 해시가 낡았다면 건너뛸 뿐이라
     * 문서가 대기로 남는 쪽으로만 어긋납니다.
     * 순서를 지키는 이유는 로그에 남는 목록이 실행마다 같아야 견주기 쉽기 때문입니다.
     */
    private Map<UUID, String> distinct(List<StatusMarkInput> marks) {
        Map<UUID, String> distinct = new LinkedHashMap<>();
        if (marks != null) {
            marks.forEach(mark -> distinct.putIfAbsent(mark.id(), mark.contentHash()));
        }
        return distinct;
    }

    /**
     * 로그에 남길 목록을 앞쪽만 자릅니다.
     *
     * 천 건이 통째로 실패하면 그것을 다 남겨야 할 이유가 없고,
     * 남기면 그 줄 하나가 로그를 덮습니다.
     */
    private List<UUID> limited(Iterable<UUID> ids) {
        List<UUID> list = new ArrayList<>();
        ids.forEach(list::add);
        return list.subList(0, Math.min(list.size(), 10));
    }
}
