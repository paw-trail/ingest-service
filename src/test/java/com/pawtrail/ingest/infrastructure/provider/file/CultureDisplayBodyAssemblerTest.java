package com.pawtrail.ingest.infrastructure.provider.file;

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
 * 이 소스만의 자리가 둘 있습니다.
 * 값이 없을 때 빈 칸이 아니라 "정보없음" 같은 문자열로 오는 것과,
 * 예 또는 아니오로 오는 값을 그대로 담는 것입니다.
 */
class CultureDisplayBodyAssemblerTest {

    private final CultureDisplayBodyAssembler assembler = new CultureDisplayBodyAssembler();

    @Test
    @DisplayName("동반 조건 · 설명 · 이용 안내 · 장소 성격 차례로 덩어리를 만든다")
    void assemblesInOrder() {
        String body = assembler.assemble(fullRow());

        assertThat(body).isEqualTo("""
                [반려동물 동반] Y
                [입장 가능 크기] 모두 가능
                [제한사항] 목줄, 배변봉투

                [장소 설명] 한강을 따라 걷는 산책로가 있는 공원

                [문의처] 02-3780-0561
                [운영시간] 상시 개방
                [휴무일] 연중무휴
                [이용료] 무료
                [주차] Y

                [실내] N
                [실외] Y
                [분류] 반려동반여행
                [세부 분류] 여행지""");
    }

    @Test
    @DisplayName("값이 없을 때 쓰는 문자열 셋을 라벨째 뺀다")
    void dropsBlankMarkers() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("반려동물 동반 가능정보", "Y");
        row.put("반려동물 전용 정보", "해당없음");
        row.put("반려동물 제한사항", "제한사항 없음");
        row.put("홈페이지", "정보없음");
        row.put("휴무일", "없음");

        String body = assembler.assemble(row);

        // 화면에 「휴무일 정보없음」이 뜨면 안 됨
        // "제한사항 없음" 은 문장이라 그대로 담김 — 목록에 있는 것은 정확히 일치할 때만 뺌
        assertThat(body).isEqualTo("""
                [반려동물 동반] Y
                [제한사항] 제한사항 없음""");
    }

    @Test
    @DisplayName("예 또는 아니오로 오는 값을 그대로 담는다")
    void keepsYesNoAsIs() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("반려동물 동반 가능정보", "Y");
        row.put("장소(실내) 여부", "N");
        row.put("장소(실외)여부", "Y");
        row.put("주차 가능여부", "N");

        String body = assembler.assemble(row);

        // 고캠핑에서는 같은 형태를 통째로 뺐으나 그쪽은 부수 정보였음
        // 이쪽은 이 서비스가 보여주려는 값 그 자체라 담음
        // 「가능」이나 「불가」로 바꾸지 않음 — 우리가 문장을 만들면 원문이 아니게 됨
        assertThat(body).isEqualTo("""
                [반려동물 동반] Y

                [주차] N

                [실내] N
                [실외] Y""");
    }

    @Test
    @DisplayName("좌표와 주소 조각 같은 기계용 값을 담지 않는다")
    void dropsMachineColumns() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("시설명", "한강공원");
        row.put("카테고리1", "반려동물업");
        row.put("시도 명칭", "서울특별시");
        row.put("위도", "37.5289");
        row.put("경도", "126.9337");
        row.put("도로명주소", "서울특별시 영등포구 여의동로 330");
        row.put("우편번호", "07331");
        row.put("최종작성일", "2025-03-24");
        row.put("기본 정보_장소설명", "한강을 따라 걷는 산책로");

        String body = assembler.assemble(row);

        // 시설명은 표시용 제목으로 따로 감
        // 카테고리1 은 모든 행이 "반려동물업" 이라 뜻이 없음
        assertThat(body).isEqualTo("[장소 설명] 한강을 따라 걷는 산책로");
    }

    @Test
    @DisplayName("값에 줄바꿈이 있으면 라벨 다음 줄부터 적는다")
    void putsMultilineValueOnItsOwnLine() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("운영시간", "평일 09:00~18:00\n주말 10:00~17:00");

        String body = assembler.assemble(row);

        assertThat(body).isEqualTo("""
                [운영시간]
                평일 09:00~18:00
                주말 10:00~17:00""");
    }

    @Test
    @DisplayName("담을 것이 하나도 없으면 null 이다")
    void returnsNullWhenNothingToShow() {
        assertThat(assembler.assemble(Map.of())).isNull();
        assertThat(assembler.assemble(null)).isNull();
    }

    @Test
    @DisplayName("사전에도 제외 목록에도 없는 컬럼을 알려준다")
    void reportsUnknownColumns() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("시설명", "한강공원");
        row.put("반려동물 동반 가능정보", "Y");
        row.put("새로생긴컬럼", "언젠가 올 값");

        // 소스가 컬럼을 늘리면 조용히 빠지는데 아무 신호가 없으면 알아챌 방법이 없음
        assertThat(assembler.unknownColumns(row)).containsExactly("새로생긴컬럼");
    }

    /**
     * 실제 파일의 서른한 컬럼 가운데 본문에 들어가는 것을 채웁니다.
     */
    private Map<String, String> fullRow() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("시설명", "여의도한강공원");
        row.put("카테고리1", "반려동물업");
        row.put("카테고리2", "반려동반여행");
        row.put("카테고리3", "여행지");
        row.put("위도", "37.5289");
        row.put("경도", "126.9337");
        row.put("지번주소", "서울특별시 영등포구 여의도동 8");
        row.put("전화번호", "02-3780-0561");
        row.put("홈페이지", "정보없음");
        row.put("휴무일", "연중무휴");
        row.put("운영시간", "상시 개방");
        row.put("주차 가능여부", "Y");
        row.put("입장(이용료)가격 정보", "무료");
        row.put("반려동물 동반 가능정보", "Y");
        row.put("반려동물 전용 정보", "해당없음");
        row.put("입장 가능 동물 크기", "모두 가능");
        row.put("반려동물 제한사항", "목줄, 배변봉투");
        row.put("장소(실내) 여부", "N");
        row.put("장소(실외)여부", "Y");
        row.put("기본 정보_장소설명", "한강을 따라 걷는 산책로가 있는 공원");
        row.put("애견 동반 추가 요금", "없음");
        row.put("최종작성일", "2025-03-24");
        return row;
    }
}
