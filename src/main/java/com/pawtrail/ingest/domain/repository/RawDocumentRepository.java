package com.pawtrail.ingest.domain.repository;

import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
     */
    Page<RawDocument> findPending(Pageable pageable);

    /**
     * 그 상태의 문서를 가져옵니다.
     *
     * 부르는 쪽이 언제나 첫 쪽만 달라고 합니다. 쪽 번호로 넘기지 않습니다.
     * 처리하면 그 문서가 대기 목록에서 빠지므로, 쪽 번호로 넘기면
     * 뒤에 있던 것이 앞으로 밀려와 그만큼을 통째로 건너뜁니다.
     * 게다가 그 일이 조용히 일어나 로그에도 남지 않습니다.
     */
    Page<RawDocument> findByStatus(DocumentStatus status, Pageable pageable);

    /**
     * 그 상태의 문서가 몇 건인지 셉니다.
     *
     * extract 가 앞으로 몇 번을 더 불러야 하는지 판단하는 값입니다.
     * 진행률을 찍는 데에도 씁니다.
     */
    long countByStatus(DocumentStatus status);

    /**
     * 식별자 목록으로 한 번에 찾습니다.
     *
     * extract 가 처리 결과를 묶어서 되돌려 줄 때 씁니다.
     * 하나씩 찾으면 백 건이면 조회가 백 번이라 한 번에 가져옵니다.
     *
     * 돌려주는 개수가 요청한 개수와 다를 수 있습니다.
     * 없는 식별자가 섞여 있다는 뜻이며, 부르는 쪽이 그것을 판단합니다.
     */
    List<RawDocument> findAllByIds(Collection<UUID> ids);
}
