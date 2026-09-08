package com.pawtrail.ingest.domain.repository;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 원본 문서를 저장하고 찾아오는 약속입니다.
 *
 * 이 인터페이스에는 JPA 라는 단어가 나오지 않습니다.
 * 무엇을 할 수 있는지만 적고 어떻게 하는지는 infrastructure 가 정합니다.
 */
public interface RawDocumentRepository {

    RawDocument save(RawDocument rawDocument);

    /**
     * 같은 소스의 같은 문서를 찾습니다.
     *
     * 재수집이 새로 담는 것이 아니라 고쳐 담는 것이라 이 조회가 먼저 필요합니다.
     * uq_raw_document_source_source_id 를 그대로 탑니다.
     */
    Optional<RawDocument> findBySourceAndSourceId(SourceType source, String sourceId);

    /**
     * 아직 추출하지 않은 문서를 가져옵니다.
     *
     * extract 가 GET /internal/raw?status=PENDING 으로 부릅니다.
     * 수집이 끝나면 대부분 처리 완료라 PENDING 이 소수이고,
     * 마이그레이션에 부분 인덱스를 걸어 두어 완료된 행이 쌓여도 인덱스가 커지지 않습니다.
     *
     * 조회 API 자체는 이 이슈 범위가 아니지만
     * 저장소의 약속에는 함께 적어 둡니다.
     */
    Page<RawDocument> findPending(Pageable pageable);
}
