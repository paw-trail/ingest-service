package com.pawtrail.ingest.domain.provider;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import java.util.List;
import java.util.function.Consumer;

/**
 * 소스 하나에서 원본을 읽어 오는 약속입니다.
 *
 * 전량을 모아 반환하지 않고 청크를 만들 때마다 넘기는 형태인 이유가 둘 있습니다.
 *
 * 한국관광공사 소스가 전량 반환으로는 성립하지 않습니다.
 * 대상이 1,080건이고 상세가 건당 세 번이라 3,240회를 다 부른 뒤에야 반환할 수 있는데,
 * 오퍼레이션마다 하루 1,000건이라 중간에 멈춥니다.
 * 그때 돌려줄 것이 없어 중단과 재개를 표현할 수 없습니다.
 *
 * 청크 경계를 공통 코드가 소유해야 합니다.
 * 저장과 진행 기록을 한 트랜잭션에 묶는 일을 수집기마다 각자 하게 두면
 * 셋이 똑같이 틀릴 수 있습니다.
 * 뒤집어 놓으면 트랜잭션과 해시 계산이 한 곳에만 있습니다.
 *
 * 전량을 한 번에 얻는 소스도 손해를 보지 않습니다.
 * 그냥 잘라서 여러 번 넘기면 되고, 오히려 13,000건을 한 트랜잭션에 담지 않게 됩니다.
 *
 * 구현체는 스프링 빈으로 등록합니다. 실행기가 source 로 찾아 씁니다.
 */
public interface SourceCollector {

    /**
     * 이 수집기가 맡은 소스입니다.
     */
    SourceType source();

    /**
     * 원본을 읽어 청크 단위로 넘깁니다.
     *
     * 넘긴 청크는 그 자리에서 저장됩니다.
     * 따라서 이 메서드가 중간에 끊겨도 그때까지 넘긴 것은 남습니다.
     *
     * 호출할 때마다 context.recordCall 로 기록합니다.
     * 쿼터를 다 쓰면 기록을 마친 뒤 QuotaExhaustedException 을 던집니다.
     * 실행기가 그것을 잡아 실행을 QUOTA_STOPPED 로 마감하고,
     * 다음 실행이 context.cursorOf 로 이어받습니다.
     *
     * @param context   실행 종류와 재개 지점을 읽고 진행을 기록하는 통로
     * @param chunkSink 청크 하나를 넘기는 곳. 비어 있으면 넘기지 않아도 됨
     */
    void collect(CollectionContext context, Consumer<List<RawDocumentDraft>> chunkSink);
}
