package com.pawtrail.ingest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 기동 때 앞 프로세스가 끝내지 못한 실행을 재개 지점을 물려주지 않는 실패로 마감하는지 봅니다.
 *
 * 남겨 두면 그 소스의 새 실행이 계속 409 로 막히고 매일 예약도 조용히 건너뜁니다.
 * 이어받는 상태로 마감하면 까닭을 모르는 멈춤의 재개 지점을 다음 실행이 그대로 물려받습니다.
 * 기준 시각을 정리할 때 정하면 그 사이에 이 프로세스가 건 실행까지 마감합니다.
 */
@ExtendWith(MockitoExtension.class)
class OrphanRunCleanerTest {

    @Mock
    private IngestRunRepository ingestRunRepository;

    @Test
    @DisplayName("앞 프로세스가 남긴 실행 중 기록을 전부 실패로 마감하고 까닭을 남긴다")
    void failsRunningLeftByPreviousProcess() {
        IngestRun petTour = IngestRun.start(SourceType.PET_TOUR, RunType.INCREMENTAL);
        IngestRun goCamping = IngestRun.start(SourceType.GOCAMPING, RunType.FULL);
        when(ingestRunRepository.findAllRunningStartedBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(petTour, goCamping));

        new OrphanRunCleaner(ingestRunRepository).cleanUp();

        assertThat(List.of(petTour, goCamping)).allSatisfy(run -> {
            // 재개 지점을 물려주는 QUOTA_STOPPED · INTERRUPTED 가 아니어야 함
            assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(run.getErrorMessage()).isEqualTo(OrphanRunCleaner.MESSAGE);
            assertThat(run.getFinishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("기준 시각은 정리할 때가 아니라 빈을 만들 때 정한다")
    void usesTheTimeTheBeanWasCreated() throws InterruptedException {
        LocalDateTime beforeCreate = LocalDateTime.now();
        OrphanRunCleaner cleaner = new OrphanRunCleaner(ingestRunRepository);
        LocalDateTime afterCreate = LocalDateTime.now();
        when(ingestRunRepository.findAllRunningStartedBefore(any(LocalDateTime.class))).thenReturn(List.of());

        // 기동 완료 이벤트는 빈을 만든 뒤 한참 있다가 옴 — 기준이 이 시각으로 밀리면 안 됨
        Thread.sleep(20);
        cleaner.cleanUp();

        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ingestRunRepository).findAllRunningStartedBefore(before.capture());
        assertThat(before.getValue()).isBetween(beforeCreate, afterCreate);
    }

    @Test
    @DisplayName("정리할 것이 없으면 조용히 지나간다")
    void passesWhenNothingRunning() {
        when(ingestRunRepository.findAllRunningStartedBefore(any(LocalDateTime.class))).thenReturn(List.of());

        new OrphanRunCleaner(ingestRunRepository).cleanUp();

        // 한 번 묻고 끝남 — 저장소의 다른 것을 건드리지 않음
        verify(ingestRunRepository).findAllRunningStartedBefore(any(LocalDateTime.class));
        verifyNoMoreInteractions(ingestRunRepository);
    }
}
