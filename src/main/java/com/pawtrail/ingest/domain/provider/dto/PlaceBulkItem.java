package com.pawtrail.ingest.domain.provider.dto;

import com.pawtrail.ingest.domain.enums.SourceType;
import java.time.LocalDate;

/**
 * 장소 서비스로 보낼 요청 한 건입니다.
 *
 * 받는 쪽이 정한 형태를 그대로 따릅니다.
 * 필드를 우리가 고르면 그쪽 검증에 걸리거나 조용히 무시됩니다.
 *
 * 정규화를 하지 않습니다.
 * 이름과 주소를 다듬고 좌표를 검사하는 일은 장소 서비스가 합니다.
 * 여기서 한 번 더 다듬으면 같은 규칙이 두 곳에 생기고,
 * 두 규칙이 갈리는 날 어느 쪽이 맞는지 알 수 없게 됩니다.
 *
 * 좌표를 문자열로 보냅니다.
 * 소수 열 자리가 오는 소스가 있어 실수로 받으면 그 시점에 정밀도가 흔들립니다.
 *
 * @param source         어느 데이터셋인지입니다.
 * @param sourceId       그 데이터셋 안에서의 식별자입니다. 돌려받을 때 짝을 맞추는 열쇠입니다.
 * @param name           장소 이름입니다. 비면 이 건을 보내지 않습니다.
 * @param addressRoad    도로명 주소입니다.
 * @param addressJibun   지번 주소입니다. 문화정보원만 줍니다.
 * @param sidoName       주소 첫 토큰이 시도가 아닐 때 쓰는 폴백입니다.
 * @param lat            위도입니다.
 * @param lon            경도입니다.
 * @param coordSource    좌표를 어디서 얻었는지입니다. 소스가 준 값이면 ORIGINAL 입니다.
 * @param lcls1          대분류입니다. 소스마다 담는 값이 다릅니다.
 * @param lcls2          중분류입니다.
 * @param lcls3          소분류입니다.
 * @param tel            전화번호입니다.
 * @param homepage       홈페이지입니다.
 * @param imageUrl       대표 사진 주소입니다.
 * @param cpyrhtDivCd    저작권 구분입니다. 관광공사만 줍니다.
 * @param overview       소개문입니다.
 * @param businessHours  운영시간입니다.
 * @param closedDays     휴무일입니다.
 * @param reservationUrl 예약 주소입니다.
 * @param dataBaseDate   소스가 밝힌 데이터 기준일입니다.
 * @param parking        주차 안내입니다.
 * @param posblFcltyCl   이용 가능 시설입니다. 고캠핑만 줍니다.
 * @param sbrsCl         부대시설입니다. 고캠핑만 줍니다.
 * @param resveCl        예약 방식입니다. 고캠핑만 줍니다.
 */
public record PlaceBulkItem(SourceType source,
                            String sourceId,
                            String name,
                            String addressRoad,
                            String addressJibun,
                            String sidoName,
                            String lat,
                            String lon,
                            String coordSource,
                            String lcls1,
                            String lcls2,
                            String lcls3,
                            String tel,
                            String homepage,
                            String imageUrl,
                            String cpyrhtDivCd,
                            String overview,
                            String businessHours,
                            String closedDays,
                            String reservationUrl,
                            LocalDate dataBaseDate,
                            String parking,
                            String posblFcltyCl,
                            String sbrsCl,
                            String resveCl) {
}
