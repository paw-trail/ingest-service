package com.pawtrail.ingest.application.service;

import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스가 뜰 때 끝내지 못한 실행을 마감합니다.
 *
 * 수집 중에 서비스가 꺼지면(배포 · 재시작 · 메모리 부족) 실행 기록이 실행 중으로 남습니다.
 * 실행 중 기록은 소스마다 하나만 둘 수 있어(V21 유일 인덱스)
 * 그 소스의 새 실행이 계속 409 로 막히고 매일 예약도 조용히 건너뜁니다.
 * 관리자 화면에서는 이것을 풀 방법이 없습니다.
 *
 * *뜨는 순간에 정리하는 까닭
 *  끝내지 못한 실행은 프로세스가 죽을 때만 생기므로 다시 뜨는 순간이 곧 정리할 때입니다.
 *  ingest 는 한 대로 뜨므로 뜨는 순간의 실행 중 기록은 전부 앞 프로세스가 남긴 것입니다.
 *  두 대 이상 띄우게 되면 다른 대의 살아 있는 실행까지 마감하므로 그때는 방식을 바꿔야 합니다.
 *  예를 들어 실행을 걸 때 오래된 실행 중 기록만 마감하는 식입니다.
 *
 * *실패로 마감하는 까닭
 *  재개 지점은 스스로 멈춘 실행(허용량 초과 · 스스로 접음)만 물려받습니다.
 *  프로세스가 왜 죽었는지는 모르므로 실행기의 규칙대로 실패로 두고 다음 실행이 처음부터 받습니다.
 *  증분과 고캠핑 목록은 처음부터 받아도 호출이 몇십 회라 부담이 없습니다.
 *
 * 마감은 엔티티를 고치는 것으로 끝납니다. 트랜잭션이 끝날 때 반영됩니다.
 * 실행기가 실패를 기록하는 방식과 같습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanRunCleaner {

    static final String MESSAGE = "기동 때 정리 — 앞 프로세스가 끝내지 못한 실행";

    private final IngestRunRepository ingestRunRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void cleanUp() {
        List<IngestRun> orphans = ingestRunRepository.findAllRunning();
        if (orphans.isEmpty()) {
            log.info("기동 때 정리 — 끝내지 못한 실행이 없습니다");
            return;
        }

        for (IngestRun run : orphans) {
            // 진행 상태는 그대로 둠 — 어디까지 받았는지 기록으로 남기려는 것이지 이어받으려는 것이 아님
            run.fail(run.getProgress(), MESSAGE);
        }

        List<UUID> runIds = orphans.stream().map(IngestRun::getId).toList();
        log.warn("기동 때 정리 — 끝내지 못한 실행 {}건을 실패로 마감했습니다. runIds={}", orphans.size(), runIds);
    }
}
