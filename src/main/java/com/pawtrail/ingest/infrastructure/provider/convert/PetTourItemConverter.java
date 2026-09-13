package com.pawtrail.ingest.infrastructure.provider.convert;

import static com.pawtrail.ingest.infrastructure.provider.convert.PayloadPicker.pick;
import static com.pawtrail.ingest.infrastructure.provider.convert.PayloadPicker.section;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.PlaceItemConverter;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 한국관광공사 반려동물 동반여행 정보를 장소 서비스가 받는 형태로 바꿉니다.
 *
 * 이 소스만 원본이 네 조각입니다.
 *
 * <pre>
 * list     목록 응답        이름 · 주소 · 좌표 · 분류가 다 들어 있음
 * petTour  동반 정보        판정에 쓰는 값이라 여기서는 꺼내지 않음
 * common   공통 상세        소개문과 홈페이지
 * intro    분류별 상세      전화번호 · 운영시간 · 휴무 · 주차
 * </pre>
 *
 * intro 의 키가 분류마다 다릅니다.
 * 같은 전화번호인데 문화시설이면 infocenterculture 이고 음식점이면 infocenterfood 입니다.
 * 그래서 후보를 늘어놓고 값이 있는 첫 번째를 씁니다.
 */
@Component
public class PetTourItemConverter implements PlaceItemConverter {

    // 주차 정보를 담는 키의 앞부분임
    //
    // 분류마다 parkingculture · parkingleports 처럼 뒤가 달라 앞부분으로 찾음
    private static final String PARKING_PREFIX = "parking";

    // 주차 요금 키를 걸러 내는 조각임
    //
    // parkingfee 에는 "무료" 같은 값이 들어 있어 주차가 되는지와 뜻이 다름
    // 그대로 담으면 주차 안내 자리에 요금만 뜸
    private static final String FEE = "fee";

    @Override
    public SourceType source() {
        return SourceType.PET_TOUR;
    }

    @Override
    public PlaceBulkItem convert(String sourceId, Map<String, Object> payload) {
        Map<String, Object> list = section(payload, "list");
        Map<String, Object> common = section(payload, "common");
        Map<String, Object> intro = section(payload, "intro");

        String name = firstOf(pick(list, "title"), pick(common, "title"));
        if (name == null) {
            return null;
        }

        return new PlaceBulkItem(
                SourceType.PET_TOUR,
                sourceId,
                name,
                firstOf(pick(list, "addr1"), pick(common, "addr1")),

                // 이 소스는 지번을 주지 않음
                null,

                // 주소 첫 토큰이 언제나 시도라 폴백이 필요 없음
                null,

                // mapy 가 위도이고 mapx 가 경도임
                // 뒤집어 넣으면 좌표가 통째로 다른 곳을 가리키는데 오류가 나지 않음
                firstOf(pick(list, "mapy"), pick(common, "mapy")),
                firstOf(pick(list, "mapx"), pick(common, "mapx")),
                "ORIGINAL",

                firstOf(pick(list, "lclsSystm1"), pick(common, "lclsSystm1")),
                firstOf(pick(list, "lclsSystm2"), pick(common, "lclsSystm2")),
                firstOf(pick(list, "lclsSystm3"), pick(common, "lclsSystm3")),

                // 목록과 공통 응답은 전화번호가 전부 비어 있고 상세에만 들어 있음
                firstOf(
                        pick(intro, "infocenter", "infocenterculture", "infocenterleports",
                                "infocenterlodging", "infocenterfood", "infocentershopping"),
                        pick(common, "tel")),

                pick(common, "homepage"),
                firstOf(pick(list, "firstimage"), pick(common, "firstimage")),
                firstOf(pick(list, "cpyrhtDivCd"), pick(common, "cpyrhtDivCd")),
                pick(common, "overview"),

                pick(intro, "usetime", "opentime", "usetimeculture",
                        "usetimeleports", "opentimefood"),
                pick(intro, "restdate", "restdateculture", "restdateleports",
                        "restdatefood", "restdateshopping"),

                // 예약 주소와 기준일을 주지 않는 소스임
                null,
                null,

                parking(intro),

                // 고캠핑만 주는 값 셋임
                null,
                null,
                null);
    }

    /**
     * 분류별 상세에서 주차 안내를 찾습니다.
     *
     * 키 이름이 분류마다 달라 앞부분으로 찾되 요금 키는 건너뜁니다.
     * 먼저 찾은 것을 씁니다. 한 응답에 주차 키가 둘 이상 들어 있는 경우가 없습니다.
     */
    private String parking(Map<String, Object> intro) {
        for (Map.Entry<String, Object> entry : intro.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.startsWith(PARKING_PREFIX) || key.contains(FEE)) {
                continue;
            }
            String value = pick(intro, entry.getKey());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstOf(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
