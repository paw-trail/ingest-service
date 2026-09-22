package com.pawtrail.ingest.infrastructure.scheduler;

import com.pawtrail.ingest.application.service.IngestRunLauncher;
import com.pawtrail.ingest.domain.enums.SourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 정해진 시각에 한국관광공사 OpenAPI 수집을 겁니다.
 *
 * 관리자 화면의 「최신 수집 실행」 과 같은 규칙을 거칩니다. IngestRunLauncher 를 부르기 때문입니다.
 * 반려동물 동반여행은 증분, 고캠핑은 목록 한 번을 겁니다. 수집은 원문 적재에서 끝납니다.
 *
 * *켜기 스위치가 있고 기본은 꺼짐입니다
 *  개발 PC 에서 켜진 줄 모르고 돌면 모르는 사이 공사 API 허용량이 샙니다.
 *  배포 서버의 설정에서만 켭니다. 켜졌는지는 기동 로그 한 줄로 보입니다.
 *  search 의 매일 재색인에는 스위치가 없습니다. 그쪽은 바깥을 부르지 않아 아낄 허용량이 없습니다.
 *
 * *코드에 둔 까닭
 *  처음에는 Jenkins 잡이 /internal 트리거를 부르게 할 생각이었습니다.
 *  Jenkins 가 아직 없고, 예약과 관리자 입구가 같은 규칙을 한 곳에서 쓰게 하려고 코드에 두었습니다.
 *
 * *한 대로 뜬다고 봅니다
 *  두 대가 되면 두 대가 같은 시각에 겁니다.
 *  뒤에 건 쪽은 실행 중 검사나 10분 잠금에 걸려 건너뛰므로 호출이 두 배가 되지는 않습니다.
 */
@Slf4j
@Component
public class IngestScheduler {

    // 매일 04:00 — search 의 매일 재색인과 같은 시각
    // 이 수집은 원문 적재에서 끝나 색인과 부딪히지 않음
    static final String DEFAULT_CRON = "0 0 4 * * *";

    static final String ZONE = "Asia/Seoul";

    private final IngestRunLauncher ingestRunLauncher;
    private final boolean enabled;
    private final String cron;

    public IngestScheduler(
            IngestRunLauncher ingestRunLauncher,
            @Value("${app.ingest.schedule.enabled:false}") boolean enabled,
            @Value("${app.ingest.schedule.cron:" + DEFAULT_CRON + "}") String cron) {

        this.ingestRunLauncher = ingestRunLauncher;
        this.enabled = enabled;
        this.cron = cron;
    }

    /**
     * 기동 때 예약이 켜졌는지 한 줄 남깁니다.
     *
     * 스위치를 빠뜨리면 배포본에서 예약이 조용히 안 돕니다.
     * 기동 로그만 보고도 알 수 있게 켜짐과 꺼짐을 둘 다 남깁니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        if (enabled) {
            log.info("예약 수집 켜짐. cron={} zone={} sources={}", cron, ZONE, SourceType.tourApiSources());
        } else {
            log.info("예약 수집 꺼짐. app.ingest.schedule.enabled=false — 배포 서버에서만 켭니다");
        }
    }

    /**
     * 공사 API 소스를 차례로 겁니다.
     *
     * 한 소스가 실패해도 다음 소스는 겁니다.
     * 둘은 서로 다른 API 라 한쪽 문제가 다른 쪽 호출 이력까지 끊을 까닭이 없습니다.
     * 잠금에 걸린 것은 실패가 아니라 건너뜀이며 IngestRunLauncher 가 로그를 남깁니다.
     */
    @Scheduled(cron = "${app.ingest.schedule.cron:" + DEFAULT_CRON + "}", zone = ZONE)
    public void runDaily() {
        if (!enabled) {
            return;
        }

        for (SourceType source : SourceType.tourApiSources()) {
            try {
                ingestRunLauncher.launchScheduled(source);
            } catch (RuntimeException e) {
                log.error("예약 수집을 걸지 못했습니다. source={}", source, e);
            }
        }
    }
}
