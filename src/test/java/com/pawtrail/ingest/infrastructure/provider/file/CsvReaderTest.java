package com.pawtrail.ingest.infrastructure.provider.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 파일이 어긋났을 때 조용히 지나가지 않는지를 못 박습니다.
 *
 * 이 검사가 없으면 컬럼 이름이 바뀐 파일이 0 건을 보내고 정상으로 마감됩니다.
 * 오류가 나지 않아 아무도 알아채지 못하는 종류라 여기서 고정합니다.
 */
class CsvReaderTest {

    private final CsvReader reader = new CsvReader();

    @TempDir
    Path directory;

    @Test
    @DisplayName("헤더와 행을 읽어 하나씩 넘긴다")
    void 행마다_넘김() throws IOException {
        Path file = write("a.csv", StandardCharsets.UTF_8,
                "관리번호,사업장명,영업상태명",
                "1,가동물병원,영업/정상",
                "2,나동물병원,폐업");

        List<Map<String, String>> rows = new ArrayList<>();
        int read = reader.read(file, StandardCharsets.UTF_8, 3, Set.of(), rows::add);

        assertThat(read).isEqualTo(2);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("사업장명")).isEqualTo("가동물병원");
    }

    @Test
    @DisplayName("값 앞뒤 공백을 털어 낸다")
    void 공백을_털어_냄() throws IOException {
        // 행정안전부가 좌표를 "199947.178    " 처럼 줌
        Path file = write("b.csv", StandardCharsets.UTF_8,
                "관리번호,좌표정보(X)",
                "1,199947.178    ");

        List<Map<String, String>> rows = new ArrayList<>();
        reader.read(file, StandardCharsets.UTF_8, 2, Set.of(), rows::add);

        assertThat(rows.get(0).get("좌표정보(X)")).isEqualTo("199947.178");
    }

    @Test
    @DisplayName("CP949 파일을 읽는다")
    void CP949() throws IOException {
        Charset cp949 = Charset.forName("CP949");
        Path file = write("c.csv", cp949,
                "관리번호,사업장명",
                "1,대학로동물병원");

        List<Map<String, String>> rows = new ArrayList<>();
        reader.read(file, cp949, 2, Set.of(), rows::add);

        assertThat(rows.get(0).get("사업장명")).isEqualTo("대학로동물병원");
    }

    @Test
    @DisplayName("컬럼 수가 다르면 접는다")
    void 컬럼_수가_다르면_접음() throws IOException {
        Path file = write("d.csv", StandardCharsets.UTF_8,
                "관리번호,사업장명",
                "1,가동물병원");

        assertThatThrownBy(() -> reader.read(file, StandardCharsets.UTF_8, 3, Set.of(), row -> {
        }))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", IngestErrorCode.SOURCE_FILE_MALFORMED);
    }

    @Test
    @DisplayName("꼭 있어야 하는 컬럼이 없으면 접는다")
    void 필수_컬럼이_없으면_접음() throws IOException {
        // 컬럼 수는 그대로인데 이름 하나가 바뀐 경우임
        // 이것을 안 보면 모든 행이 비영업으로 판정되어 0 건을 보내고 정상으로 마감됨
        Path file = write("e.csv", StandardCharsets.UTF_8,
                "관리번호,사업장명,영업상태",
                "1,가동물병원,영업/정상");

        assertThatThrownBy(() -> reader.read(file, StandardCharsets.UTF_8, 3,
                Set.of("관리번호", "사업장명", "영업상태명"), row -> {
                }))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", IngestErrorCode.SOURCE_FILE_MALFORMED);
    }

    @Test
    @DisplayName("필수 컬럼이 다 있으면 지나간다")
    void 필수_컬럼이_다_있으면_지나감() throws IOException {
        Path file = write("f.csv", StandardCharsets.UTF_8,
                "관리번호,사업장명,영업상태명",
                "1,가동물병원,영업/정상");

        List<Map<String, String>> rows = new ArrayList<>();
        int read = reader.read(file, StandardCharsets.UTF_8, 3,
                Set.of("관리번호", "영업상태명"), rows::add);

        assertThat(read).isEqualTo(1);
    }

    @Test
    @DisplayName("읽을 수 없는 파일이면 접는다")
    void 파일이_없으면_접음() {
        Path missing = directory.resolve("없는파일.csv");

        assertThatThrownBy(() -> reader.read(missing, StandardCharsets.UTF_8, 3, Set.of(), row -> {
        }))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", IngestErrorCode.SOURCE_FILE_NOT_READABLE);
    }

    private Path write(String name, Charset charset, String... lines) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n", charset);
        return file;
    }
}
