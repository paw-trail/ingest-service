package com.pawtrail.ingest.domain.exception;

/**
 * 다시 불러도 결과가 같은 소스 오류입니다. 고쳐야 나아집니다.
 *
 * 인증키가 등록되지 않았거나 기한이 지난 것, 파라미터가 틀린 것,
 * 없는 서비스를 부른 것이 여기 해당합니다.
 *
 * *재시도를 다 쓰고 실패한 것과 갈라 두는 이유가 있습니다.
 *  둘 다 호출이 실패한 것은 같지만 뒤이어 할 일이 정반대입니다.
 *
 *  일시적인 실패는 그 항목만 건너뛰고 이어 가는 편이 낫습니다.
 *  흩어진 실패는 소스 사정이고, 한 건 때문에 그날 받아 둔 것을 버릴 이유가 없습니다.
 *
 *  이쪽은 다음 항목도 반드시 같은 결과입니다.
 *  건너뛰며 이어 가면 대상 전부를 헛되이 부르고 남은 허용량을 다 씁니다.
 *  그래서 그 자리에서 접습니다.
 *
 * 갈라 두지 않으면 인증키가 만료됐을 때 그것이 "한 건이 실패했다" 로 보입니다.
 * 연속 실패 한도가 있어 결국 멈추기는 하지만, 그때까지 몇 건을 헛되이 부르고
 * 실행 상태도 INTERRUPTED 로 남습니다.
 * 그것은 우리가 상황을 보고 접었다는 뜻이라 다음 실행이 재개 지점을 물려받는데,
 * 인증키를 고치지 않았다면 같은 자리에서 또 죽습니다.
 *
 * 실행기가 이 예외를 따로 잡지 않습니다.
 * 예상하지 못한 오류와 같은 길로 흘러가 실행이 FAILED 로 마감되고,
 * 재개 지점을 물려주지 않습니다. 그것이 맞는 결말입니다.
 *
 * QuotaExhaustedException 과 마찬가지로 CustomException 을 상속하지 않습니다.
 * 이 예외는 비동기 수집 중에 흐름을 접는 내부 신호이지
 * 요청 실패를 HTTP 응답으로 바꾸는 통로가 아니라 밖으로 나가지 않습니다.
 */
public class PermanentSourceErrorException extends RuntimeException {

    private final String operation;
    private final String code;

    public PermanentSourceErrorException(String operation, String code, String message) {
        super("고쳐야 하는 소스 오류 operation=" + operation + " code=" + code
                + " message=" + message);
        this.operation = operation;
        this.code = code;
    }

    public String getOperation() {
        return operation;
    }

    public String getCode() {
        return code;
    }
}
