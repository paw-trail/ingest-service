package com.pawtrail.ingest.application.dto.output;

/**
 * 상태를 바꾼 결과입니다.
 *
 * @param updated 바꾼 건수. 부르는 쪽이 보낸 개수와 맞는지 확인하면 됩니다
 */
public record StatusUpdateOutput(int updated) {
}
