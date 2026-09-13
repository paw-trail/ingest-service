package com.pawtrail.ingest.domain.exception;

import com.pawtrail.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 이 서비스의 도메인 에러 코드입니다.
 *
 * 공통 코드는 CommonErrorCode 에 있고 도메인 개념은 여기에 둡니다.
 * 공통에 두면 코드 하나를 더할 때마다 공통 모듈 재배포와 전 서비스 버전업이 필요해집니다.
 *
 * getCode 는 반드시 name 을 그대로 반환합니다.
 * 상수 이름이 곧 응답의 code 값이자 API 계약인데, 규칙을 어겨도 컴파일러가 잡지 못합니다.
 *
 * 메시지는 고정 문자열입니다. 동적인 값이 필요하면 응답 data 에 담습니다.
 */
public enum IngestErrorCode implements ErrorCode {

    // 같은 소스로 이미 실행 중임
    //
    // * 왜 막는가
    //   수집은 비동기라 트리거를 두 번 부르면 같은 소스가 나란히 돌 수 있음
    //   그러면 같은 쿼터를 두 배로 쓰고, 둘 다 1,000 에 못 미쳐 멈춰
    //   *어느 쪽도 끝내지 못한 채 그날 몫이 사라짐*
    //   쿼터는 되돌릴 수 없는 자원이라 나중에 알아채도 복구할 방법이 없음
    //
    // * 409 인 이유
    //   요청이 잘못된 것이 아니라 지금 상태와 충돌하는 것임
    //   실행이 끝나면 같은 요청이 그대로 성공함
    //
    // * 두 곳에서 던짐
    //   미리 조회해 걸러내는 자리와, 그 사이를 비집고 들어온 요청이
    //   유일 인덱스에 부딪히는 자리임
    //   조회만으로는 두 요청을 갈라 놓지 못해 DB 제약이 마지막 방어선임
    INGEST_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 실행 중인 수집이 있습니다."),

    // 그 소스를 수집할 구현이 아직 없음
    //
    // * 실행을 만들지 않고 트리거 시점에 거절함
    //   RUNNING 으로 만들어 두고 곧바로 FAILED 로 마감하면
    //   실행 이력에 아무 일도 안 한 행이 남고, 그것을 재개 대상으로 착각할 여지가 생김
    //
    // * 501 인 이유
    //   서버가 그 기능을 아직 구현하지 않은 것이지 요청이 틀린 것이 아님
    //   소스 이름 자체는 우리가 정의한 값이라 400 으로 두면 부르는 쪽이 값을 의심하게 됨
    COLLECTOR_NOT_REGISTERED(HttpStatus.NOT_IMPLEMENTED, "아직 지원하지 않는 소스입니다."),

    // 그 실행 유형을 아직 구현하지 않음
    //
    // * 지금은 증분 수집이 여기 걸림
    //   run_type 은 표에 담기지만 수집기가 그 값을 읽어 동작을 바꾸지는 않음
    //   막지 않으면 증분으로 불러도 조용히 전량 수집이 돌아
    //   이틀치 호출 허용량을 통째로 쓰고, 그것이 버그라는 것도 드러나지 않음
    //
    // * 요청 필드는 그대로 받음
    //   빼면 API 계약이 바뀌고 증분을 구현할 때 되돌려야 함
    //
    // * 501 인 이유
    //   위와 같음. 서버가 아직 만들지 않은 것이지 요청 값이 틀린 것이 아님
    RUN_TYPE_NOT_SUPPORTED(HttpStatus.NOT_IMPLEMENTED, "아직 지원하지 않는 실행 유형입니다."),

