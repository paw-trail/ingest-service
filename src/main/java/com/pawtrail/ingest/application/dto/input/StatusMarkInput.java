package com.pawtrail.ingest.application.dto.input;

import java.util.UUID;

/**
 * extract 가 처리한 문서 하나와, 처리할 때 본 내용의 해시입니다.
 *
 * 해시를 함께 받는 이유는 extract 가 가져간 사이에 재수집이 내용을 바꿨을 수 있어서입니다.
 * 해시가 지금 것과 다르면 옛 내용으로 뽑은 결과라 상태를 바꾸지 않고 대기로 둡니다.
 * 그래야 다음 실행이 새 내용을 가져갑니다.
 *
 * @param id          문서 식별자. 목록 조회로 받은 값 그대로입니다
 * @param contentHash 목록 조회로 받은 내용 해시 그대로입니다
 */
public record StatusMarkInput(UUID id, String contentHash) {
}
