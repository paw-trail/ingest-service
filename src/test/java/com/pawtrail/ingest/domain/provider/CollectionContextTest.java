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
 *
 * 호출 수와 재개 지점이 따로 움직이는 것도 여기서 못 박습니다.
 * 한 건에 호출을 셋 쓰고 스무 건을 모아 저장하므로 둘이 함께 움직이면
 * 아직 저장되지 않은 것을 처리한 것으로 기록하게 됩니다.
 */
class CollectionContextTest {

    private static final String OPERATION = "detailPetTour2";
    private static final String COMMON = "detailCommon2";
    private static final String INTRO = "detailIntro2";

    @Test
    @DisplayName("처음 시작하면 재개 지점이 없고 호출 수가 0 이다")
    void startsEmpty() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);

        assertThat(context.cursorOf(OPERATION)).isNull();
        assertThat(context.countOf(OPERATION)).isZero();
        assertThat(context.skipped()).isEmpty();
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
    @DisplayName("재개 지점 없이 호출만 기록하면 수만 오른다")
    void recordsCallWithoutMovingCursor() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);
        context.markCursor("500", OPERATION);

        context.recordCall(OPERATION);
        context.recordCall(OPERATION);

        // 한 건에 호출을 셋 쓰고 여러 건을 모아 저장하므로
        // 부를 때마다 재개 지점을 옮기면 저장 안 된 것을 처리한 것으로 기록하게 됨
        assertThat(context.countOf(OPERATION)).isEqualTo(2);
        assertThat(context.cursorOf(OPERATION)).isEqualTo("500");
    }

    @Test
    @DisplayName("재개 지점만 옮기면 호출 수는 그대로다")
    void movesCursorWithoutCountingCall() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);
        context.recordCall(OPERATION);
        context.recordCall(OPERATION);

        context.markCursor("1059479", OPERATION);

        assertThat(context.countOf(OPERATION)).isEqualTo(2);
        assertThat(context.cursorOf(OPERATION)).isEqualTo("1059479");
    }

    @Test
    @DisplayName("여러 오퍼레이션의 재개 지점을 한 번에 옮긴다")
    void movesCursorForSeveralOperations() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);
        context.recordCall(OPERATION);
        context.recordCall(COMMON);

        context.markCursor("1059479", OPERATION, COMMON, INTRO);

        // 한 장소에 상세를 셋 부르는데 하나라도 실패하면 그 장소를 통째로 버리므로
        // 셋의 재개 지점은 언제나 같은 값이어야 함
        assertThat(context.cursorOf(OPERATION)).isEqualTo("1059479");
        assertThat(context.cursorOf(COMMON)).isEqualTo("1059479");
        assertThat(context.cursorOf(INTRO)).isEqualTo("1059479");

        // 아직 한 번도 부르지 않은 것도 재개 지점만 받고 호출 수는 0 임
        assertThat(context.countOf(INTRO)).isZero();
    }

    @Test
    @DisplayName("오퍼레이션마다 따로 센다")
    void countsPerOperation() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);

        context.recordCall(OPERATION, "10");
        context.recordCall(COMMON, "20");
        context.recordCall(COMMON, "30");

        assertThat(context.countOf(OPERATION)).isEqualTo(1);
        assertThat(context.countOf(COMMON)).isEqualTo(2);
    }

    @Test
    @DisplayName("건너뛴 것을 순서대로 남긴다")
    void recordsSkipped() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);

        context.recordSkip("1059479");
        context.recordSkip("2733967");

        assertThat(context.skipped()).containsExactly("1059479", "2733967");
    }

    @Test
    @DisplayName("스냅샷과 건너뛴 목록을 고쳐도 원래 상태는 그대로다")
    void snapshotIsDetached() {
        CollectionContext context = CollectionContext.startFresh(RunType.FULL);
        context.recordCall(OPERATION, "10");
        context.recordSkip("1");

        Map<String, OperationProgress> snapshot = context.snapshot();
        snapshot.clear();

        assertThat(context.countOf(OPERATION)).isEqualTo(1);
        assertThat(context.skipped()).containsExactly("1");
    }
}
