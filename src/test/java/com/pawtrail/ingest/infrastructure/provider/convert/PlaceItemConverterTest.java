package com.pawtrail.ingest.infrastructure.provider.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 원본을 장소 서비스 요청으로 바꾸는 규칙을 검사합니다.
 *
 * 이 매핑은 만 칠천 건을 실제로 넣어 본 것을 옮긴 것입니다.
 * 한 자리가 어긋나면 그 소스의 값이 통째로 비거나 엉뚱한 칸에 들어가는데,
 * 오류가 나지 않아 적재가 끝난 뒤에야 드러납니다.
 *
 * 특히 지키려는 것이 셋입니다.
 * 관광공사는 위도와 경도의 키 이름이 뒤바뀐 것처럼 보이는 자리가 있습니다.
 * 문화정보원의 분류는 중분류 자리에 담아야 받는 쪽이 읽습니다.
 * 이름이 없으면 비워 돌려주어야 그 묶음이 통째로 거절되지 않습니다.
 */
class PlaceItemConverterTest {

    @Nested
    @DisplayName("한국관광공사")
    class 관광공사 {

        private final PetTourItemConverter converter = new PetTourItemConverter();

        @Test
        @DisplayName("네 조각에서 각각 꺼내 담는다")
        void 네_조각() {
            PlaceBulkItem item = converter.convert("125701", payload());

            assertThat(item.source()).isEqualTo(SourceType.PET_TOUR);
            assertThat(item.sourceId()).isEqualTo("125701");
            assertThat(item.name()).isEqualTo("하조대해수욕장");
            assertThat(item.addressRoad()).isEqualTo("강원특별자치도 양양군 현북면 하조대해안길 35");
            assertThat(item.overview()).isEqualTo("소개문입니다");
            assertThat(item.homepage()).isEqualTo("https://example.test");
        }

        @Test
        @DisplayName("위도는 mapy 이고 경도는 mapx 다")
        void 좌표_순서() {
            PlaceBulkItem item = converter.convert("125701", payload());

            // 뒤집어 넣으면 좌표가 통째로 다른 곳을 가리키는데 오류가 나지 않음
            assertThat(item.lat()).isEqualTo("38.0229894");
            assertThat(item.lon()).isEqualTo("128.7242655");
        }

        @Test
        @DisplayName("전화번호는 분류별 상세에서 먼저 찾는다")
        void 전화번호() {
            // 목록과 공통 응답은 전화번호가 전 계열 비어 있고 상세에만 들어 있음
            PlaceBulkItem item = converter.convert("125701", payload());

            assertThat(item.tel()).isEqualTo("033-672-2346");
        }

        @Test
        @DisplayName("주차 요금은 주차 안내로 담지 않는다")
        void 주차_요금_제외() {
            Map<String, Object> payload = payload();
            Map<String, Object> intro = new LinkedHashMap<>();
            intro.put("parkingfee", "무료");
            intro.put("parkingleports", "주차장 50대");
            payload.put("intro", intro);

            // 요금 필드에는 "무료" 같은 값이 들어와 주차가 되는지와 뜻이 다름
            assertThat(converter.convert("125701", payload).parking()).isEqualTo("주차장 50대");
        }

        @Test
        @DisplayName("이름이 없으면 비워 돌려준다")
        void 이름_없음() {
            assertThat(converter.convert("125701", Map.of())).isNull();
        }

        private Map<String, Object> payload() {
            Map<String, Object> list = new LinkedHashMap<>();
            list.put("title", "하조대해수욕장");
            list.put("addr1", "강원특별자치도 양양군 현북면 하조대해안길 35");
            list.put("mapy", "38.0229894");
            list.put("mapx", "128.7242655");
            list.put("lclsSystm1", "NA");
            list.put("lclsSystm2", "NA01");
            list.put("firstimage", "https://img.example.test/a.jpg");
            list.put("cpyrhtDivCd", "Type1");

            Map<String, Object> common = new LinkedHashMap<>();
            common.put("overview", "소개문입니다");
            common.put("homepage", "https://example.test");
            common.put("tel", "");

            Map<String, Object> intro = new LinkedHashMap<>();
            intro.put("infocenter", "033-672-2346");
            intro.put("usetime", "상시 개방");
            intro.put("restdate", "연중무휴");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("list", list);
            payload.put("common", common);
            payload.put("intro", intro);
            return payload;
        }
    }

    @Nested
    @DisplayName("고캠핑")
    class 고캠핑 {

        private final GoCampingItemConverter converter = new GoCampingItemConverter();

        @Test
        @DisplayName("한 겹 싸인 응답에서 꺼낸다")
        void 한_겹() {
            PlaceBulkItem item = converter.convert("100019", payload());

            assertThat(item.source()).isEqualTo(SourceType.GOCAMPING);
            assertThat(item.name()).isEqualTo("양지뜰캠핑장");
            assertThat(item.lat()).isEqualTo("35.9076467");
            assertThat(item.lon()).isEqualTo("127.7680715");
        }

