package com.pawtrail.ingest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 관리자 입구와 매일 예약이 같은 규칙으로 막히는지 봅니다.
 *
 * 규칙이 어긋나면 막아야 할 호출이 새어 공사 API 허용량을 쓰거나,
 * 통과해야 할 요청이 막혀 관리자 버튼이 먹지 않습니다.
 * 둘 다 화면에서야 드러나므로 여기서 못 박아 둡니다.
 */
@ExtendWith(MockitoExtension.class)
class IngestRunLauncherTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(10);

    @Mock
    private IngestTriggerService ingestTriggerService;

    @Mock
    private IngestExecutor ingestExecutor;

    @Mock
    private IngestRunRepository ingestRunRepository;

    private IngestRunLauncher launcher() {
        return new IngestRunLauncher(ingestTriggerService, ingestExecutor, ingestRunRepository, COOLDOWN);
    }

    @Nested
    class 조합 {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"PET_TOUR, INCREMENTAL", "GOCAMPING, FULL"})
        @DisplayName("공사 API 소스를 평소 방법으로 거는 두 조합은 실행을 만들고 뒤에서 돌린다")
        void acceptsRoutineCollect(SourceType source, RunType runType) {
            UUID runId = UUID.randomUUID();
            when(ingestRunRepository.existsCollectFinishedSince(eq(source), any())).thenReturn(false);
            when(ingestTriggerService.startRun(source, runType)).thenReturn(runId);

            assertThat(launcher().launch(source, runType)).isEqualTo(runId);
            verify(ingestExecutor).execute(runId);
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "PET_TOUR, FULL",
                "PET_TOUR, LINK",
                "GOCAMPING, INCREMENTAL",
                "GOCAMPING, LINK",
                "CULTURE_CSV, FULL",
                "CULTURE_CSV, LINK",
                "MOIS_VET, DIRECT"})
        @DisplayName("나머지 조합은 잠금을 보기 전에 400 으로 거절한다")
        void rejectsOtherCombinations(SourceType source, RunType runType) {
            assertThatThrownBy(() -> launcher().launch(source, runType))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", IngestErrorCode.INGEST_RUN_NOT_ALLOWED);

            // 안 되는 조합에 「잠시 후 다시」 가 나가면 기다려도 안 된다는 것을 모름
            verify(ingestRunRepository, never()).existsCollectFinishedSince(any(), any());
            verify(ingestTriggerService, never()).startRun(any(), any());
            verify(ingestExecutor, never()).execute(any());
        }
    }

    @Nested
    class 잠금 {

        @Test
        @DisplayName("같은 소스의 받아 오기가 잠금 시간 안에 끝났으면 429 로 막고 실행을 만들지 않는다")
        void rejectsWithinCooldown() {
            when(ingestRunRepository.existsCollectFinishedSince(eq(SourceType.PET_TOUR), any()))
                    .thenReturn(true);

            assertThatThrownBy(() -> launcher().launch(SourceType.PET_TOUR, RunType.INCREMENTAL))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", IngestErrorCode.INGEST_COOLDOWN);

            verify(ingestTriggerService, never()).startRun(any(), any());
            verify(ingestExecutor, never()).execute(any());
        }

        @Test
        @DisplayName("잠금은 지금에서 잠금 시간만큼 앞선 시각부터 센다")
        void countsFromNowMinusCooldown() {
            when(ingestRunRepository.existsCollectFinishedSince(eq(SourceType.GOCAMPING), any()))
                    .thenReturn(false);
            when(ingestTriggerService.startRun(SourceType.GOCAMPING, RunType.FULL))
                    .thenReturn(UUID.randomUUID());

            LocalDateTime before = LocalDateTime.now();
            launcher().launch(SourceType.GOCAMPING, RunType.FULL);
            LocalDateTime after = LocalDateTime.now();

            ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(ingestRunRepository).existsCollectFinishedSince(eq(SourceType.GOCAMPING), since.capture());
            assertThat(since.getValue()).isBetween(before.minus(COOLDOWN), after.minus(COOLDOWN));
        }

        @Test
        @DisplayName("같은 소스가 돌고 있으면 트리거 서비스의 409 를 그대로 올리고 실행기를 부르지 않는다")
        void passesThroughAlreadyRunning() {
            when(ingestRunRepository.existsCollectFinishedSince(eq(SourceType.PET_TOUR), any()))
                    .thenReturn(false);
            when(ingestTriggerService.startRun(SourceType.PET_TOUR, RunType.INCREMENTAL))
                    .thenThrow(new CustomException(IngestErrorCode.INGEST_ALREADY_RUNNING));

            assertThatThrownBy(() -> launcher().launch(SourceType.PET_TOUR, RunType.INCREMENTAL))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", IngestErrorCode.INGEST_ALREADY_RUNNING);

            verify(ingestExecutor, never()).execute(any());
        }

        @Test
        @DisplayName("잠금 시간이 음수면 기동 때 멈춘다")
        void rejectsNegativeCooldown() {
            // 음수면 잠금이 조용히 풀려 누를 때마다 돎
            assertThatThrownBy(() -> new IngestRunLauncher(
                    ingestTriggerService, ingestExecutor, ingestRunRepository, Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class 예약 {

        @Test
        @DisplayName("예약이 거는 소스는 공사 API 로 받는 반려동물 동반여행과 고캠핑 둘이다")
        void schedulesTourApiSources() {
            assertThat(SourceType.tourApiSources())
                    .containsExactly(SourceType.PET_TOUR, SourceType.GOCAMPING);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"PET_TOUR, INCREMENTAL", "GOCAMPING, FULL"})
        @DisplayName("소스마다 평소 방법으로 건다")
        void launchesRoutineRunType(SourceType source, RunType expected) {
            UUID runId = UUID.randomUUID();
            when(ingestRunRepository.existsCollectFinishedSince(eq(source), any())).thenReturn(false);
            when(ingestTriggerService.startRun(source, expected)).thenReturn(runId);

            assertThat(launcher().launchScheduled(source)).contains(runId);
            verify(ingestExecutor).execute(runId);
        }

        @Test
        @DisplayName("잠금 시간 안이면 건너뛰고 예외를 올리지 않는다")
        void skipsWithinCooldown() {
            when(ingestRunRepository.existsCollectFinishedSince(eq(SourceType.PET_TOUR), any()))
                    .thenReturn(true);

            assertThat(launcher().launchScheduled(SourceType.PET_TOUR)).isEmpty();
            verify(ingestTriggerService, never()).startRun(any(), any());
        }

        @Test
        @DisplayName("이미 돌고 있으면 건너뛰고 예외를 올리지 않는다")
        void skipsWhenAlreadyRunning() {
            when(ingestRunRepository.existsCollectFinishedSince(eq(SourceType.GOCAMPING), any()))
                    .thenReturn(false);
            when(ingestTriggerService.startRun(SourceType.GOCAMPING, RunType.FULL))
                    .thenThrow(new CustomException(IngestErrorCode.INGEST_ALREADY_RUNNING));

            assertThat(launcher().launchScheduled(SourceType.GOCAMPING)).isEmpty();
            verify(ingestExecutor, never()).execute(any());
        }

        @Test
        @DisplayName("잠금이 아닌 실패는 그대로 올린다")
        void rethrowsOtherFailures() {
            when(ingestRunRepository.existsCollectFinishedSince(eq(SourceType.PET_TOUR), any()))
                    .thenReturn(false);
            when(ingestTriggerService.startRun(SourceType.PET_TOUR, RunType.INCREMENTAL))
                    .thenThrow(new CustomException(IngestErrorCode.COLLECTOR_NOT_REGISTERED));

            // 고쳐야 나아지는 일이라 조용히 넘기면 예약이 매일 헛돌아도 모름
            assertThatThrownBy(() -> launcher().launchScheduled(SourceType.PET_TOUR))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", IngestErrorCode.COLLECTOR_NOT_REGISTERED);
        }
    }
}
