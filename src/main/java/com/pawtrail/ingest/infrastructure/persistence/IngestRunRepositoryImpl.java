package com.pawtrail.ingest.infrastructure.persistence;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.infrastructure.persistence.jpa.IngestRunJpaRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 스프링 데이터로 구현합니다.
 */
@Repository
@RequiredArgsConstructor
public class IngestRunRepositoryImpl implements IngestRunRepository {

    private final IngestRunJpaRepository ingestRunJpaRepository;

    @Override
    public IngestRun save(IngestRun ingestRun) {
        return ingestRunJpaRepository.save(ingestRun);
    }

    @Override
    public IngestRun saveAndFlush(IngestRun ingestRun) {
        return ingestRunJpaRepository.saveAndFlush(ingestRun);
    }

    @Override
    public Optional<IngestRun> findById(UUID id) {
        return ingestRunJpaRepository.findById(id);
    }

    @Override
    public boolean existsRunningBySource(SourceType source) {
        return ingestRunJpaRepository.existsBySourceAndStatus(source, RunStatus.RUNNING);
    }

    @Override
    public Optional<IngestRun> findPreviousRun(SourceType source, UUID currentRunId) {
        return ingestRunJpaRepository
                .findFirstBySourceAndIdNotOrderByStartedAtDescIdDesc(source, currentRunId);
    }
}
