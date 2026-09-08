package com.pawtrail.ingest.infrastructure.persistence.jpa;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestRunJpaRepository extends JpaRepository<IngestRun, UUID> {

    boolean existsBySourceAndStatus(SourceType source, RunStatus status);
}
