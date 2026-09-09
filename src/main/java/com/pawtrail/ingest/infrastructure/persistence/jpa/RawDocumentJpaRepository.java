package com.pawtrail.ingest.infrastructure.persistence.jpa;

import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawDocumentJpaRepository extends JpaRepository<RawDocument, UUID> {

    Optional<RawDocument> findBySourceAndSourceId(SourceType source, String sourceId);

    Page<RawDocument> findByStatus(DocumentStatus status, Pageable pageable);

    long countByStatus(DocumentStatus status);
}
