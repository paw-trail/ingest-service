package com.pawtrail.ingest.infrastructure.provider.file;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * 저장소에 함께 커밋한 CSV 를 읽습니다.
 *
 * 소스가 REST API 가 아니라 파일입니다.
 * 그래서 호출 허용량도 재시도도 없고 다시 읽는 비용이 사실상 없습니다.
 *
 * 소스가 둘인데 읽는 방법이 같아 하나로 둡니다.
 * 문화정보원과 행정안전부가 인코딩과 컬럼 수만 다르고
 * 헤더를 읽고 행마다 넘기고 컬럼 수로 형식을 검사하는 일은 똑같습니다.
 * 그 셋은 값이지 다른 로직이 아닙니다.
 *
 * 수집기와 클라이언트와 변환기를 소스마다 나눈 것과는 다릅니다.
 * 그쪽은 오류 코드 체계와 거르는 판정과 키 매핑이 실제로 달라 조건문으로 담으면
 * 소스를 더할 때마다 그 메서드가 길어집니다.
 * 여기서 나누면 백 줄이 거의 같은 코드가 둘이 되고 한쪽만 고쳐 어긋날 자리가 생깁니다.
 *
 * *행을 모아서 돌려주지 않고 하나씩 넘깁니다.
 *  문화정보원 파일이 7만 행이고 컬럼이 서른한 개라 전부 들고 있으면
 *  이백 메가바이트에 가깝습니다.
 *  담을 것은 그중 만 삼천 행뿐이라, 받는 쪽이 그 자리에서 걸러 내면
 *  가장 많이 쥐고 있을 때가 오십 메가바이트 아래로 내려갑니다.
 *
 * *직접 가르지 않고 라이브러리를 쓰는 이유가 있습니다.
 *  값 안에 쉼표가 78,494개 있어 쉼표로 나누면 안 됩니다.
 *  지금 파일은 필드 안 줄바꿈도 이스케이프된 따옴표도 없어 서른 줄이면 되지만,
 *  다음 판이 그럴 것이라는 보장이 없습니다.
 */
@Slf4j
@Component
public class CsvReader {

    /**
     * 파일 맨 앞에 오는 바이트 순서 표시입니다.
     *
     * UTF-8 로 읽으면 이 세 바이트가 문자 하나로 들어옵니다.
     * 눈에 보이지 않아서 첫 컬럼 이름이 어긋난 것을 알아채기 어렵습니다.
     * 문화정보원 파일에 붙어 있고 행정안전부 파일에는 없습니다.
     * 있으면 걷어내고 없으면 그대로 두므로 소스를 가르지 않아도 됩니다.
     */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * 파일을 훑으며 행 하나씩 넘깁니다.
     *
     * 컬럼을 골라 담지 않고 전부 넘깁니다.
     * 필드를 추려 담으면 나중에 더 필요해졌을 때 파일을 다시 읽어야 합니다.
     *
     * 값은 앞뒤 공백만 털어 냅니다.
     * 값이 없을 때 쓰는 문자열을 바꾸지 않습니다.
     * 문화정보원은 "정보없음" 으로 주고 행정안전부는 빈 칸으로 주는데,
     * 어느 컬럼에서 그것이 미기입이고 어느 컬럼에서 실제 값인지는 받는 쪽이 판단합니다.
     *
     * 좌표 뒤에 붙어 오는 공백도 여기서 털립니다.
     * 행정안전부가 "199947.178659037    " 처럼 주는데 다듬지 않으면 숫자로 읽지 못합니다.
     *
     * 컬럼 수와 함께 꼭 있어야 하는 이름도 봅니다.
     *
     * 수만 보면 이름이 바뀐 것을 못 잡습니다.
     * 스물다섯 개를 유지한 채 영업상태명 하나가 바뀌면 모든 행이 비영업으로 판정되어,
     * 빈 것을 보내고 정상으로 마감됩니다. 오류가 나지 않아 아무도 알아채지 못합니다.
     * 관리번호나 사업장명이 바뀌어도 같은 결과가 됩니다.
     *
     * 전부 적지 않고 없으면 조용히 어긋나는 것만 적습니다.
     * 값이 비는 것은 화면에서 드러나지만, 거르는 조건과 식별자는 그렇지 않습니다.
     *
     * @param path            읽을 파일
     * @param charset         파일 인코딩. 문화정보원은 UTF-8, 행정안전부는 CP949
     * @param expectedColumns 헤더와 각 행의 컬럼 수. 어긋난 줄은 건너뛰고 경고를 남김
     * @param requiredColumns 없으면 읽기를 접을 컬럼 이름. 비우면 이름은 보지 않음
     * @param rowSink         행마다 불립니다. 컬럼 이름을 열쇠로 하고 순서는 파일 그대로입니다
     * @return 넘긴 행 수. 컬럼 수가 어긋나 건너뛴 것은 세지 않습니다
     */
    public int read(Path path, Charset charset, int expectedColumns,
                    Set<String> requiredColumns, Consumer<Map<String, String>> rowSink) {

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

        try (Reader reader = openWithoutByteOrderMark(path, charset);
             CSVParser parser = CSVParser.parse(reader, format)) {

            List<String> header = parser.getHeaderNames();
            if (header.size() != expectedColumns) {
                log.error("헤더 컬럼 수가 다릅니다. expected={} actual={} header={}",
                        expectedColumns, header.size(), header);
                throw new CustomException(IngestErrorCode.SOURCE_FILE_MALFORMED);
            }
            if (!header.containsAll(requiredColumns)) {
                List<String> missing = requiredColumns.stream()
                        .filter(column -> !header.contains(column))
                        .toList();
                log.error("꼭 있어야 하는 컬럼이 없습니다. missing={} header={}", missing, header);
                throw new CustomException(IngestErrorCode.SOURCE_FILE_MALFORMED);
            }

            for (CSVRecord record : parser) {
                if (record.size() != expectedColumns) {
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
    private Reader openWithoutByteOrderMark(Path path, Charset charset) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path, charset);
        reader.mark(1);
        if (reader.read() != BYTE_ORDER_MARK) {
            reader.reset();
        }
        return reader;
    }
}
