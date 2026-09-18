package com.pawtrail.ingest.application.dto.output;

/**
 * 상태를 바꾼 결과입니다.
 *
 * 두 수를 더하면 부르는 쪽이 보낸 문서 수가 됩니다(같은 문서를 두 번 보낸 것은 한 번으로 셉니다).
 *
 * @param updated 상태를 바꾼 건수
 * @param skipped 처리하는 사이에 내용이 바뀌어 상태를 그대로 둔 건수.
 *                오류가 아닙니다. 그 문서는 대기로 남아 다음 실행이 새 내용으로 다시 가져갑니다
 */
public record StatusUpdateOutput(int updated, int skipped) {
}
