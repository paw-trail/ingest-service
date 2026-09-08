package com.pawtrail.ingest.domain.enums;

/**
 * 수집 실행의 상태입니다.
 */
public enum RunStatus {

    // 실행 중입니다. 같은 소스로 또 부르면 거절합니다.
    RUNNING,

    // 끝까지 마쳤습니다.
    DONE,

    // 예상하지 못한 오류로 멈췄습니다. 원인은 error_message 에 있습니다.
    FAILED,

    // 일일 호출 허용량에 걸려 멈췄습니다. 실패가 아닙니다.
    //
    // 대상이 1,080건인데 상세 오퍼레이션마다 하루 1,000건이라
    // 한 번에 끝나지 않는 것이 정상입니다.
    // 다음 실행이 progress 의 cursor 에서 이어받습니다.
    QUOTA_STOPPED
}
