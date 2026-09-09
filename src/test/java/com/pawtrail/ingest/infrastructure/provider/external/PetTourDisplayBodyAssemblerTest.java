package com.pawtrail.ingest.infrastructure.provider.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 표시용 본문은 장소 상세의 원문보기가 그대로 보여주는 값입니다.
 *
 * 이 규칙을 시험으로 못 박아 두는 이유가 있습니다.
 * 원본이 그대로면 내용 해시도 그대로이고, 해시가 같으면 이미 담긴 행의 본문이 갱신되지 않습니다.
 * 조립 규칙을 나중에 고쳐도 옛 본문이 그대로 남는다는 뜻이라
 * 처음 담을 때 맞아야 합니다.
 *
 * 여기 쓰는 값은 2026년 9월 9일에 여섯 타입을 실제로 불러 받은 응답에서 가져왔습니다.
 */
class PetTourDisplayBodyAssemblerTest {

    private final PetTourDisplayBodyAssembler assembler = new PetTourDisplayBodyAssembler();

    @Test
    @DisplayName("조건 · 관련 안내 · 개요 · 이용 안내 차례로 덩어리를 만든다")
    void assemblesInOrder() {
        String body = assembler.assemble(petTour(), common(), touristIntro());

        assertThat(body).isEqualTo("""
                [동반 유형] 전구역 동반가능
                [동반 가능 반려동물] 전 견종 동반 가능
                [동반 필수 준비물] 목줄 착용
                [기타 동반 안내]
                - 목줄은 2m 이내로 유지
                - 배변봉투 지참

                [관련 사고 대비사항] 반려동물 안전사고에 유의해 주세요

                [개요]
                서울 도심에 자리한 공원입니다.

                ◎ 이용 안내는 홈페이지를 참고하세요

                [문의처] 종로구청 도시녹지과 02-2148-2832
                [휴무일] 연중무휴
                [이용시간] 상시 개방
                [주차] 불가능""");
    }

    @Test
    @DisplayName("값에 줄바꿈이 있으면 라벨 다음 줄부터 적는다")
    void putsMultilineValueOnItsOwnLine() {
        Map<String, Object> petTour = new LinkedHashMap<>();
        petTour.put("acmpyTypeCd", "전구역 동반가능");
        petTour.put("etcAcmpyInfo", "- 목줄 착용\n- 입마개 착용");

        String body = assembler.assemble(petTour, Map.of(), Map.of());

        assertThat(body).isEqualTo("""
                [동반 유형] 전구역 동반가능
                [기타 동반 안내]
                - 목줄 착용
                - 입마개 착용""");
    }

    @Test
    @DisplayName("빈 문자열은 라벨째 뺀다")
    void dropsLabelWhenValueIsBlank() {
        Map<String, Object> petTour = new LinkedHashMap<>();
        petTour.put("acmpyTypeCd", "전구역 동반가능");
        petTour.put("acmpyPsblCpam", "");
        petTour.put("acmpyNeedMtr", "   ");

        String body = assembler.assemble(petTour, Map.of(), Map.of());

        assertThat(body).isEqualTo("[동반 유형] 전구역 동반가능");
    }

    @Test
    @DisplayName("식별자와 숫자 플래그는 담지 않는다")
    void dropsIdentifiersAndNumericFlags() {
        Map<String, Object> intro = new LinkedHashMap<>();
        intro.put("contentid", "138661");
        intro.put("contenttypeid", "32");
        intro.put("seminar", "0");
        intro.put("barbecue", "1");
        intro.put("reservationurl", "https://example.test/booking");
        intro.put("roomcount", "42실");

        String body = assembler.assemble(Map.of(), Map.of(), intro);

        assertThat(body).isEqualTo("[객실 수] 42실");
    }

    @Test
    @DisplayName("사전에 없는 이름은 담지 않는다")
    void dropsUnknownField() {
        Map<String, Object> intro = new LinkedHashMap<>();
        intro.put("infocenter", "02-0000-0000");
        intro.put("newFieldFromSource", "언젠가 생길 값");

        String body = assembler.assemble(Map.of(), Map.of(), intro);

        assertThat(body).isEqualTo("[문의처] 02-0000-0000");
    }

