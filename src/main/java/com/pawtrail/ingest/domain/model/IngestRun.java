package com.pawtrail.ingest.domain.model;

import com.pawtrail.ingest.domain.enums.RunStatus;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 수집 실행 기록입니다.
 *
 * 사람이 승인을 판단하는 재료이자 쿼터로 중단된 뒤 재개하는 근거입니다.
 *
 * BaseEntity 를 상속하지 않습니다.
 * createdAt 이 startedAt 과 사실상 같은 값이고 배치가 만드는 로그성 표입니다.
 * refresh_token_log, outbox, processed_event 와 같은 부류입니다.
 */
@Entity
@Table(name = "ingest_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestRun {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // 넷 다 옴. MOIS_VET 은 raw_document 를 안 거칠 뿐 실행은 함
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false, updatable = false)
    private SourceType source;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", length = 12, nullable = false, updatable = false)
    private RunType runType;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    // 실행 중이면 null 임
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private RunStatus status;

    @Column(name = "fetched_count", nullable = false)
    private int fetchedCount;

    // 내용이 실제로 달라진 건수임
    // 감지는 스케줄로 하고 실행은 사람이 승인한다는 방침에서
    // 사람이 보고 판단하는 숫자가 이것임
    @Column(name = "changed_count", nullable = false)
    private int changedCount;

    // 오퍼레이션별 호출 수와 재개 지점임
    //
    // * 타입이 Map 인 이유
    //   청크마다 호출 수를 올리는 코드가 문자열 조작이 아니라 객체 조작이 됨
    //   매 청크마다 하는 일이라 여기서 실수가 나면 쿼터 카운트가 조용히 틀어지고,
    //   쿼터는 되돌릴 수 없는 자원이라 나중에 알아채도 복구할 방법이 없음
    //
    // * 컬럼으로 나누지 않은 이유
    //   소스마다 부르는 오퍼레이션이 다르고 CSV 소스는 호출이 0이라
    //   컬럼으로 두면 대부분이 null 이 됨
    //   오퍼레이션이 늘 때마다 마이그레이션이 붙는 것도 대가임
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress", nullable = false)
    private Map<String, OperationProgress> progress;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    private IngestRun(SourceType source, RunType runType) {
        this.source = source;
        this.runType = runType;
        this.startedAt = LocalDateTime.now();
        this.status = RunStatus.RUNNING;
        this.fetchedCount = 0;
        this.changedCount = 0;
        this.progress = new HashMap<>();
    }

    /**
     * 실행을 시작합니다.
     *
     * 같은 소스가 이미 실행 중인지는 트리거 서비스가 먼저 확인합니다.
     * 여기서 확인하지 않는 이유는 조회 결과가 있어야 판단할 수 있고,
     * 그 조회는 도메인이 아니라 저장소가 하는 일이기 때문입니다.
     */
    public static IngestRun start(SourceType source, RunType runType) {
        if (source == null || runType == null) {
            throw new IllegalArgumentException("source 와 runType 은 필수입니다.");
        }
        return new IngestRun(source, runType);
    }

    /**
     * 청크 하나를 처리한 결과를 더합니다.
     *
     * 저장과 같은 트랜잭션 안에서 불립니다.
     * 호출할 때마다 따로 기록하지 않는 이유가 여기 있습니다.
     * 갈라 두면 저장이 롤백됐는데 호출 수만 오른 상태가 만들어지고,
     * 그것은 쿼터는 썼는데 데이터는 없다는 뜻입니다.
     *
     * @param progressSnapshot 수집기가 그 청크까지 기록한 진행 상태
     */
    public void applyChunk(int fetched, int changed, Map<String, OperationProgress> progressSnapshot) {
        this.fetchedCount += fetched;
        this.changedCount += changed;
        replaceProgress(progressSnapshot);
    }

    /**
     * 끝까지 마쳤습니다.
     */
    public void complete(Map<String, OperationProgress> progressSnapshot) {
        replaceProgress(progressSnapshot);
        this.status = RunStatus.DONE;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * 일일 호출 허용량에 걸려 멈췄습니다. 실패가 아닙니다.
     *
     * 다음 실행이 progress 의 cursor 에서 이어받습니다.
     */
    public void stopByQuota(Map<String, OperationProgress> progressSnapshot, String operation) {
        replaceProgress(progressSnapshot);
        this.status = RunStatus.QUOTA_STOPPED;
        this.finishedAt = LocalDateTime.now();
        this.errorMessage = "일일 호출 허용량 초과: " + operation;
    }

    /**
     * 예상하지 못한 오류로 멈췄습니다.
     */
    public void fail(Map<String, OperationProgress> progressSnapshot, String message) {
        replaceProgress(progressSnapshot);
        this.status = RunStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
        this.errorMessage = message;
    }

    /**
     * 진행 상태를 통째로 갈아끼웁니다.
     *
     * 안쪽 값만 고치지 않고 Map 자체를 새것으로 바꾸는 이유는
     * Hibernate 가 JSON 컬럼의 변경을 알아채게 하기 위함입니다.
     * 같은 Map 인스턴스를 그대로 두고 안을 고치면
     * 더티 체크가 걸리지 않아 갱신이 조용히 빠질 수 있습니다.
     */
    private void replaceProgress(Map<String, OperationProgress> snapshot) {
        this.progress = snapshot == null ? new HashMap<>() : new HashMap<>(snapshot);
    }
}
