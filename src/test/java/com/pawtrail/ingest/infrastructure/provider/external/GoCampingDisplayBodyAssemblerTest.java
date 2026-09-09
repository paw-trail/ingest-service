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
 * 조립 규칙을 나중에 고쳐도 옛 본문이 그대로 남는다는 뜻이라 처음 담을 때 맞아야 합니다.
 *
 * 개인정보를 담지 않는 것도 여기서 지킵니다.
 * 한 번 담기면 화면에 그대로 뜨고, 그때는 이미 늦습니다.
 *
 * 여기 쓰는 값은 2026년 9월 9일에 운영 중인 이천구백구십삼 건을 실제로 집계한 것에서 가져왔습니다.
 */
class GoCampingDisplayBodyAssemblerTest {

    private final GoCampingDisplayBodyAssembler assembler = new GoCampingDisplayBodyAssembler();

    @Test
    @DisplayName("동반 조건 · 소개 · 이용 안내 · 시설 · 부가 차례로 덩어리를 만든다")
    void assemblesInOrder() {
        String body = assembler.assemble(fullItem());

        assertThat(body).isEqualTo("""
                [반려동물 동반] 가능

                [한 줄 소개] 계곡을 배경으로 펼쳐진 캠핑장
                [소개] 주문진 글램핑 오토캠핑장은 강원도 강릉시 주문진읍에 자리 잡고 있다.

                [문의처] 033-642-4241
                [운영 기간] 봄,여름,가을,겨울
                [운영 요일] 평일+주말
                [예약] 온라인실시간예약
                [화로대] 개별
                [입지] 산,숲,계곡

                [부대시설] 전기,무선인터넷,장작판매,온수,운동시설
                [주변 이용시설] 계곡 물놀이

                [업종] 자동차야영장
                [시설 구분] 민간
                [관리 형태] 직영""");
    }

    @Test
    @DisplayName("관리자 개인 이름을 담지 않는다")
    void dropsManagerName() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("animalCmgCl", "가능");
        item.put("mgcDiv", "김영길");
        item.put("bizrno", "611-03-25160");
        item.put("trsagntNo", "2015000001");

        String body = assembler.assemble(item);

