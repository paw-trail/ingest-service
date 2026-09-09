package com.pawtrail.ingest.domain.exception;

/**
 * 더 진행하는 것이 낭비라고 판단해 수집기가 스스로 멈출 때 던집니다.
 *
 * 지금은 한 가지 경우에 씁니다.
 * 한 건씩 실패해 건너뛰다가 연달아 실패하면 소스가 멈췄거나 우리 요청이 잘못된 것이라,
 * 계속 부르면 남은 허용량을 전부 헛되이 씁니다.
 *
 * QuotaExhaustedException 과 성격이 같습니다.
 * 요청을 처리하다 실패한 것을 HTTP 응답으로 바꾸는 통로가 아니라,
 * 비동기 실행 중에 흐름을 접는 내부 신호라 밖으로 나가지 않습니다.
 * 실행기가 이 예외를 잡아 실행 상태를 INTERRUPTED 로 마감합니다.
 *
 * *실패와 갈라 두는 이유가 있습니다.
 *  예상하지 못한 오류로 죽은 실행은 재개 지점을 물려주지 않습니다.
 *  그 자리에서 무엇이 잘못됐는지 모르는 상태라 이어받으면 문제가 난 구간을 조용히 건너뜁니다.
 *
 *  이쪽은 다릅니다.
 *  우리가 상황을 보고 스스로 멈춘 것이고 그때까지 저장한 것은 멀쩡합니다.
 *  소스가 잠깐 멈춘 것이 대부분이라 되살아나면 이어받는 편이 맞습니다.
 *  버리면 그날 쓴 허용량이 통째로 사라집니다.
 */
public class CollectionInterruptedException extends RuntimeException {

    public CollectionInterruptedException(String message) {
        super(message);
    }
}
