package com.pawtrail.ingest.infrastructure.provider.convert;

import static com.pawtrail.ingest.infrastructure.provider.convert.PayloadPicker.pick;
import static com.pawtrail.ingest.infrastructure.provider.convert.PayloadPicker.unwrap;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.PlaceItemConverter;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 한국관광공사 고캠핑 정보를 장소 서비스가 받는 형태로 바꿉니다.
 *
 * 목록 응답 하나에 필드가 전부 들어 있어 조각을 나누지 않습니다.
 * 상세 호출이 없는 소스라 꺼낼 곳도 한 군데뿐입니다.
 *
 * 시설 정보를 세 필드로 넘깁니다.
 * 부대시설과 이용 가능 시설과 예약 방식인데 받는 쪽이 그것으로 편의시설을 매깁니다.
 * 문자열을 쉼표로 이은 형태 그대로 넘기고 쪼개는 일은 그쪽이 합니다.
 */
@Component
public class GoCampingItemConverter implements PlaceItemConverter {

    @Override
    public SourceType source() {
        return SourceType.GOCAMPING;
    }

    @Override
    public PlaceBulkItem convert(String sourceId, Map<String, Object> payload) {
        Map<String, Object> item = unwrap(payload, "list");

        String name = pick(item, "facltNm");
        if (name == null) {
            return null;
        }

        return new PlaceBulkItem(
                SourceType.GOCAMPING,
                sourceId,
                name,
                pick(item, "addr1"),

                // 이 소스는 지번을 주지 않음
                null,

                // 주소에 시도가 빠진 행이 있어 폴백으로 씀
                // doNm 이 시도 명칭임
                pick(item, "doNm"),

                // 관광공사 응답이라 여기도 대문자 Y 가 위도임
                pick(item, "mapY"),
                pick(item, "mapX"),
                "ORIGINAL",

                // 업종을 대분류 자리에 담음
                //
                // 받는 쪽은 이 소스면 분류를 보지 않고 야영장으로 정함
                // 그래도 넘기는 것은 원본이 무엇이라고 했는지를 남겨 두기 위함임
                pick(item, "induty"),
                null,
                null,

                pick(item, "tel"),
                pick(item, "homepage"),
                pick(item, "firstImageUrl"),

                // 저작권 구분은 관광공사 관광정보 쪽에만 있음
                null,

                // 긴 소개가 없으면 한 줄 소개를 씀
                pick(item, "intro", "lineIntro"),

                // 운영시간과 휴무를 주지 않는 소스임
                // 대신 운영 기간과 운영 요일을 주는데 뜻이 달라 담지 않음
                null,
                null,

                pick(item, "resveUrl"),

                // 기준일을 주지 않음
                // modifiedtime 이 있으나 소스가 고친 시각이라 데이터 기준일과 뜻이 다름
                null,

                // 주차 안내를 주지 않음
                null,

                pick(item, "posblFcltyCl"),
                pick(item, "sbrsCl"),
                pick(item, "resveCl"));
    }
}
