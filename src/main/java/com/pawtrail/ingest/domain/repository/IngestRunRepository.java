package com.pawtrail.ingest.domain.repository;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 수집 실행 기록을 저장하고 찾아오는 약속입니다.
 */
public interface IngestRunRepository {

    IngestRun save(IngestRun ingestRun);

    /**
     * 저장하고 그 자리에서 데이터베이스에 반영합니다.
     *
     * 그냥 저장하면 반영이 트랜잭션이 끝날 때까지 미뤄질 수 있습니다.
     * 그러면 uq_ingest_run_running 에 부딪히는 시점도 함께 미뤄져,
     * 예외가 저장을 감싼 자리 밖에서 나고 우리 오류로 바뀌지 못합니다.
     *
     * 같은 소스가 이미 실행 중인 것은 흔한 상황이라
     * 부르는 쪽이 그것을 알아볼 수 있는 형태로 받아야 합니다.
     */
    IngestRun saveAndFlush(IngestRun ingestRun);

    Optional<IngestRun> findById(UUID id);

    /**
     * 그 소스가 지금 실행 중인지 봅니다.
     *
     * 수집은 비동기라 트리거를 두 번 부르면 같은 소스가 나란히 돌 수 있습니다.
     * 그러면 같은 쿼터를 두 배로 쓰고 둘 다 한도에 못 미쳐 멈춰,
     * 어느 쪽도 끝내지 못한 채 그날 몫이 사라집니다.
     *
     * 이 조회만으로는 두 요청을 갈라 놓지 못합니다.
     * 같은 순간에 들어오면 둘 다 없음을 보고 지나가므로
     * 저장 시점에 유일 인덱스가 한 번 더 막습니다.
     * 그래도 이 조회를 두는 이유는 대부분이 여기서 걸러지고 응답도 자연스럽기 때문입니다.
     */
    boolean existsRunningBySource(SourceType source);

    /**
     * 그 소스의 바로 앞 실행을 찾습니다. 지금 실행은 빼고 봅니다.
     *
     * 쿼터로 멈춘 실행을 이어받으려면 그쪽이 어디까지 처리했는지를 알아야 합니다.
     * 상태를 조건에 넣지 않고 바로 앞 실행을 그대로 돌려주는 이유가 있습니다.
     *
     * 쿼터로 멈춘 실행이 있어도 그 뒤에 전량 수집이 한 번 성공했다면
     * 그 재개 지점은 이미 지난 이야기입니다.
     * 상태로 걸러 찾으면 더 오래된 것을 집어 와 엉뚱한 자리에서 이어받게 됩니다.
     *
     * 이어받을지 말지는 실행기가 상태를 보고 정합니다.
     * 저장소는 무엇이 있었는지만 알려줍니다.
     */
    Optional<IngestRun> findPreviousRun(SourceType source, UUID currentRunId);

    /**
     * 최근 실행을 새것부터 돌려줍니다.
     *
     * 사람이 승인을 판단하는 화면이 씁니다.
     * 감지는 스케줄이 하고 실행은 사람이 승인한다는 방침이라,
     * 무엇이 얼마나 바뀌었는지를 보고 다음 실행을 부를지 정합니다.
     *
     * 쪽 번호로 넘기지 않습니다.
     * 실행은 하루에 몇 건씩 쌓이므로 한 해가 지나도 수백 건입니다.
     * 필요해지면 그때 넣는 편이 낫습니다.
     *
     * @param source 이 소스만 봅니다. null 이면 전부 봅니다
     * @param size   몇 건까지 볼지
     */
    List<IngestRun> findRecent(SourceType source, int size);
}
