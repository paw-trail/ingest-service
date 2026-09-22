package com.pawtrail.ingest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 기동 때 끝내지 못한 실행을 재개 지점을 물려주지 않는 실패로 마감하는지 봅니다.
 *
 * 남겨 두면 그 소스의 새 실행이 계속 409 로 막히고 매일 예약도 조용히 건너뜁니다.
 * 이어받는 상태로 마감하면 까닭을 모르는 멈춤의 재개 지점을 다음 실행이 그대로 물려받습니다.
 */
@ExtendWith(MockitoExtension.class)
class OrphanRunCleanerTest {

    @Mock
    private IngestRunRepository ingestRunRepository;

    @Test
    @DisplayName("실행 중으로 남은 기록을 전부 실패로 마감하고 까닭을 남긴다")
    void failsAllRunning() {
        IngestRun petTour = IngestRun.start(SourceType.PET_TOUR, RunType.INCREMENTAL);
        IngestRun goCamping = IngestRun.start(SourceType.GOCAMPING, RunType.FULL);
        when(ingestRunRepository.findAllRunning()).thenReturn(List.of(petTour, goCamping));

        new OrphanRunCleaner(ingestRunRepository).cleanUp();

        assertThat(List.of(petTour, goCamping)).allSatisfy(run -> {
            // 재개 지점을 물려주는 QUOTA_STOPPED · INTERRUPTED 가 아니어야 함
            assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(run.getErrorMessage()).isEqualTo(OrphanRunCleaner.MESSAGE);
            assertThat(run.getFinishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("정리할 것이 없으면 조용히 지나간다")
    void passesWhenNothingRunning() {
        when(ingestRunRepository.findAllRunning()).thenReturn(List.of());

        new OrphanRunCleaner(ingestRunRepository).cleanUp();

        // 한 번 묻고 끝남 — 저장소의 다른 것을 건드리지 않음
        verify(ingestRunRepository).findAllRunning();
        verifyNoMoreInteractions(ingestRunRepository);
    }
}
