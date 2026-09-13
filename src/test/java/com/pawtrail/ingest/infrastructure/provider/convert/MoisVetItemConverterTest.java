package com.pawtrail.ingest.infrastructure.provider.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 어느 컬럼을 어느 칸에 넣는지를 고정합니다.
 *
 * 이 소스만 다른 점이 둘이라 그 둘에 특히 촘촘히 둡니다.
 * 좌표계를 옮기는 것과 지역번호가 빠진 전화를 버리는 것입니다.
 */
class MoisVetItemConverterTest {

    private final MoisVetItemConverter converter = new MoisVetItemConverter();

    private Map<String, Object> row(String name, String tel, String x, String y) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("사업장명", name);
        row.put("도로명주소", "서울특별시 종로구 창경궁로 261 (명륜2가)");
        row.put("지번주소", "서울특별시 종로구 명륜2가 5-99");
        row.put("전화번호", tel);
        row.put("좌표정보(X)", x);
        row.put("좌표정보(Y)", y);
        return row;
    }

    @Test
    @DisplayName("맡은 소스는 MOIS_VET 다")
    void handlesMoisVet() {
        assertThat(converter.source()).isEqualTo(SourceType.MOIS_VET);
    }

    @Test
    @DisplayName("사업장명이 없으면 비운다")
    void 이름이_없으면_null() {
        // 받는 쪽이 이름을 필수로 요구해 그대로 보내면 그 묶음이 통째로 거절됨
        assertThat(converter.convert("300000001019950002",
                row(null, "0212345678", "199947.1", "453593.8"))).isNull();
        assertThat(converter.convert("300000001019950002",
                row("   ", "0212345678", "199947.1", "453593.8"))).isNull();
    }

    @Test
    @DisplayName("분류 코드를 비운다")
    void 분류가_없음() {
        // 인허가 대장이라 분류 코드가 없음
        // 받는 쪽이 소스만 보고 동물병원으로 정함
        PlaceBulkItem item = converter.convert("300000001019950002",
                row("대학로동물병원", "0212345678", "199947.1", "453593.8"));

        assertThat(item.lcls1()).isNull();
        assertThat(item.lcls2()).isNull();
        assertThat(item.lcls3()).isNull();
    }

    @Nested
    @DisplayName("좌표")
    class Coordinate {

        @Test
        @DisplayName("옮기면 출처를 CONVERTED 로 둔다")
        void 옮기면_CONVERTED() {
            PlaceBulkItem item = converter.convert("300000001019950002",
                    row("대학로동물병원", null, "199947.178659037    ", "453593.826987348    "));

            assertThat(item.lat()).isNotNull();
            assertThat(item.lon()).isNotNull();
            assertThat(item.coordSource()).isEqualTo("CONVERTED");
        }

        @Test
        @DisplayName("옮기지 못하면 출처도 비운다")
        void 못_옮기면_출처도_비움() {
            // 값이 없는데 출처만 있으면 받는 쪽이 지오코딩으로 채운 뒤에도
            // 우리가 적어 둔 출처가 남아 어긋남
            PlaceBulkItem item = converter.convert("300000001019950002",
                    row("불광동물병원", null, "", ""));

            assertThat(item.lat()).isNull();
            assertThat(item.lon()).isNull();
            assertThat(item.coordSource()).isNull();
        }
    }

    @Nested
    @DisplayName("전화번호")
    class Phone {

        @Test
        @DisplayName("열 자리와 열한 자리는 숫자 그대로 넘긴다")
        void 열_자리_이상() {
            // 하이픈을 넣지 않음, 어디에 넣을지는 표시 형식이고 화면이 정할 일임
            assertThat(converter.convert("1", row("가", "0222377582", "199947.1", "453593.8")).tel())
                    .isEqualTo("0222377582");
            assertThat(converter.convert("1", row("가", "07088687585", "199947.1", "453593.8")).tel())
                    .isEqualTo("07088687585");
        }

        @Test
        @DisplayName("지역번호가 빠진 번호는 버린다")
        void 여덟_아홉_자리는_버림() {
            // 실측에서 511 건이 이랬음
            // "36728441" 은 서울 국번만 있어 그대로 걸 수 없음
            // 소재지 시도로 앞자리를 유추하는 것은 추정이라 하지 않음
            assertThat(converter.convert("1", row("가", "36728441", "199947.1", "453593.8")).tel())
                    .isNull();
            assertThat(converter.convert("1", row("가", "027449098", "199947.1", "453593.8")).tel())
                    .isNull();
        }

        @Test
        @DisplayName("하이픈이 섞여 있어도 숫자만 세어 판단한다")
        void 하이픈_제거() {
            // 실측 파일에는 하이픈이 한 건도 없었으나 다음 판이 그럴 것이라는 보장이 없음
            assertThat(converter.convert("1", row("가", "02-2237-7582", "199947.1", "453593.8")).tel())
                    .isEqualTo("0222377582");
        }

        @Test
        @DisplayName("비어 있으면 비운다")
        void 비면_null() {
            assertThat(converter.convert("1", row("가", null, "199947.1", "453593.8")).tel())
                    .isNull();
            assertThat(converter.convert("1", row("가", "", "199947.1", "453593.8")).tel())
                    .isNull();
        }
    }
}
