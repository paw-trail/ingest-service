package com.pawtrail.ingest.domain.provider;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.model.OperationProgress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집기가 실행 상태를 읽고 진행을 기록하는 통로입니다.
 *
 * 실행 엔티티를 그대로 넘기지 않는 이유는 둘입니다.
 * 수집은 트랜잭션 밖에서 오래 도는 작업이라 그 사이 엔티티를 들고 있으면
 * 준영속 상태로 오래 남고, 수집기가 완료나 실패 같은 상태 전이까지 만질 수 있게 됩니다.
 * 상태 전이는 실행기 한 곳에서만 일어나야 합니다.
 *
 * 진행 상태에는 성격이 다른 값 둘이 함께 있습니다.
 * 호출 수는 허용량을 얼마나 썼는지이고 재개 지점은 어디까지 저장했는지입니다.
 * 둘이 함께 움직이지 않으므로 기록하는 메서드도 갈라 두었습니다.
 *
 * 스레드 안전하지 않습니다.
 * 실행 하나가 한 스레드에서 도는 것을 전제로 합니다.
 * 병렬 수집이 필요해지면 이 클래스부터 다시 봐야 합니다.
 */
public final class CollectionContext {

    private final RunType runType;
    private final Map<String, OperationProgress> progress;

    /**
     * 끝내 실패해서 건너뛴 것들입니다.
     *
     * 실행 기록에 남겨 나중에 무엇이 빠졌는지 찾아볼 수 있게 합니다.
     * 건너뛴 항목은 다음 전량 수집이 알아서 다시 집으므로 따로 복구할 것은 없습니다.
     */
    private final List<String> skipped = new ArrayList<>();

    private CollectionContext(RunType runType, Map<String, OperationProgress> progress) {
        this.runType = runType;
        this.progress = progress;
    }

    /**
     * 처음부터 시작합니다.
     */
    public static CollectionContext startFresh(RunType runType) {
        return new CollectionContext(runType, new HashMap<>());
    }

    /**
     * 앞 실행이 멈춘 자리를 이어받습니다.
     *
     * 재개 지점만 가져오고 호출 수는 0 부터 다시 셉니다.
     *
     * 호출 수를 이어받으면 안 되는 이유가 여기 있습니다.
     * 그 값은 이 실행에서 몇 번 불렀는지를 뜻하고 수집기가 그것으로 한도를 판단합니다.
     * 앞 실행의 1,000 을 그대로 물려받으면 날이 바뀌어 한도가 되살아났는데도
     * 첫 호출부터 한도를 넘긴 것으로 보고 아무것도 못 합니다.
     *
     * 반대로 재개 지점은 반드시 물려받아야 합니다.
     * 없으면 처음부터 다시 받게 되고, 이미 쓴 쿼터를 한 번 더 쓰는 셈입니다.
     * 되돌릴 수 없는 자원이라 그 낭비가 그날 몫을 통째로 날릴 수 있습니다.
     *
     * @param previous 앞 실행의 진행 상태. 비어 있으면 처음부터 시작한 것과 같음
     */
    public static CollectionContext resumeFrom(
            RunType runType, Map<String, OperationProgress> previous) {

        Map<String, OperationProgress> seeded = new HashMap<>();
        if (previous != null) {
            previous.forEach((operation, point) ->
                    seeded.put(operation, new OperationProgress(0, point.cursor())));
        }
        return new CollectionContext(runType, seeded);
    }

    public RunType runType() {
        return runType;
    }

    /**
     * 그 오퍼레이션을 이 실행에서 몇 번 불렀는지 알려줍니다.
     *
     * 쿼터가 오퍼레이션마다 따로 걸리므로 수집기가 이 값으로 한도를 판단합니다.
     * 이어받은 실행이면 앞 실행의 값이 아니라 이번 실행의 값입니다.
     */
    public int countOf(String operation) {
        OperationProgress current = progress.get(operation);
        return current == null ? 0 : current.count();
    }

