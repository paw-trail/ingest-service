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

    /**
     * 그 소스의 원본을 식별자 순으로 한 쪽씩 돌려줍니다.
     *
     * 장소 서비스로 넘길 때 씁니다.
     *
     * 쪽 번호로 넘겨도 됩니다.
     * 대기 목록과 달리 이 조회는 도는 동안 대상이 줄지 않습니다.
     * 장소 식별자를 채우는 것뿐이라 행이 조건에서 빠지지 않기 때문입니다.
     *
     * 식별자 순으로 정렬합니다.
     * 순서가 정해져 있지 않으면 쪽을 넘길 때 같은 행이 두 번 나오거나 한 행이 통째로 빠집니다.
     * 식별자가 시간순으로 만들어지는 값이라 그 순서가 곧 받아 온 순서이기도 합니다.
     */
    Page<RawDocument> findBySource(SourceType source, Pageable pageable);

    /**
     * 그 장소에 이어진 원본을 돌려줍니다.
     *
     * 「근거 원문 전체 보기」가 씁니다.
     *
     * 소스가 여럿이면 여럿입니다.
     * 세 데이터셋이 같은 장소를 가리켜 병합된 곳이 백마흔일곱 군데 있습니다.
     *
     * 페이징을 두지 않습니다.
     * 한 장소에 이어질 수 있는 원본이 소스 수만큼이라 많아야 셋입니다.
     * 쪽을 나누면 부르는 쪽이 쪽을 넘기는 코드를 짜야 하는데 넘길 쪽이 생기지 않습니다.
     */
    List<RawDocument> findByPlaceId(UUID placeId);

    /**
     * 그 소스의 식별자와 수정 시각만 한 번에 읽어 옵니다.
     *
     * 증분 수집이 상세를 부를지 판단하는 자리입니다.
     * 목록에서 받은 수정 시각이 여기 담긴 값보다 늦으면 바뀐 것으로 봅니다.
     *
     * 원본을 통째로 읽지 않습니다.
     * 문서 하나에 소스 응답이 그대로 들어 있어 천 건이면 수십 메가바이트가 됩니다.
     * 판단에 필요한 것은 두 값뿐입니다.
     */
    List<SourceModifiedView> findSourceModified(SourceType source);

    /**
     * 그 소스에서 가장 오래 전에 받아 온 문서를 돌려줍니다.
     *
     * 증분이 도는 전제를 검증하는 표본입니다.
     *
     * 방금 처리한 문서는 받아 온 시각이 새것이라 뒤로 갑니다.
     * 그래서 앞쪽에는 자연히 이번에 건너뛴 것들이 옵니다.
     * 수집기가 무엇을 건너뛰었는지 따로 알려주지 않아도 됩니다.
     *
     * 오래된 것부터 뽑는 이유가 하나 더 있습니다.
     * 무작위보다 예측 가능하고, 오래 받지 않은 것일수록
     * 소스가 조용히 고쳤을 가능성이 높습니다.
     */
    List<RawDocument> findOldestFetched(SourceType source, int size);
}
