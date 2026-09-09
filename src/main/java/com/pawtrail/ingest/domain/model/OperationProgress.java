package com.pawtrail.ingest.domain.model;

/**
 * 오퍼레이션 하나의 진행 상태입니다.
 *
 * ingest_run.progress 의 값 타입이며 오퍼레이션 이름을 열쇠로 담깁니다.
 *
 * <pre>
 * { "detailPetTour2": { "count": 1000, "cursor": "1080" },
 *   "detailCommon2":  { "count":  743, "cursor":  "743" } }
 * </pre>
 *
 * 단일 카운터로 두지 않은 이유는 쿼터가 오퍼레이션마다 따로 걸리기 때문입니다.
 * 관광공사 상세 셋은 각각 하루 1,000건이고 대상이 1,080건이라
 * 한 실행에서 셋이 각자 한도에 닿습니다.
 * 합계만 남기면 어느 것이 걸렸는지 알 수 없어 재개 지점을 정할 수 없습니다.
 *
 * 불변입니다. 값을 바꿀 때는 새 인스턴스를 만들어 Map 에 다시 넣습니다.
 * Hibernate 가 JSON 컬럼의 변경을 알아채려면 Map 자체가 새것이어야 하므로
 * 어차피 통째로 갈아끼우게 됩니다.
 *
 * @param count  이 실행에서 이 오퍼레이션을 부른 횟수. 쿼터 소모량임
 * @param cursor 다음에 이어받을 지점. 페이지 번호나 처리한 마지막 식별자이며
 *               형식은 수집기가 정함. 아직 시작 전이면 null
 */
public record OperationProgress(int count, String cursor) {

    public static OperationProgress start() {
        return new OperationProgress(0, null);
    }

    /**
     * 한 번 더 부른 것을 기록합니다.
     */
    public OperationProgress advance(String nextCursor) {
        return new OperationProgress(count + 1, nextCursor);
    }
}
