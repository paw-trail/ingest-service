package com.pawtrail.ingest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 관리자 입구 · 매일 예약 · 기동 정리가 쓰는 실행 기록 조회를 실제 데이터베이스로 확인합니다.
 *
 * *모의 객체로는 잡을 수 없는 자리입니다.
 *  어떤 실행 종류를 셀지와 끝난 시각 비교는 쿼리 안에 있어서,
 *  서비스를 아무리 시험해도 조건이 빠지거나 다른 칸을 보는 것을 알아챌 수 없습니다.
 *
 * *실행 중 기록은 소스마다 하나만 둘 수 있습니다(V21 유일 인덱스).
 *  그래서 한 소스로 여러 번 만들 때는 앞 실행을 마감한 뒤에 다음을 만듭니다.
 */
@SpringBootTest
@Testcontainers
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IngestRunQueryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private IngestRunRepository ingestRunRepository;

    @Test
    @DisplayName("10분 잠금은 같은 소스의 받아 오기가 기준 시각 뒤에 끝났을 때만 잡힌다")
    void countsOnlyFinishedCollectOfSameSource() {
        finished(SourceType.PET_TOUR, RunType.INCREMENTAL);
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);

        assertThat(ingestRunRepository.existsCollectFinishedSince(SourceType.PET_TOUR, tenMinutesAgo))
                .isTrue();
        // 소스마다 따로 셈 — 반려동물 동반여행을 막 받았어도 고캠핑은 걸 수 있음
        assertThat(ingestRunRepository.existsCollectFinishedSince(SourceType.GOCAMPING, tenMinutesAgo))
                .isFalse();
        // 기준 시각보다 먼저 끝난 것은 잡히지 않음
        assertThat(ingestRunRepository.existsCollectFinishedSince(
                SourceType.PET_TOUR, LocalDateTime.now().plusMinutes(1)))
                .isFalse();
    }

    @Test
    @DisplayName("넘기기 · 바로 보내기와 아직 도는 실행은 10분 잠금에 잡히지 않는다")
    void ignoresLinkDirectAndRunning() {
        finished(SourceType.PET_TOUR, RunType.LINK);
        finished(SourceType.MOIS_VET, RunType.DIRECT);
        ingestRunRepository.saveAndFlush(IngestRun.start(SourceType.GOCAMPING, RunType.FULL));
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);

        // 넘기기 · 바로 보내기는 바깥을 부르지 않아 허용량과 무관함
        assertThat(ingestRunRepository.existsCollectFinishedSince(SourceType.PET_TOUR, tenMinutesAgo))
                .isFalse();
        assertThat(ingestRunRepository.existsCollectFinishedSince(SourceType.MOIS_VET, tenMinutesAgo))
                .isFalse();
        // 도는 실행은 끝난 시각이 없어 여기서 안 잡히고 실행 중 검사가 막음
        assertThat(ingestRunRepository.existsCollectFinishedSince(SourceType.GOCAMPING, tenMinutesAgo))
                .isFalse();
    }

    @Test
    @DisplayName("관리자 기록은 고른 소스의 받아 오기만 새것부터 담는다")
    void listsCollectRunsOfGivenSourcesNewestFirst() {
        IngestRun petFull = finished(SourceType.PET_TOUR, RunType.FULL);
        finished(SourceType.PET_TOUR, RunType.LINK);
        finished(SourceType.CULTURE_CSV, RunType.FULL);
        finished(SourceType.MOIS_VET, RunType.DIRECT);
        IngestRun gocamping = finished(SourceType.GOCAMPING, RunType.FULL);
        IngestRun petIncremental = finished(SourceType.PET_TOUR, RunType.INCREMENTAL);

        List<IngestRun> runs = ingestRunRepository.findRecentCollect(SourceType.tourApiSources(), 10);

        // 개발하며 돌린 전량도 실제 호출이라 남음 · 넘기기 · 바로 보내기 · 문화정보원은 빠짐
        assertThat(runs)
                .extracting(IngestRun::getId)
                .containsExactly(petIncremental.getId(), gocamping.getId(), petFull.getId());
    }

    @Test
    @DisplayName("관리자 기록은 요청한 개수까지만 담는다")
    void limitsSize() {
        finished(SourceType.PET_TOUR, RunType.INCREMENTAL);
        IngestRun second = finished(SourceType.GOCAMPING, RunType.FULL);
        IngestRun latest = finished(SourceType.PET_TOUR, RunType.INCREMENTAL);

        assertThat(ingestRunRepository.findRecentCollect(SourceType.tourApiSources(), 2))
                .extracting(IngestRun::getId)
                .containsExactly(latest.getId(), second.getId());
    }

    @Test
    @DisplayName("실행 중 기록만 전부 찾는다")
    void findsAllRunning() {
        finished(SourceType.PET_TOUR, RunType.INCREMENTAL);
        IngestRun running = ingestRunRepository.saveAndFlush(
                IngestRun.start(SourceType.GOCAMPING, RunType.FULL));

        assertThat(ingestRunRepository.findAllRunning())
                .extracting(IngestRun::getId)
                .containsExactly(running.getId());
    }

    /**
     * 실행을 만들고 곧바로 마감합니다.
     *
     * 마감까지 반영해 두어야 같은 소스로 다음 실행을 만들 때 유일 인덱스에 부딪히지 않습니다.
     */
    private IngestRun finished(SourceType source, RunType runType) {
        IngestRun run = ingestRunRepository.saveAndFlush(IngestRun.start(source, runType));
        run.complete(Map.of(), null);
        return ingestRunRepository.saveAndFlush(run);
    }
}
