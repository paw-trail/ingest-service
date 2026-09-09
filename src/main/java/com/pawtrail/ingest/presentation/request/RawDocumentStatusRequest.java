package com.pawtrail.ingest.presentation.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * extract 가 처리 결과를 되돌려 주는 요청입니다.
 *
 * *한 건씩이 아니라 묶음으로 받습니다.
 *  extract 가 백 건을 가져가 처리하므로 되돌려 쓰는 것도 백 건입니다.
 *  한 건씩 받으면 호출이 백 번이고 트랜잭션도 백 개이며,
 *  중간에 부르는 쪽이 죽으면 절반만 바뀐 상태로 남습니다.
 *
 *  묶어서 받으면 전부 바뀌거나 전부 안 바뀝니다.
 *  죽으면 그 묶음이 통째로 대기 상태로 남아 다음에 다시 가져가면 됩니다.
 *  추출을 두 번 도는 비용은 있으나 어중간한 상태보다 낫습니다.
 *
 * 둘 다 비어 있어도 요청 자체는 유효합니다. 바꾼 건수 0으로 응답합니다.
 * 처리할 것이 없었다는 뜻이라 오류가 아닙니다.
 *
 * @param done   추출을 마친 문서
 * @param failed 추출에 실패한 문서.
 *               실패를 표시해야 대기 목록에서 빠집니다.
 *               대기로 남겨 두면 그것이 계속 맨 앞에 와 뒤가 나가지 못합니다
 */
public record RawDocumentStatusRequest(

        // 한 번에 받는 양을 막아 둡니다.
        // extract 가 백 건씩 가져가므로 그보다 훨씬 큰 값이 오면 무언가 잘못된 것이고,
        // 그대로 받으면 트랜잭션 하나가 지나치게 길어집니다.
        //
        // ⛔원소 하나하나에도 제약을 겁니다.
        //  @Size 는 개수만 봅니다. { "done": [null] } 이 그대로 통과해
        //  조회까지 내려가면 없는 문서로 판정되어 400 이 나옵니다.
        //  결과는 맞지만 이유가 틀립니다. 요청이 잘못된 것인데 없는 문서라고 답하고,
        //  그 사이에 조회를 한 번 헛돕니다.
        @Size(max = 1000, message = "done 은 한 번에 1000건까지입니다")
        List<@NotNull(message = "done 에 빈 값이 들어 있습니다") UUID> done,

        @Size(max = 1000, message = "failed 는 한 번에 1000건까지입니다")
        List<@NotNull(message = "failed 에 빈 값이 들어 있습니다") UUID> failed) {
}
