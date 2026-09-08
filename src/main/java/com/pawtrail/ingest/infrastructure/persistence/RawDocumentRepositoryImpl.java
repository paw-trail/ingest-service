package com.pawtrail.ingest.infrastructure.persistence;

import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import com.pawtrail.ingest.infrastructure.persistence.jpa.RawDocumentJpaRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
        return rawDocumentJpaRepository.findByStatus(DocumentStatus.PENDING, pageable);
    }
}
