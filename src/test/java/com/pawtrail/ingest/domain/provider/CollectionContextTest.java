package com.pawtrail.ingest.domain.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.model.OperationProgress;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이어받기 규칙은 어긋나도 오류가 나지 않습니다.
 * 재개 지점을 잃으면 처음부터 다시 받고, 호출 수를 물려받으면 아무것도 못 받는데
 * 둘 다 조용히 일어나므로 규칙을 테스트로 못 박아 둡니다.
 */
class CollectionContextTest {

    private static final String OPERATION = "detailPetTour2";

    @Test
    @DisplayName("처음 시작하면 재개 지점이 없고 호출 수가 0 이다")
    void startsEmpty() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);

        assertThat(context.cursorOf(OPERATION)).isNull();
        assertThat(context.countOf(OPERATION)).isZero();
    }

    @Test
    @DisplayName("이어받으면 재개 지점은 가져오고 호출 수는 0 부터 다시 센다")
    void resumesCursorButNotCount() {
        Map<String, OperationProgress> previous =
                Map.of(OPERATION, new OperationProgress(1000, "1080"));

        CollectionContext context = CollectionContext.resumeFrom(RunType.INCREMENTAL, previous);

        assertThat(context.cursorOf(OPERATION)).isEqualTo("1080");
        assertThat(context.countOf(OPERATION)).isZero();
    }

    @Test
    @DisplayName("앞 실행이 없으면 처음부터 시작한 것과 같다")
    void resumesFromNothing() {
        CollectionContext context = CollectionContext.resumeFrom(RunType.FULL, null);

        assertThat(context.cursorOf(OPERATION)).isNull();
        assertThat(context.countOf(OPERATION)).isZero();
    }

    @Test
    @DisplayName("호출을 기록하면 수가 오르고 재개 지점이 바뀐다")
    void recordsCall() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);

        context.recordCall(OPERATION, "100");
        context.recordCall(OPERATION, "200");

        assertThat(context.countOf(OPERATION)).isEqualTo(2);
        assertThat(context.cursorOf(OPERATION)).isEqualTo("200");
    }

    @Test
    @DisplayName("오퍼레이션마다 따로 센다")
    void countsPerOperation() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);

        context.recordCall(OPERATION, "10");
        context.recordCall("detailCommon2", "20");
        context.recordCall("detailCommon2", "30");

        assertThat(context.countOf(OPERATION)).isEqualTo(1);
        assertThat(context.countOf("detailCommon2")).isEqualTo(2);
    }

    @Test
    @DisplayName("스냅샷을 고쳐도 원래 상태는 그대로다")
    void snapshotIsDetached() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);
        context.recordCall(OPERATION, "10");

        Map<String, OperationProgress> snapshot = context.snapshot();
        snapshot.clear();

        assertThat(context.countOf(OPERATION)).isEqualTo(1);
    }
}
