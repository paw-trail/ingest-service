package com.pawtrail.ingest.infrastructure.persistence;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.infrastructure.persistence.jpa.IngestRunJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    @Override
    public List<IngestRun> findRecent(SourceType source, int size) {
        // 정렬은 메서드 이름에 이미 들어 있으므로 여기서는 개수만 정함
        // 정렬을 Pageable 에도 넣으면 두 곳에 같은 규칙이 생겨 한쪽만 고치는 실수가 남
        Pageable limit = PageRequest.ofSize(size);
        return source == null
                ? ingestRunJpaRepository.findAllByOrderByStartedAtDescIdDesc(limit)
                : ingestRunJpaRepository.findBySourceOrderByStartedAtDescIdDesc(source, limit);
    }
}