        // 원문보기는 관리자 전용이 아니라 사용자가 여는 화면임
        // 이 값이 주소나 전화번호와 같은 줄에 놓이면 개인을 식별하게 됨
        assertThat(body).isEqualTo("[반려동물 동반] 가능");
        assertThat(body).doesNotContain("김영길");
    }

    @Test
    @DisplayName("운영 주체는 시설 구분과 관리 형태로 드러난다")
    void showsOperatorThroughDivisionFields() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("mgcDiv", "김영길");
        item.put("facltDivNm", "국립");
        item.put("mangeDivNm", "직영");

        String body = assembler.assemble(item);

        // 개인 이름을 빼도 누가 운영하는 곳인지는 남음, 채움률도 이쪽이 높음
        assertThat(body).isEqualTo("""
                [시설 구분] 국립
                [관리 형태] 직영""");
    }

    @Test
    @DisplayName("수량 필드를 담지 않는다")
    void dropsCountFields() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("gnrlSiteCo", "0");
        item.put("autoSiteCo", "10");
        item.put("toiletCo", "8");
        item.put("siteMg1Width", "3");
        item.put("animalCmgCl", "불가능");

        String body = assembler.assemble(item);

        // 값이 0 일 때 실제로 없는 것인지 적지 않은 것인지 구분할 수 없어 값 자체를 못 믿음
        assertThat(body).isEqualTo("[반려동물 동반] 불가능");
    }

    @Test
    @DisplayName("예 또는 아니오 필드를 담지 않는다")
    void dropsYesNoFields() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("insrncAt", "N");
        item.put("trlerAcmpnyAt", "Y");
        item.put("clturEventAt", "N");
        item.put("exprnProgrm", "봄 매실따기,아로니아");

        String body = assembler.assemble(item);

        assertThat(body).isEqualTo("[체험 프로그램] 봄 매실따기,아로니아");
    }

    @Test
    @DisplayName("좌표와 주소 같은 기계용 값을 담지 않는다")
    void dropsMachineFields() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("contentId", "2758");
        item.put("facltNm", "주문진글램핑 오토캠핑장");
        item.put("mapX", "128.7344964");
        item.put("addr1", "강원특별자치도 강릉시 주문진읍 신리천로 951-9");
        item.put("firstImageUrl", "https://gocamping.or.kr/upload/camp/2758/thumb/thumb.jpg");
        item.put("modifiedtime", "2026-09-08");
        item.put("lineIntro", "계곡을 배경으로 펼쳐진 캠핑장");

        String body = assembler.assemble(item);

        // 시설 이름은 display_title 로 따로 감
        assertThat(body).isEqualTo("[한 줄 소개] 계곡을 배경으로 펼쳐진 캠핑장");
    }

    @Test
    @DisplayName("문의처를 담는다")
    void keepsTel() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tel", "033-642-4241");

        String body = assembler.assemble(item);

        // 반려동물 동반여행 쪽도 문의처를 같은 라벨로 담음
        // 빠뜨리면 화면에서 두 소스가 다르게 보임
        assertThat(body).isEqualTo("[문의처] 033-642-4241");
    }

    @Test
    @DisplayName("쉼표로 이어진 다중값은 원문 그대로 담는다")
    void keepsCommaSeparatedValuesAsIs() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sbrsCl", "전기,무선인터넷,장작판매,온수,운동시설");

        String body = assembler.assemble(item);

        // 쪼개어 시설 코드로 만드는 것은 장소 쪽이 할 일임
        assertThat(body).isEqualTo("[부대시설] 전기,무선인터넷,장작판매,온수,운동시설");
    }

    @Test
    @DisplayName("빈 값은 라벨째 뺀다")
    void dropsLabelWhenValueIsBlank() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("animalCmgCl", "");
        item.put("lineIntro", "   ");
        item.put("intro", "설명입니다");

        String body = assembler.assemble(item);

        assertThat(body).isEqualTo("[소개] 설명입니다");
    }

    @Test
    @DisplayName("값에 줄바꿈이 있으면 라벨 다음 줄부터 적는다")
    void putsMultilineValueOnItsOwnLine() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("intro", "첫 줄입니다.\n둘째 줄입니다.");

        String body = assembler.assemble(item);

        assertThat(body).isEqualTo("""
                [소개]
                첫 줄입니다.
                둘째 줄입니다.""");
    }

    @Test
    @DisplayName("담을 것이 하나도 없으면 null 이다")
    void returnsNullWhenNothingToShow() {
        assertThat(assembler.assemble(Map.of())).isNull();
        assertThat(assembler.assemble(null)).isNull();
    }

    private Map<String, Object> fullItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("contentId", "2758");
        item.put("facltNm", "주문진글램핑 오토캠핑장");
        item.put("manageSttus", "운영");
        item.put("mgcDiv", "김영길");
        item.put("animalCmgCl", "가능");
        item.put("lineIntro", "계곡을 배경으로 펼쳐진 캠핑장");
        item.put("intro", "주문진 글램핑 오토캠핑장은 강원도 강릉시 주문진읍에 자리 잡고 있다.");
        item.put("tel", "033-642-4241");
        item.put("operPdCl", "봄,여름,가을,겨울");
        item.put("operDeCl", "평일+주말");
        item.put("resveCl", "온라인실시간예약");
        item.put("brazierCl", "개별");
        item.put("lctCl", "산,숲,계곡");
        item.put("sbrsCl", "전기,무선인터넷,장작판매,온수,운동시설");
        item.put("posblFcltyCl", "계곡 물놀이");
        item.put("induty", "자동차야영장");
        item.put("facltDivNm", "민간");
        item.put("mangeDivNm", "직영");
        item.put("gnrlSiteCo", "0");
        item.put("toiletCo", "8");
        item.put("insrncAt", "N");
        item.put("mapX", "128.7344964");
        item.put("modifiedtime", "2026-09-08");
        return item;
    }
}
