package com.pawtrail.ingest.domain.exception;

/**
 * 일일 호출 허용량을 다 써서 더 진행할 수 없을 때 수집기가 던집니다.
 *
 * CustomException 이 아닙니다.
 * 그쪽은 요청을 처리하다 실패한 것을 HTTP 응답으로 바꾸는 통로인데,
 * 이것은 비동기 실행 중에 흐름을 접는 내부 신호라 밖으로 나가지 않습니다.
 * 실행기가 이 예외를 잡아 실행 상태를 QUOTA_STOPPED 로 마감합니다.
 *
 * 실패가 아닙니다.
 * 대상이 1,080건인데 상세 오퍼레이션마다 하루 1,000건이라
 * 한 번에 끝나지 않는 것이 정상입니다.
 *
 * 던지기 전에 그때까지의 진행을 반드시 기록해야 합니다.
 * 쿼터는 되돌릴 수 없는 자원이라 카운트를 잃으면
 * 다음 실행이 아직 안 썼다고 판단해 그날 몫을 날립니다.
 */
public class QuotaExhaustedException extends RuntimeException {

    private final String operation;

    public QuotaExhaustedException(String operation) {
        super("일일 호출 허용량 초과: " + operation);
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }
}
