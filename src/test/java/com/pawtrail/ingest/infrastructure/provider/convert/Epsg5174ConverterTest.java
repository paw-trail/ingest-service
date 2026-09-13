package com.pawtrail.ingest.infrastructure.provider.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 좌표계 정의를 문자열로 적었으므로 그 값이 맞는지를 여기서 못 박습니다.
 *
 * 틀려도 예외가 나지 않고 값이 어긋나는 것으로 끝나는 종류라
 * 실제 좌표를 넣어 결과를 견주는 것 말고는 확인할 방법이 없습니다.
 *
 * 기대값은 착수 전에 pyproj 로 같은 파일을 옮겨 낸 것입니다.
 * 그때 5,258 건을 옮겨 기존 장소와 거리를 쟀고 맞은 1,783 쌍의 중앙값이 3m 였습니다.
 * 여기 적은 넷은 그 결과에서 가져온 실제 행입니다.
 */
class Epsg5174ConverterTest {

    @Test
    @DisplayName("대학로동물병원을 옮긴다")
    void 서울_종로() {
        Epsg5174Converter.Result result =
                Epsg5174Converter.convert("199947.178659037    ", "453593.826987348    ");

        assertThat(result.converted()).isTrue();
        assertThat(new BigDecimal(result.lat()).doubleValue())
                .isCloseTo(37.5826, within(0.01));
        assertThat(new BigDecimal(result.lon()).doubleValue())
                .isCloseTo(126.9994, within(0.01));
    }

    @Test
    @DisplayName("값 뒤에 붙어 오는 공백을 다듬는다")
    void 공백을_다듬음() {
        Epsg5174Converter.Result padded =
                Epsg5174Converter.convert("199947.178659037    ", "453593.826987348    ");
        Epsg5174Converter.Result trimmed =
                Epsg5174Converter.convert("199947.178659037", "453593.826987348");

        // 소스가 "199947.178659037    " 처럼 주므로 다듬지 않으면 숫자로 읽지 못함
        assertThat(padded.lat()).isEqualTo(trimmed.lat());
        assertThat(padded.lon()).isEqualTo(trimmed.lon());
    }

    @Test
    @DisplayName("옮긴 결과가 대한민국 범위 안이다")
    void 한국_범위_안() {
        // 실측에서 5,258 건 전부 이 범위였음
        // 벗어나면 좌표계 정의가 틀린 것이라 여기서 드러남
        String[][] samples = {
                {"201300.617267399    ", "452512.234050389    "},
                {"196420.140799836    ", "455175.041431888    "},
        };

        for (String[] sample : samples) {
            Epsg5174Converter.Result result = Epsg5174Converter.convert(sample[0], sample[1]);

            assertThat(result.converted()).isTrue();
            double lat = new BigDecimal(result.lat()).doubleValue();
            double lon = new BigDecimal(result.lon()).doubleValue();
            assertThat(lat).isBetween(33.0, 38.7);
            assertThat(lon).isBetween(124.5, 132.0);
        }
    }

    @Test
    @DisplayName("자릿수를 일곱 자리로 맞춘다")
    void 일곱_자리() {
        // 받는 쪽 컬럼이 numeric(10,7) 이라 미리 맞춰 둠
        // 그래야 보낸 값과 저장된 값이 같아 나중에 대조할 때 흔들리지 않음
        Epsg5174Converter.Result result =
                Epsg5174Converter.convert("199947.178659037", "453593.826987348");

        assertThat(result.lat()).matches("-?\\d+\\.\\d{7}");
        assertThat(result.lon()).matches("-?\\d+\\.\\d{7}");
    }

    @Test
    @DisplayName("값이 없거나 숫자가 아니면 비운다")
    void 못_읽으면_비움() {
        // 그 건은 좌표 없이 보내고 받는 쪽이 주소로 지오코딩함
        assertThat(Epsg5174Converter.convert(null, "453593.8").converted()).isFalse();
        assertThat(Epsg5174Converter.convert("199947.1", null).converted()).isFalse();
        assertThat(Epsg5174Converter.convert("", "").converted()).isFalse();
        assertThat(Epsg5174Converter.convert("   ", "   ").converted()).isFalse();
        assertThat(Epsg5174Converter.convert("좌표없음", "453593.8").converted()).isFalse();
    }

    @Test
    @DisplayName("비운 결과는 값이 null 이다")
    void 비우면_null() {
        Epsg5174Converter.Result result = Epsg5174Converter.convert(null, null);

        assertThat(result.lat()).isNull();
        assertThat(result.lon()).isNull();
    }
}