        @Test
        @DisplayName("시도 명칭을 폴백으로 담는다")
        void 시도_폴백() {
            // 주소에 시도가 빠진 행이 있어 받는 쪽이 이 값을 씀
            assertThat(converter.convert("100019", payload()).sidoName()).isEqualTo("전라북도");
        }

        @Test
        @DisplayName("시설 문자열 셋을 그대로 넘긴다")
        void 시설_셋() {
            PlaceBulkItem item = converter.convert("100019", payload());

            assertThat(item.sbrsCl()).isEqualTo("전기,무선인터넷,온수");
            assertThat(item.posblFcltyCl()).isEqualTo("계곡 물놀이,산책로");
            assertThat(item.resveCl()).isEqualTo("온라인실시간예약");
        }

        @Test
        @DisplayName("긴 소개가 없으면 한 줄 소개를 쓴다")
        void 소개_폴백() {
            Map<String, Object> payload = payload();
            inner(payload).remove("intro");

            assertThat(converter.convert("100019", payload).overview())
                    .isEqualTo("계곡 물놀이를 즐길 수 있는 곳");
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> inner(Map<String, Object> payload) {
            return (Map<String, Object>) payload.get("list");
        }

        private Map<String, Object> payload() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("facltNm", "양지뜰캠핑장");
            item.put("addr1", "전북 무주군 설천면 보안길 88");
            item.put("doNm", "전라북도");
            item.put("mapY", "35.9076467");
            item.put("mapX", "127.7680715");
            item.put("induty", "자동차야영장");
            item.put("tel", "0507-1321-0532");
            item.put("intro", "긴 소개문입니다");
            item.put("lineIntro", "계곡 물놀이를 즐길 수 있는 곳");
            item.put("sbrsCl", "전기,무선인터넷,온수");
            item.put("posblFcltyCl", "계곡 물놀이,산책로");
            item.put("resveCl", "온라인실시간예약");
            item.put("resveUrl", null);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("list", item);
            return payload;
        }
    }

    @Nested
    @DisplayName("문화정보원")
    class 문화정보원 {

        private final CultureItemConverter converter = new CultureItemConverter();

        @Test
        @DisplayName("분류를 중분류 자리에 담는다")
        void 분류_자리() {
            PlaceBulkItem item = converter.convert("A공원|서울 1", payload());

            // 대분류에 넣으면 받는 쪽 분류 매핑이 통째로 안 되어 전부 기타가 됨
            assertThat(item.lcls1()).isEqualTo("여행");
            assertThat(item.lcls2()).isEqualTo("여행지");
        }

        @Test
        @DisplayName("지번 주소를 함께 담는다")
        void 지번() {
            // 지번을 주는 유일한 소스이고 도로명이 없는 행의 폴백이 됨
            PlaceBulkItem item = converter.convert("A공원|서울 1", payload());

            assertThat(item.addressRoad()).isEqualTo("서울특별시 송파구 도로 1");
            assertThat(item.addressJibun()).isEqualTo("서울특별시 송파구 잠실동 1");
        }

        @Test
        @DisplayName("기준일을 날짜로 맞춘다")
        void 기준일() {
            assertThat(converter.convert("A공원|서울 1", payload()).dataBaseDate())
                    .isEqualTo(LocalDate.of(2025, 3, 24));
        }

        @Test
        @DisplayName("알아볼 수 없는 기준일은 비운다")
        void 기준일_실패() {
            Map<String, Object> payload = payload();
            payload.put("최종작성일", "작성일 없음");

            // 받는 쪽이 날짜로 읽으므로 형태가 어긋나면 그 묶음이 통째로 거절됨
            assertThat(converter.convert("A공원|서울 1", payload).dataBaseDate()).isNull();
        }

        @Test
        @DisplayName("문자열 null 은 빈 값으로 본다")
        void 문자열_널() {
            Map<String, Object> payload = payload();
            payload.put("홈페이지", "null");

            // 실데이터에 그 글자가 들어 있어 그대로 두면 화면에 null 이라는 글자가 뜸
            assertThat(converter.convert("A공원|서울 1", payload).homepage()).isNull();
        }

        private Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("시설명", "A공원");
            payload.put("도로명주소", "서울특별시 송파구 도로 1");
            payload.put("지번주소", "서울특별시 송파구 잠실동 1");
            payload.put("시도 명칭", "서울특별시");
            payload.put("위도", "37.5");
            payload.put("경도", "127.0");
            payload.put("카테고리2", "여행");
            payload.put("카테고리3", "여행지");
            payload.put("전화번호", "02-000-0000");
            payload.put("홈페이지", "https://example.test");
            payload.put("주차 가능여부", "가능");
            payload.put("최종작성일", "2025-03-24");
            return payload;
        }
    }
}
