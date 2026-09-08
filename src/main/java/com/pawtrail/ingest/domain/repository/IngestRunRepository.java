package com.pawtrail.ingest.domain.repository;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import java.util.Optional;
import java.util.UUID;

/**
 * 수집 실행 기록을 저장하고 찾아오는 약속입니다.
 */
public interface IngestRunRepository {

    IngestRun save(IngestRun ingestRun);

    Optional<IngestRun> findById(UUID id);

    /**
     * 그 소스가 지금 실행 중인지 봅니다.
     *
     * 수집은 비동기라 트리거를 두 번 부르면 같은 소스가 나란히 돌 수 있습니다.
     * 그러면 같은 쿼터를 두 배로 쓰고 둘 다 한도에 못 미쳐 멈춰,
     * 어느 쪽도 끝내지 못한 채 그날 몫이 사라집니다.
     *
     * 완벽한 방어는 아닙니다.
     * 두 요청이 같은 순간에 들어오면 둘 다 없음을 보고 지나갈 수 있습니다.
     * 부르는 것이 Jenkins 잡 하나이고 사람이 승인해서 누르는 경로라
     * 같은 밀리초에 두 번 눌릴 상황이 실제로 없어 그 확률을 받아들입니다.
     *
     * 유일 제약으로 막지 않는 이유는 실행 기록이 소스마다 여러 건 쌓이는 것이 정상이기 때문입니다.
     * 막으려면 상태가 RUNNING 인 행만 대상으로 하는 부분 유일 제약이 필요하고,
     * 그것은 마이그레이션을 하나 더 만드는 일입니다.
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
}
