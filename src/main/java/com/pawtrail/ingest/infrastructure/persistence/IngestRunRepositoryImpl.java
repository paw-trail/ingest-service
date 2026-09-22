package com.pawtrail.ingest.infrastructure.persistence;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.infrastructure.persistence.jpa.IngestRunJpaRepository;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
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

    /**
     * 받아 오기에 드는 실행 종류입니다. 넘기기와 바로 보내기는 뺍니다.
     *
     * 목록을 여기 적지 않고 열거값의 표시에서 뽑습니다.
     * 실행 종류가 늘어도 이 자리를 따로 고치지 않게 하려는 것입니다.
     */
    private static final List<RunType> COLLECT_TYPES =
            Arrays.stream(RunType.values()).filter(RunType::isCollect).toList();

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

    @Override
    public boolean existsCollectFinishedSince(SourceType source, LocalDateTime since) {
        return ingestRunJpaRepository.existsBySourceAndRunTypeInAndFinishedAtAfter(
                source, COLLECT_TYPES, since);
    }

    @Override
    public List<IngestRun> findRecentCollect(Collection<SourceType> sources, int size) {
        // 정렬은 메서드 이름에 들어 있어 여기서는 개수만 정함 — findRecent 와 같은 까닭
        return ingestRunJpaRepository.findBySourceInAndRunTypeInOrderByStartedAtDescIdDesc(
                sources, COLLECT_TYPES, PageRequest.ofSize(size));
    }

    @Override
    public List<IngestRun> findAllRunning() {
        return ingestRunJpaRepository.findByStatus(RunStatus.RUNNING);
    }
}
