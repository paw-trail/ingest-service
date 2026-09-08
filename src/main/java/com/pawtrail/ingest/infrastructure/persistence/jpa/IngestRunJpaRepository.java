package com.pawtrail.ingest.infrastructure.persistence.jpa;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestRunJpaRepository extends JpaRepository<IngestRun, UUID> {

    boolean existsBySourceAndStatus(SourceType source, RunStatus status);

    // 시작 시각이 같은 행이 나올 수 있어 식별자로 한 번 더 정렬함
    // 식별자가 시각 순서를 담은 uuid v7 이라 이 정렬이 시작 순서와 어긋나지 않음
    Optional<IngestRun> findFirstBySourceAndIdNotOrderByStartedAtDescIdDesc(
            SourceType source, UUID excludedId);
}
