package com.pawtrail.ingest.infrastructure.provider.convert;

import static com.pawtrail.ingest.infrastructure.provider.convert.PayloadPicker.date;
import static com.pawtrail.ingest.infrastructure.provider.convert.PayloadPicker.pick;
import static com.pawtrail.ingest.infrastructure.provider.convert.PayloadPicker.unwrap;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.PlaceItemConverter;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 한국문화정보원 반려동물 동반 가능 문화시설을 장소 서비스가 받는 형태로 바꿉니다.
 *
 * 파일에서 읽은 것이라 키가 한글입니다.
 * 판마다 이름이 조금씩 달라 후보를 둘씩 늘어놓습니다.
 * 새 판을 받았을 때 열 이름이 바뀌면 값이 조용히 비게 되는데,
 * 후보를 두면 그중 하나라도 맞으면 살아남습니다.
 *
 * 지번 주소를 주는 유일한 소스입니다.
 * 도로명이 없는 행이 있어 받는 쪽이 지번을 주소 폴백으로 씁니다.
 */
@Component
public class CultureItemConverter implements PlaceItemConverter {

    @Override
    public SourceType source() {
        return SourceType.CULTURE_CSV;
    }

    @Override
    public PlaceBulkItem convert(String sourceId, Map<String, Object> payload) {
        Map<String, Object> item = unwrap(payload, "list");

        String name = pick(item, "시설명", "name");
        if (name == null) {
            return null;
        }

        return new PlaceBulkItem(
                SourceType.CULTURE_CSV,
                sourceId,
                name,
                pick(item, "도로명주소", "road"),
                pick(item, "지번주소", "jibun"),

                // 이 소스는 구 명칭을 씀
                // 강원도와 전라북도처럼 바뀌기 전 이름이 와 받는 쪽이 표준화함
                pick(item, "시도 명칭", "시도명칭", "시도명"),

                pick(item, "위도", "lat"),
                pick(item, "경도", "lon"),
                "ORIGINAL",

                pick(item, "카테고리2"),

                // 카테고리3 을 중분류 자리에 담는 것이 중요함
                //
                // 받는 쪽이 이 소스의 분류를 중분류 자리에서 읽음
                // 대분류에 넣으면 분류 매핑이 통째로 안 되어 전부 기타가 됨
                pick(item, "카테고리3"),
                null,

                pick(item, "전화번호", "tel"),
                pick(item, "홈페이지", "homepage"),

                // 사진을 주지 않는 소스임
                null,

                // 저작권 구분은 관광공사 쪽에만 있음
                null,

                pick(item, "기본 정보_장소설명", "장소설명", "overview"),
                pick(item, "운영시간", "businessHours"),
                pick(item, "휴무일", "closedDays"),

                // 예약 주소를 주지 않음
                null,

                // 기준일을 주는 유일한 소스임
                date(pick(item, "최종작성일", "데이터기준일자")),

                pick(item, "주차 가능여부", "주차가능여부"),

                // 고캠핑만 주는 값 셋임
                null,
                null,
                null);
    }
}