    // 그 실행 기록이 없음
    //
    // 비동기 실행기가 식별자로 실행을 다시 읽을 때 쓰는 코드임
    // 트리거가 만들고 커밋한 직후라 정상 흐름에서는 나올 수 없고,
    // 나온다면 누가 행을 지웠거나 트랜잭션 경계가 깨진 것임
    INGEST_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "수집 실행 기록을 찾을 수 없습니다."),

    // 원본을 담을 수 없는 소스인데 raw_document 를 만들려고 함
    //
    // MOIS_VET 은 인허가 데이터라 동반 조건 문구가 없어 추출할 것이 없고
    // 판정을 하지 않으니 원문보기에 보여줄 근거도 없음
    // CSV 를 읽어 바로 place 로 넘기는 경로라 이 표를 거치지 않음
    //
    // 사용자가 만드는 상황이 아니라 코드 실수일 때만 나옴
    // 그래도 값으로 두는 이유는 조용히 들어가면 원문보기에 빈 문서가 생기기 때문임
    RAW_DOCUMENT_NOT_ALLOWED(HttpStatus.INTERNAL_SERVER_ERROR, "이 소스는 원본을 보관하지 않습니다."),

    // 소스 API 호출이 끝내 실패함
    //
    // * 다시 시도해도 안 되는 것과 몇 번 해 보고도 안 되는 것이 여기 모임
    //   인증키가 등록되지 않았거나 기한이 지난 것, 파라미터가 틀린 것,
    //   그리고 일시 오류로 정해진 횟수만큼 다시 시도했는데도 실패한 것임
    //
    // * 쿼터 초과는 여기 오지 않음
    //   그것은 실패가 아니라 오늘 몫을 다 쓴 것이라 QuotaExhaustedException 으로 갈라
    //   실행을 QUOTA_STOPPED 로 마감함
    //
    // * 502 인 이유
    //   우리 잘못이든 상대 잘못이든 부르는 쪽에서 보면 바깥 호출이 실패한 것임
    //   구체적인 코드는 로그와 실행 기록의 error_message 에 남음
    //
    // * 이 코드가 HTTP 응답으로 나가는 일은 거의 없음
    //   수집은 비동기라 트리거 응답은 이미 나간 뒤임
    //   그래도 코드로 두는 이유는 실행 기록에 남길 문구가 한 곳에서 나와야 하기 때문임
    SOURCE_API_FAILED(HttpStatus.BAD_GATEWAY, "소스 API 호출에 실패했습니다."),

    // 소스 파일을 열지 못함
    //
    // * 파일을 읽는 소스에만 해당함
    //   문화정보원은 REST API 가 아니라 저장소에 함께 커밋한 CSV 를 읽음
    //
    // * 흔한 원인 둘
    //   설정의 경로가 실행 위치와 안 맞음 (상대경로라 어디서 띄우는지에 달림)
    //   컨테이너에 파일을 안 넣었거나 마운트 경로가 다름
    //
    // * 500 인 이유
    //   사용자 요청이 잘못된 것이 아니라 우리 배포가 덜 된 것임
    SOURCE_FILE_NOT_READABLE(HttpStatus.INTERNAL_SERVER_ERROR, "소스 파일을 읽을 수 없습니다."),

    // 소스 파일의 형태가 예상과 다름
    //
    // 컬럼 수가 맞지 않을 때 남김
    // 파일이 새 판으로 바뀌면서 컬럼이 늘거나 줄면 여기서 먼저 드러남
    // 그대로 담으면 값이 한 칸씩 밀린 채로 들어가고 나중에 알아채기 어려움
    SOURCE_FILE_MALFORMED(HttpStatus.INTERNAL_SERVER_ERROR, "소스 파일의 형태가 다릅니다."),

    // 상태를 바꾸라고 받은 식별자 중에 없는 것이 섞여 있음
    //
    // * 하나라도 없으면 전체를 거절함
    //   extract 가 방금 우리에게 받아 간 것을 되돌려 주는 것인데
    //   없다는 것은 무언가 어긋난 것임
    //   ⛔조용히 건너뛰면 그 문서가 영영 대기로 남아 목록 맨 앞을 막음
    //   대기 목록은 언제나 오래된 것부터 주므로 맨 앞이 막히면 그 뒤가 안 나감
    //
    // * 400 인 이유
    //   보낸 목록이 잘못된 것이므로 요청 문제임
    //   어느 식별자가 없었는지는 로그에 앞쪽 열 개까지 남김
    RAW_DOCUMENT_NOT_FOUND(HttpStatus.BAD_REQUEST, "없는 원본 문서가 포함돼 있습니다."),

    // 같은 식별자가 처리 완료와 실패에 함께 들어옴
    //
    // 어느 것으로 둘지 우리가 고를 수 없으므로 거절함
    // 부르는 쪽의 실수이고 조용히 한쪽을 택하면 그 판단이 어디에도 안 남음
    RAW_DOCUMENT_STATUS_CONFLICT(
            HttpStatus.BAD_REQUEST, "같은 문서가 처리 완료와 실패에 함께 있습니다."),

    // 장소 서비스에 넘기지 못함
    //
    // * 실행을 그 자리에서 멈춤
    //   상대가 한 서비스라 한 묶음이 실패하면 다음 묶음도 같은 이유로 실패함
    //   계속 보내면 같은 오류를 쌓기만 하고 끝난 뒤에도 무엇이 들어갔는지 알 수 없음
    //
    // * 이어받지 않음
    //   보낸 것을 다시 보내도 상대가 같은 결과를 내므로 처음부터 다시 하면 됨
    //   어디까지 갔는지를 남기면 그 정확성을 또 검증해야 하는데 얻는 것이 없음
    //
    // * 502 인 이유
    //   우리 잘못도 부르는 쪽 잘못도 아니고 뒤에 있는 서비스가 답하지 않은 것임
    //   소스 API 실패를 502 로 둔 것과 같은 자리임
    PLACE_LINK_FAILED(HttpStatus.BAD_GATEWAY, "장소 서비스에 넘기지 못했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    IngestErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }
}
