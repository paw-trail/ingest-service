package com.pawtrail.ingest.application.service;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.application.dto.output.StatusUpdateOutput;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawDocumentStatusService {

    private final RawDocumentRepository rawDocumentRepository;

    /**
     * 처리 결과를 한 트랜잭션으로 씁니다.
     *
     * 전부 바뀌거나 전부 안 바뀝니다.
     * 부르는 쪽이 묶음으로 일하므로 되돌려 쓰는 것도 묶음이어야
     * 절반만 바뀐 상태가 생기지 않습니다.
     *
     * 없는 식별자가 하나라도 있으면 전체를 거절합니다.
     * 방금 우리에게 받아 간 것을 되돌려 주는 것인데 없다는 것은 무언가 어긋난 것입니다.
     * 조용히 건너뛰면 그 문서가 영영 대기로 남아 목록 맨 앞을 막습니다.
     *
     * @return 바꾼 건수
     */
    @Transactional
    public StatusUpdateOutput apply(List<UUID> done, List<UUID> failed) {
        Set<UUID> doneIds = distinct(done);
        Set<UUID> failedIds = distinct(failed);

        // 같은 문서를 처리 완료이자 실패라고 말하는 것은 부르는 쪽의 실수임
        // 둘 중 어느 것으로 둘지 우리가 고를 수 없으므로 거절함
        Set<UUID> overlap = new LinkedHashSet<>(doneIds);
        overlap.retainAll(failedIds);
        if (!overlap.isEmpty()) {
            log.warn("같은 식별자가 done 과 failed 에 함께 있습니다. ids={}", limited(overlap));
            throw new CustomException(IngestErrorCode.RAW_DOCUMENT_STATUS_CONFLICT);
        }

        Set<UUID> requested = new LinkedHashSet<>(doneIds);
        requested.addAll(failedIds);
        if (requested.isEmpty()) {
            // 처리할 것이 없었다는 뜻이라 오류가 아님
            return new StatusUpdateOutput(0);
        }

        Map<UUID, RawDocument> found = rawDocumentRepository.findAllByIds(requested).stream()
                .collect(java.util.stream.Collectors.toMap(RawDocument::getId, Function.identity()));

        if (found.size() != requested.size()) {
            Set<UUID> missing = new LinkedHashSet<>(requested);
            missing.removeAll(found.keySet());
            log.warn("없는 문서가 섞여 있어 전체를 거절합니다. 요청={} 없음={} ids={}",
                    requested.size(), missing.size(), limited(missing));
            throw new CustomException(IngestErrorCode.RAW_DOCUMENT_NOT_FOUND);
        }

        doneIds.forEach(id -> found.get(id).markDone());
        failedIds.forEach(id -> found.get(id).markFailed());

        log.info("처리 결과를 반영했습니다. done={} failed={}", doneIds.size(), failedIds.size());
        return new StatusUpdateOutput(requested.size());
    }

    /**
     * 중복을 걷어내고 순서를 지킵니다.
     *
     * 같은 식별자가 두 번 오는 것은 부르는 쪽의 실수이지만 결과가 달라지지 않으므로
     * 거절하지 않고 한 번으로 셉니다.
     * 순서를 지키는 이유는 로그에 남는 목록이 실행마다 같아야 견주기 쉽기 때문입니다.
     */
    private Set<UUID> distinct(List<UUID> ids) {
        return ids == null ? new LinkedHashSet<>() : new LinkedHashSet<>(ids);
    }

    /**
     * 로그에 남길 목록을 앞쪽만 자릅니다.
     *
     * 천 건이 통째로 실패하면 그것을 다 남겨야 할 이유가 없고,
     * 남기면 그 줄 하나가 로그를 덮습니다.
     */
    private List<UUID> limited(Set<UUID> ids) {
        return new ArrayList<>(ids).subList(0, Math.min(ids.size(), 10));
    }
}
