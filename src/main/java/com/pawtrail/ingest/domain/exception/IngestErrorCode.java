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
    RAW_DOCUMENT_NOT_ALLOWED(HttpStatus.INTERNAL_SERVER_ERROR, "이 소스는 원본을 보관하지 않습니다.");

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
