package com.pawtrail.ingest.domain.enums;

/**
 * raw_document 의 추출 처리 상태입니다.
 *
 * extract 가 PENDING 인 것만 가져가 처리하고 결과를 되돌려 씁니다.
 * 수집이 끝나면 대부분 DONE 이라 PENDING 이 소수이므로
 * 마이그레이션에서 부분 인덱스로 걸어 두었습니다.
 */
public enum DocumentStatus {

    // 아직 추출하지 않았거나, 내용이 바뀌어 다시 추출해야 함
    PENDING,

    // 추출을 마침
    DONE,

    // 추출에 실패함. 원인은 extract 쪽 로그에 있음
    FAILED
}
