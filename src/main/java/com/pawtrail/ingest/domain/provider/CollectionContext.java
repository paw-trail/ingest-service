package com.pawtrail.ingest.domain.provider;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.model.OperationProgress;
import java.util.HashMap;
import java.util.Map;

/**
 * 수집기가 실행 상태를 읽고 진행을 기록하는 통로입니다.
 *
 * 실행 엔티티를 그대로 넘기지 않는 이유는 둘입니다.
 * 수집은 트랜잭션 밖에서 오래 도는 작업이라 그 사이 엔티티를 들고 있으면
 * 준영속 상태로 오래 남고, 수집기가 완료나 실패 같은 상태 전이까지 만질 수 있게 됩니다.
 * 상태 전이는 실행기 한 곳에서만 일어나야 합니다.
 *
 * 스레드 안전하지 않습니다.
 * 실행 하나가 한 스레드에서 도는 것을 전제로 합니다.
 * 병렬 수집이 필요해지면 이 클래스부터 다시 봐야 합니다.
 */
public final class CollectionContext {

    private final RunType runType;
    private final Map<String, OperationProgress> progress;

    public CollectionContext(RunType runType, Map<String, OperationProgress> initialProgress) {
        this.runType = runType;
        this.progress = initialProgress == null ? new HashMap<>() : new HashMap<>(initialProgress);
    }

    public RunType runType() {
        return runType;
    }

    /**
     * 그 오퍼레이션을 이 실행에서 몇 번 불렀는지 알려줍니다.
     *
     * 쿼터가 오퍼레이션마다 따로 걸리므로 수집기가 이 값으로 한도를 판단합니다.
     * 이어받은 실행이면 지난 실행의 값이 아니라 이번 실행의 값입니다.
     */
    public int countOf(String operation) {
        OperationProgress current = progress.get(operation);
        return current == null ? 0 : current.count();
    }

    /**
     * 그 오퍼레이션을 어디까지 처리했는지 알려줍니다.
     *
     * 쿼터로 멈춘 실행을 이어받을 때 여기서부터 시작합니다.
     * 형식은 수집기가 정합니다. 페이지 번호일 수도 마지막 식별자일 수도 있습니다.
     * 아직 시작 전이면 null 입니다.
     */
    public String cursorOf(String operation) {
        OperationProgress current = progress.get(operation);
        return current == null ? null : current.cursor();
    }

    /**
     * 그 오퍼레이션을 한 번 부른 것을 기록합니다.
     *
     * 호출한 직후에 부르되 그 값이 DB 에 반영되는 시점은 청크 저장과 같습니다.
     * 갈라 두면 저장이 롤백됐는데 호출 수만 오른 상태가 만들어지기 때문입니다.
     *
     * 쿼터를 다 써서 예외를 던지기 전에도 반드시 먼저 기록합니다.
     * 기록을 빠뜨리면 다음 실행이 아직 안 썼다고 판단해 그날 몫을 날립니다.
     *
     * @param nextCursor 다음에 이어받을 지점. 아직 정할 수 없으면 지금 값을 그대로 넘김
     */
    public void recordCall(String operation, String nextCursor) {
        OperationProgress current = progress.getOrDefault(operation, OperationProgress.start());
        progress.put(operation, current.advance(nextCursor));
    }

    /**
     * 지금까지의 진행 상태를 실행 기록에 옮겨 담을 형태로 돌려줍니다.
     */
    public Map<String, OperationProgress> snapshot() {
        return new HashMap<>(progress);
    }
}
