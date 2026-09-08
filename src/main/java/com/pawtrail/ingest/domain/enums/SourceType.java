package com.pawtrail.ingest.domain.enums;

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
    PET_TOUR(true),

    // 한국관광공사 고캠핑
    // 목록 응답 하나에 필드가 전부 들어 있어 상세 호출이 없습니다
    GOCAMPING(true),

    // 한국문화정보원 반려동물 동반 가능 문화시설 CSV
    CULTURE_CSV(true),

    // 행정안전부 동물병원 인허가 CSV
    // raw_document 를 거치지 않습니다
    MOIS_VET(false);

    private final boolean storedAsRawDocument;

    SourceType(boolean storedAsRawDocument) {
        this.storedAsRawDocument = storedAsRawDocument;
    }

    /**
     * 이 소스의 원본을 raw_document 에 담는지 알려줍니다.
     */
    public boolean isStoredAsRawDocument() {
        return storedAsRawDocument;
    }
}
