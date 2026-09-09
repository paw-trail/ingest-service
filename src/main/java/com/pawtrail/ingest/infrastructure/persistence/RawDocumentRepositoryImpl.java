package com.pawtrail.ingest.infrastructure.persistence;

import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import com.pawtrail.ingest.domain.repository.SourceModifiedView;
import com.pawtrail.ingest.infrastructure.persistence.jpa.RawDocumentJpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 스프링 데이터로 구현합니다.
 */
@Repository
@RequiredArgsConstructor
public class RawDocumentRepositoryImpl implements RawDocumentRepository {

    private final RawDocumentJpaRepository rawDocumentJpaRepository;

    @Override
    public RawDocument save(RawDocument rawDocument) {
        return rawDocumentJpaRepository.save(rawDocument);
    }

    @Override
    public Optional<RawDocument> findBySourceAndSourceId(SourceType source, String sourceId) {
        return rawDocumentJpaRepository.findBySourceAndSourceId(source, sourceId);
    }

    @Override
    public Page<RawDocument> findPending(Pageable pageable) {
        return rawDocumentJpaRepository.findByStatusOrderByIdAsc(DocumentStatus.PENDING, pageable);
    }

    @Override
    public Page<RawDocument> findByStatus(DocumentStatus status, Pageable pageable) {
        // 정렬은 메서드 이름에 들어 있으므로 여기서는 개수만 넘김
        // Pageable 에도 정렬을 담으면 같은 규칙이 두 곳에 생겨 한쪽만 고치는 실수가 남
        return rawDocumentJpaRepository.findByStatusOrderByIdAsc(status, pageable);
    }

    @Override
    public long countByStatus(DocumentStatus status) {
        return rawDocumentJpaRepository.countByStatus(status);
    }

    @Override
    public List<RawDocument> findAllByIds(Collection<UUID> ids) {
        return rawDocumentJpaRepository.findAllById(ids);
    }

    @Override
    public List<SourceModifiedView> findSourceModified(SourceType source) {
        return rawDocumentJpaRepository.findBySource(source);
    }

    @Override
    public List<RawDocument> findOldestFetched(SourceType source, int size) {
        // 정렬은 메서드 이름에 있으므로 여기서는 개수만 정함
        return rawDocumentJpaRepository
                .findBySourceOrderByFetchedAtAscIdAsc(source, PageRequest.ofSize(size));
    }
}
