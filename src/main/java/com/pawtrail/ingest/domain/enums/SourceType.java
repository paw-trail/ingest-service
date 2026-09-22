package com.pawtrail.ingest.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 수집 소스입니다.
 *
 * 값이 넷이지만 raw_document 에 담기는 것은 셋뿐입니다.
 * MOIS_VET(행정안전부 동물병원 인허가)은 원본을 보관하지 않고
 * ingest 가 CSV 를 읽어 바로 place 로 넘깁니다.
 * 인허가 데이터라 동반 조건 문구가 없어 추출할 것이 없고,
 * 판정을 하지 않으니 장소 상세의 원문보기에 보여줄 근거도 없습니다.
 *
 * 그래도 실행은 하므로 ingest_run 에는 기록이 남습니다.
 * 그래서 이 enum 을 둘로 나누지 않았습니다.
 * 나누면 SourceCollector 가 어느 타입을 반환할지 갈리고 세 값이 두 곳에 중복됩니다.
 * 대신 RawDocument 의 팩터리가 MOIS_VET 을 막습니다.
 *
 * DB 는 varchar 이고 CHECK 를 걸지 않았습니다.
 * 값이 늘 때마다 마이그레이션이 필요해지기 때문이며 다른 서비스도 같은 규칙입니다.
 */
public enum SourceType {

    // 한국관광공사 반려동물 동반여행정보
    // 한 장소에 응답이 넷이라 payload 를 키로 나눠 담습니다
    //
    // 증분을 지원하는 유일한 소스입니다.
    // 상세를 장소마다 세 번 불러 한 번에 삼천 회가 넘고 오퍼레이션마다 하루 한도가 있어,
    // 바뀐 것만 받는 것이 실제로 값어치가 있습니다
    PET_TOUR(true, true, true),

    // 한국관광공사 고캠핑
    // 목록 응답 하나에 필드가 전부 들어 있어 상세 호출이 없습니다
    //
    // 증분을 지원하지 않습니다. 한 번 부르면 전량이 오므로 아낄 것이 없습니다
    GOCAMPING(true, false, true),

    // 한국문화정보원 반려동물 동반 가능 문화시설 CSV
    //
    // 증분을 지원하지 않습니다. 바깥을 아예 부르지 않습니다
    CULTURE_CSV(true, false, false),

    // 행정안전부 동물병원 인허가 CSV
    // raw_document 를 거치지 않습니다
    MOIS_VET(false, false, false);

    private final boolean storedAsRawDocument;
    private final boolean supportsIncremental;
    private final boolean tourApi;

    SourceType(boolean storedAsRawDocument, boolean supportsIncremental, boolean tourApi) {
        this.storedAsRawDocument = storedAsRawDocument;
        this.supportsIncremental = supportsIncremental;
        this.tourApi = tourApi;
    }

    /**
     * 이 소스의 원본을 raw_document 에 담는지 알려줍니다.
     */
    public boolean isStoredAsRawDocument() {
        return storedAsRawDocument;
    }

    /**
     * 이 소스가 증분 수집을 지원하는지 알려줍니다.
     *
     * 값어치가 있는 곳에만 둡니다.
     * 세 소스가 이미 내용 해시로 안 바뀐 것을 걸러 저장하지 않으므로,
     * 증분이 더 아끼는 것은 *받아 오는 비용* 뿐입니다.
     * 그 비용이 큰 것은 상세를 장소마다 세 번 부르는 소스 하나입니다.
     *
     * 지원하지 않는 소스에 증분을 부르면 트리거가 501 로 거절합니다.
     * 받아 주고 안에서 전량을 돌면 같은 일을 두 이름으로 부르게 되고
     * 코드만 보아서는 그 구별이 되지 않습니다.
     */
    public boolean supportsIncremental() {
        return supportsIncremental;
    }

    /**
     * 한국관광공사 OpenAPI 로 받는 소스인지 알려줍니다.
     *
     * 관리자 화면의 「최신 수집 실행」 과 매일 예약이 이 소스들만 부릅니다.
     * 두 입구는 공사 API 를 그 자리에서 부르고 그 호출 이력을 남기려는 자리라,
     * 공사 API 를 부르지 않는 소스는 올릴 까닭이 없습니다.
     *
     * 나머지 둘은 공사 API 가 아닙니다.
     * 문화정보원과 행정안전부는 레포에 담은 파일을 읽어 바깥을 부르지 않습니다.
     */
    public boolean isTourApi() {
        return tourApi;
    }

    /**
     * 한국관광공사 OpenAPI 로 받는 소스를 선언 순서대로 돌려줍니다.
     *
     * 관리자 수집 기록이 이 소스들만 보여 주고, 매일 예약이 이 소스들을 차례로 겁니다.
     * 두 자리가 목록을 따로 들고 있으면 한쪽만 고쳐 어긋나므로 여기서 한 번만 정합니다.
     */
    public static List<SourceType> tourApiSources() {
        return Arrays.stream(values()).filter(SourceType::isTourApi).toList();
    }
}
