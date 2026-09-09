package com.pawtrail.ingest.infrastructure.provider.file;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * 한국문화정보원 문화시설 CSV 를 읽습니다.
 *
 * 소스가 REST API 가 아니라 저장소에 함께 커밋한 파일입니다.
 * 그래서 호출 허용량도 재시도도 없고, 다시 읽는 비용이 사실상 없습니다.
 *
 * *행을 모아서 돌려주지 않고 하나씩 넘깁니다.
 *  파일이 7만 행이고 컬럼이 서른한 개라 전부 들고 있으면 이백 메가바이트에 가깝습니다.
 *  문자열 내용이 백십 메가바이트이고 지도 항목이 팔십 메가바이트쯤입니다.
 *  담을 것은 그중 만 삼천 행뿐이라, 받는 쪽이 그 자리에서 걸러 내면
 *  가장 많이 쥐고 있을 때가 오십 메가바이트 아래로 내려갑니다.
 *  컨테이너에 줄 힙을 아직 정하지 않았는데 흔히 쓰는 크기에서 이 차이가 큽니다.
 *
 * *직접 가르지 않고 라이브러리를 쓰는 이유가 있습니다.
 *  값 안에 쉼표가 78,494개 있어 쉼표로 나누면 안 됩니다.
 *  지금 파일은 필드 안 줄바꿈도 이스케이프된 따옴표도 없어 서른 줄이면 되지만,
 *  다음 판이 그럴 것이라는 보장이 없습니다.
 *  그때 깨지면 아래 컬럼 수 검사로 알아채기는 해도 고치려면 결국 파서를 다시 짜야 합니다.
 *
 * 파일 앞에 바이트 순서 표시가 붙어 있습니다.
 * 그것을 걷어내지 않으면 첫 컬럼 이름 앞에 보이지 않는 문자가 붙어
 * 시설명을 찾을 수 없게 됩니다.
 */
@Slf4j
@Component
public class CultureCsvReader {

    /**
     * 파일 맨 앞에 오는 바이트 순서 표시입니다.
     *
     * UTF-8 로 읽으면 이 세 바이트가 문자 하나로 들어옵니다.
     * 눈에 보이지 않아서 첫 컬럼 이름이 어긋난 것을 알아채기 어렵습니다.
     */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * 이 파일의 컬럼 수입니다.
     *
     * 어긋난 줄은 넘기지 않고 경고를 남깁니다.
     * 파서가 형식을 잘못 읽으면 필드 수가 먼저 어긋나므로 여기서 드러납니다.
     * 2026년 9월 9일 실측에서 70,650행 전부 서른한 개였습니다.
     */
    private static final int EXPECTED_COLUMN_COUNT = 31;

    /**
     * 파일을 훑으며 행 하나씩 넘깁니다.
     *
     * 컬럼을 골라 담지 않고 전부 넘깁니다.
     * 원본을 통째로 보관하는 것이 이 서비스의 일이라, 필드를 추려 담으면
     * 나중에 더 필요해졌을 때 파일을 다시 읽어야 합니다.
     *
     * 값은 앞뒤 공백만 털어 냅니다.
     * 이 소스는 값이 없을 때 빈 칸이 아니라 "정보없음" 같은 문자열로 주는데
     * 그것을 여기서 바꾸지 않습니다. 원본은 온 그대로 넘깁니다.
     *
     * 넘긴 지도는 받는 쪽의 것입니다. 이 메서드는 그것을 다시 들여다보지 않으므로
     * 받는 쪽이 버리면 그 자리에서 회수됩니다.
     *
     * @param path     읽을 파일. 설정의 app.ingest.culture.file-path 에서 옵니다
     * @param rowSink  행마다 불립니다. 컬럼 이름을 열쇠로 하고 순서는 파일 그대로입니다
     * @return 넘긴 행 수. 컬럼 수가 어긋나 건너뛴 것은 세지 않습니다
     */
    public int read(Path path, Consumer<Map<String, String>> rowSink) {
        if (!Files.isReadable(path)) {
            log.error("CSV 파일을 읽을 수 없습니다. path={}", path.toAbsolutePath());
            throw new CustomException(IngestErrorCode.SOURCE_FILE_NOT_READABLE);
        }

        int delivered = 0;
        int skipped = 0;

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get();

        try (Reader reader = openWithoutByteOrderMark(path);
             CSVParser parser = CSVParser.parse(reader, format)) {

            List<String> header = parser.getHeaderNames();
            if (header.size() != EXPECTED_COLUMN_COUNT) {
                log.error("헤더 컬럼 수가 다릅니다. expected={} actual={} header={}",
                        EXPECTED_COLUMN_COUNT, header.size(), header);
                throw new CustomException(IngestErrorCode.SOURCE_FILE_MALFORMED);
            }

            for (CSVRecord record : parser) {
                if (record.size() != EXPECTED_COLUMN_COUNT) {
                    // 파서가 형식을 잘못 읽으면 여기서 먼저 드러남
                    // 그 줄만 건너뛰고 이어 가되 무엇이 빠졌는지는 남김
                    log.warn("컬럼 수가 달라 건너뜁니다. line={} size={}",
                            record.getRecordNumber(), record.size());
                    skipped++;
                    continue;
                }

                Map<String, String> row = new LinkedHashMap<>();
                for (String column : header) {
                    String value = record.get(column);
                    row.put(column, value == null ? "" : value.strip());
                }
                rowSink.accept(row);
                delivered++;
            }

        } catch (IOException e) {
            log.error("CSV 파일을 읽다 실패했습니다. path={}", path.toAbsolutePath(), e);
            throw new CustomException(IngestErrorCode.SOURCE_FILE_NOT_READABLE, e);
        }

        log.info("CSV 를 읽었습니다. path={} 행={} 건너뜀={}",
                path.getFileName(), delivered, skipped);
        return delivered;
    }

    /**
     * 바이트 순서 표시를 걷어내고 엽니다.
     *
     * 라이브러리를 하나 더 쓰면 이것을 대신해 주지만 그러자고 의존성을 늘리지 않습니다.
     * 첫 문자를 보고 표시면 버리는 것이 전부입니다.
     */
    private Reader openWithoutByteOrderMark(Path path) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        reader.mark(1);
        if (reader.read() != BYTE_ORDER_MARK) {
            reader.reset();
        }
        return reader;
    }
}