    @Test
    @DisplayName("타입이 달라도 같은 사전으로 라벨이 붙는다")
    void usesOneDictionaryAcrossTypes() {
        Map<String, Object> intro = new LinkedHashMap<>();
        intro.put("contenttypeid", "39");
        intro.put("firstmenu", "월특해물꼬치짬뽕");
        intro.put("infocenterfood", "0507-1405-4096");
        intro.put("opentimefood", "10:00~21:00 (마지막 주문 20:00)");
        intro.put("chkcreditcardfood", "가능");

        String body = assembler.assemble(Map.of(), Map.of(), intro);

        assertThat(body).isEqualTo("""
                [대표 메뉴] 월특해물꼬치짬뽕
                [문의처] 0507-1405-4096
                [영업시간] 10:00~21:00 (마지막 주문 20:00)
                [신용카드] 가능""");
    }

    @Test
    @DisplayName("소개 정보가 통째로 비어도 나머지는 담는다")
    void keepsOtherBlocksWhenIntroIsEmpty() {
        Map<String, Object> petTour = new LinkedHashMap<>();
        petTour.put("acmpyTypeCd", "전구역 동반가능");

        // 소개 정보가 등록되지 않은 콘텐츠가 실제로 있음
        // 그때 결과 코드는 정상이고 항목만 비어서 오므로 클라이언트가 빈 지도를 돌려줌
        String body = assembler.assemble(petTour, Map.of("overview", "설명"), Map.of());

        assertThat(body).isEqualTo("""
                [동반 유형] 전구역 동반가능

                [개요] 설명""");
    }

    @Test
    @DisplayName("담을 것이 하나도 없으면 null 이다")
    void returnsNullWhenNothingToShow() {
        assertThat(assembler.assemble(Map.of(), Map.of(), Map.of())).isNull();
        assertThat(assembler.assemble(null, null, null)).isNull();
    }

    @Test
    @DisplayName("개요만 담고 홈페이지나 주소는 담지 않는다")
    void takesOnlyOverviewFromCommon() {
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("title", "여의도한강공원");
        common.put("addr1", "서울특별시 영등포구");
        common.put("homepage", "<a href=\"https://example.test\">공원</a>");
        common.put("overview", "설명입니다");

        String body = assembler.assemble(Map.of(), common, Map.of());

        assertThat(body).isEqualTo("[개요] 설명입니다");
    }

    private Map<String, Object> petTour() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contentid", "1019041");
        map.put("acmpyTypeCd", "전구역 동반가능");
        map.put("acmpyPsblCpam", "전 견종 동반 가능");
        map.put("acmpyNeedMtr", "목줄 착용");
        map.put("etcAcmpyInfo", "- 목줄은 2m 이내로 유지\n- 배변봉투 지참");
        map.put("relaAcdntRiskMtr", "반려동물 안전사고에 유의해 주세요");
        map.put("relaPosesFclty", "");
        map.put("relaFrnshPrdlst", "");
        map.put("relaPurcPrdlst", "");
        map.put("relaRntlPrdlst", "");
        return map;
    }

    private Map<String, Object> common() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", "와룡공원");
        map.put("overview", "서울 도심에 자리한 공원입니다.\n\n◎ 이용 안내는 홈페이지를 참고하세요");
        map.put("tel", "");
        return map;
    }

    /**
     * 관광지 타입 응답입니다. 값이 비어 있던 필드도 그대로 넣어 걸러지는지 봅니다.
     */
    private Map<String, Object> touristIntro() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contentid", "1019041");
        map.put("contenttypeid", "12");
        map.put("heritage1", "0");
        map.put("heritage2", "0");
        map.put("heritage3", "0");
        map.put("infocenter", "종로구청 도시녹지과 02-2148-2832");
        map.put("restdate", "연중무휴");
        map.put("usetime", "상시 개방");
        map.put("parking", "불가능");
        map.put("expguide", "");
        map.put("chkpet", "");
        return map;
    }
}