    /**
     * 그 오퍼레이션을 어디까지 처리했는지 알려줍니다.
     *
     * 쿼터로 멈춘 실행을 이어받으면 앞 실행이 남긴 자리부터 시작합니다.
     * 형식은 수집기가 정합니다. 페이지 번호일 수도 마지막 식별자일 수도 있습니다.
     * 처음 시작하는 것이면 null 입니다.
     */
    public String cursorOf(String operation) {
        OperationProgress current = progress.get(operation);
        return current == null ? null : current.cursor();
    }

    /**
     * 그 오퍼레이션을 한 번 부른 것을 기록합니다. 재개 지점은 건드리지 않습니다.
     *
     * 호출은 한 건마다 일어나는데 저장은 여러 건을 모아 한 번에 하므로,
     * 부를 때마다 재개 지점을 옮기면 아직 저장되지 않은 것을 처리한 것으로 기록하게 됩니다.
     * 그러면 이어받을 때 그 자리를 건너뜁니다.
     *
     * 실패해서 넘어가는 호출도 반드시 기록합니다.
     * 응답을 못 받았어도 허용량은 이미 쓴 것이라,
     * 빠뜨리면 다음 실행이 아직 안 썼다고 판단해 그날 몫을 날립니다.
     */
    public void recordCall(String operation) {
        OperationProgress current = progress.getOrDefault(operation, OperationProgress.start());
        progress.put(operation, current.advance(current.cursor()));
    }

    /**
     * 한 번 부른 것을 기록하면서 재개 지점도 함께 옮깁니다.
     *
     * 한 번 부른 것이 곧 한 번 저장하는 것일 때만 씁니다.
     * 목록을 쪽 단위로 받아 그대로 저장하는 소스가 그렇습니다.
     * 부르는 것과 저장하는 것이 갈리는 소스는 위의 한 인자짜리와
     * 아래 markCursor 를 나누어 씁니다.
     *
     * @param nextCursor 다음에 이어받을 지점. 아직 정할 수 없으면 지금 값을 그대로 넘김
     */
    public void recordCall(String operation, String nextCursor) {
        OperationProgress current = progress.getOrDefault(operation, OperationProgress.start());
        progress.put(operation, current.advance(nextCursor));
    }

    /**
     * 재개 지점을 옮깁니다. 호출 수는 건드리지 않습니다.
     *
     * 저장할 것을 넘기기 직전에 부릅니다.
     * 그 값은 그 청크에 담아 함께 커밋할 것의 마지막이어야 합니다.
     * 앞서 나가면 저장되지 않은 것을 처리한 것으로 기록해 이어받을 때 건너뜁니다.
     *
     * 오퍼레이션을 여럿 받는 이유가 있습니다.
     * 한 장소에 상세를 셋 부르는데 하나라도 실패하면 그 장소를 통째로 버리므로,
     * 셋의 재개 지점은 언제나 같은 값이어야 합니다.
     * 따로 옮기게 두면 어느 하나만 앞서 나가는 실수가 생기고,
     * 그 실수는 다음 날 이어받을 때에야 드러납니다.
     */
    public void markCursor(String cursor, String... operations) {
        for (String operation : operations) {
            OperationProgress current =
                    progress.getOrDefault(operation, OperationProgress.start());
            progress.put(operation, new OperationProgress(current.count(), cursor));
        }
    }

    /**
     * 끝내 실패해서 건너뛴 것을 남깁니다.
     *
     * 흩어진 실패는 소스 사정이라 그 건만 건너뛰고 계속하는 편이 낫습니다.
     * 한 건 때문에 그날 받아 둔 것을 통째로 버릴 이유가 없습니다.
     * 대신 무엇이 빠졌는지는 남아야 합니다.
     */
    public void recordSkip(String sourceId) {
        skipped.add(sourceId);
    }

    /**
     * 건너뛴 것들을 돌려줍니다. 실행기가 실행 기록에 남깁니다.
     */
    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    /**
     * 지금까지의 진행 상태를 실행 기록에 옮겨 담을 형태로 돌려줍니다.
     */
    public Map<String, OperationProgress> snapshot() {
        return new HashMap<>(progress);
    }
}
