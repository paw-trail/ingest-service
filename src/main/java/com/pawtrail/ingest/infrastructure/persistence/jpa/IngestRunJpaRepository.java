package com.pawtrail.ingest.infrastructure.persistence.jpa;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestRunJpaRepository extends JpaRepository<IngestRun, UUID> {

    boolean existsBySourceAndStatus(SourceType source, RunStatus status);

    // 시작 시각이 같은 행이 나올 수 있어 식별자로 한 번 더 정렬함
    // 식별자가 시각 순서를 담은 uuid v7 이라 이 정렬이 시작 순서와 어긋나지 않음
    Optional<IngestRun> findFirstBySourceAndIdNotOrderByStartedAtDescIdDesc(
            SourceType source, UUID excludedId);

    // 아래 둘은 소스를 거를 때와 안 거를 때로 갈림
    // 조건이 하나뿐이라 동적 쿼리를 만들 값어치가 없어 메서드를 둘로 둠
    // 정렬 규칙은 위와 같은 이유로 식별자를 함께 봄
    List<IngestRun> findAllByOrderByStartedAtDescIdDesc(Pageable pageable);

    List<IngestRun> findBySourceOrderByStartedAtDescIdDesc(SourceType source, Pageable pageable);
}
