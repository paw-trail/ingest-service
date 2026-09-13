package com.pawtrail.ingest.infrastructure.persistence.jpa;

import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.SourceModifiedView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawDocumentJpaRepository extends JpaRepository<RawDocument, UUID> {

    Optional<RawDocument> findBySourceAndSourceId(SourceType source, String sourceId);

    /**
     * 그 상태의 문서를 오래된 것부터 돌려줍니다.
     *
     * ⛔정렬을 메서드 이름에 못 박습니다.
     *  부르는 쪽이 Pageable 에 정렬을 담아 넘길 수도 있지만 그러면 빠뜨릴 수 있고,
     *  빠뜨려도 조회가 실패하지 않아 알아챌 방법이 없습니다.
     *
     *  정렬이 없으면 데이터베이스가 어떤 순서로 돌려줄지 보장되지 않습니다.
     *  대개 물리적 순서로 오지만 그것은 그날의 실행 계획일 뿐입니다.
     *  extract 가 상태를 계속 바꾸면 행이 옮겨 다니고,
     *  그러면 처리하지 못한 오래된 문서가 뒤로 밀려 영영 나오지 않을 수 있습니다.
     *
     * 식별자 하나로 정렬합니다.
     * uuid v7 이라 시간 순서를 담고 있고 기본 키라 유일해서 동점이 없습니다.
     * 받아 온 시각으로 정렬하면 재수집 때 그 값이 갱신되어 순서가 흔들립니다.
     */
    Page<RawDocument> findByStatusOrderByIdAsc(DocumentStatus status, Pageable pageable);

    long countByStatus(DocumentStatus status);

    /**
     * 증분 판단에 쓰는 두 값만 읽습니다. 원본을 읽지 않아 가볍습니다.
     */
    List<SourceModifiedView> findBySource(SourceType source);

    /**
     * 받아 온 시각이 이른 것부터 돌려줍니다.
     *
     * 시각이 같은 행이 나올 수 있어 식별자로 한 번 더 정렬합니다.
     * 식별자가 시각 순서를 담은 uuid v7 이라 이 정렬이 받아 온 순서와 어긋나지 않습니다.
     */
    List<RawDocument> findBySourceOrderByFetchedAtAscIdAsc(SourceType source, Pageable pageable);

    /**
     * 그 소스의 원본을 식별자 순으로 돌려줍니다.
     *
     * 장소 서비스로 넘길 때 씁니다.
     * 식별자에 기본 키 인덱스가 있어 정렬에 따로 드는 비용이 없습니다.
     */
    Page<RawDocument> findBySourceOrderByIdAsc(SourceType source, Pageable pageable);

    /**
     * 그 장소에 이어진 원본을 돌려줍니다.
     *
     * place_id 에 인덱스가 있어 장소 하나를 집어내는 데 드는 비용이 없습니다.
     *
     * 정렬을 소스 이름순으로 두지 않습니다.
     * 화면에 보이는 순서를 소스 열거값의 차례에 맞춰야 하는데 그것은 이름차례가 아닙니다.
     * 그 정렬은 부르는 쪽이 합니다.
     */
    List<RawDocument> findByPlaceId(UUID placeId);
}
