package com.pawtrail.ingest.domain.repository;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import java.util.Optional;
import java.util.UUID;

/**
 * 수집 실행 기록을 저장하고 찾아오는 약속입니다.
 */
public interface IngestRunRepository {

    IngestRun save(IngestRun ingestRun);

    Optional<IngestRun> findById(UUID id);

    /**
     * 그 소스가 지금 실행 중인지 봅니다.
     *
     * 수집은 비동기라 트리거를 두 번 부르면 같은 소스가 나란히 돌 수 있습니다.
     * 그러면 같은 쿼터를 두 배로 쓰고 둘 다 한도에 못 미쳐 멈춰,
     * 어느 쪽도 끝내지 못한 채 그날 몫이 사라집니다.
     *
     * 완벽한 방어는 아닙니다.
     * 두 요청이 같은 순간에 들어오면 둘 다 없음을 보고 지나갈 수 있습니다.
     * 사람이 버튼을 누르는 경로라 그 확률을 받아들이고,
     * 유일 제약을 걸지 않는 이유는 실행 기록이 여러 건 쌓이는 것이 정상이기 때문입니다.
     */
    boolean existsRunningBySource(SourceType source);
}
