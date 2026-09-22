package com.pawtrail.ingest.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawtrail.ingest.application.service.IngestRunLauncher;
import com.pawtrail.ingest.domain.enums.SourceType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 매일 예약이 스위치를 지키고, 한 소스가 실패해도 다음 소스를 거는지 봅니다.
 *
 * 스위치를 지키지 않으면 개발 PC 에서 모르는 사이 공사 API 허용량이 새고,
 * 한 소스의 실패가 다음 소스를 막으면 그 API 의 호출 이력이 조용히 끊깁니다.
 */
@ExtendWith(MockitoExtension.class)
class IngestSchedulerTest {

    @Mock
    private IngestRunLauncher ingestRunLauncher;

    private IngestScheduler scheduler(boolean enabled) {
        return new IngestScheduler(ingestRunLauncher, enabled, IngestScheduler.DEFAULT_CRON);
    }

    @Test
    @DisplayName("스위치가 꺼져 있으면 아무것도 걸지 않는다")
    void doesNothingWhenDisabled() {
        scheduler(false).runDaily();

        verify(ingestRunLauncher, never()).launchScheduled(any());
    }

    @Test
    @DisplayName("켜져 있으면 공사 API 소스를 선언 순서대로 건다")
    void launchesTourApiSourcesInOrder() {
        when(ingestRunLauncher.launchScheduled(SourceType.PET_TOUR))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(ingestRunLauncher.launchScheduled(SourceType.GOCAMPING))
                .thenReturn(Optional.of(UUID.randomUUID()));

        scheduler(true).runDaily();

        InOrder order = inOrder(ingestRunLauncher);
        order.verify(ingestRunLauncher).launchScheduled(SourceType.PET_TOUR);
        order.verify(ingestRunLauncher).launchScheduled(SourceType.GOCAMPING);
    }

    @Test
    @DisplayName("한 소스가 실패해도 다음 소스는 건다")
    void continuesAfterFailure() {
        when(ingestRunLauncher.launchScheduled(SourceType.PET_TOUR))
                .thenThrow(new IllegalStateException("수집기 없음"));
        when(ingestRunLauncher.launchScheduled(SourceType.GOCAMPING))
                .thenReturn(Optional.of(UUID.randomUUID()));

        scheduler(true).runDaily();

        // 둘은 서로 다른 API 라 한쪽 문제가 다른 쪽 호출 이력까지 끊으면 안 됨
        verify(ingestRunLauncher).launchScheduled(SourceType.GOCAMPING);
    }
}
