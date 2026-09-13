package com.pawtrail.ingest.application.service;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.provider.PlaceItemConverter;
import com.pawtrail.ingest.domain.provider.PlaceLinkClient;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.config.PlaceLinkProperties;
import com.pawtrail.ingest.infrastructure.provider.file.CsvReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 행정안전부 동물병원 CSV 를 읽어 장소 서비스로 바로 보냅니다.
 *
 * 실행기가 셋이 된 이유입니다.
 *
 * <pre>
 * IngestExecutor        바깥에서 받아 우리 표에 담음
 * PlaceLinkExecutor     담아 둔 것을 장소 서비스로 넘김
 * 이 클래스              파일을 읽어 바로 넘김.  우리 표를 거치지 않음
 * </pre>
 *
 * 이 소스만 원본을 보관하지 않습니다.
 * 인허가 대장이라 동반 조건 문구가 없어 다시 해석할 재료도,
 * 장소 상세의 원문보기에 보여줄 근거도 없습니다.
 * 담으면 보관만 하고 아무도 읽지 않는 행이 오천 건 생깁니다.
 *
 * 앞의 둘을 고쳐 담지 않은 이유가 각각 있습니다.
 * 수집 실행기는 넘겨받은 것을 원본 초안으로 만들어 기록기에 보내는데
 * 그 초안을 만드는 팩터리가 이 소스를 막고 있습니다.
 * 넘기기 실행기는 우리 표를 읽는 것이 시작이라 읽을 행이 없습니다.
 *
 * 진행 위치를 남기지 않습니다.
 * 끊기면 처음부터 다시 합니다. 파일을 다시 읽는 비용이 없고
 * 받는 쪽이 같은 것을 여러 번 받아도 같은 결과를 냅니다.
 *
 * 돌려받은 식별자를 쓰지 않습니다.
 * 채울 자리가 없기 때문입니다. 우리 표에 이 소스의 행이 없습니다.
 * 그래서 넘기기 기록기를 부르지 않고 마감만 수집 기록기에 맡깁니다.
 */
@Slf4j
@Service
public class MoisVetDirectExecutor {

    /**
     * 이 파일의 컬럼 수입니다.
     *
     * 2026년 9월 8일 판에서 10,618 행 전부 스물다섯 개였습니다.
     * 어긋나면 읽는 쪽이 그 줄을 건너뛰고 경고를 남깁니다.
     */
    private static final int COLUMN_COUNT = 25;

    /**
     * 담을 영업 상태입니다.
     *
     * 실측에서 영업/정상 5,474 · 폐업 5,057 · 취소 59 · 휴업 28 이었습니다.
     *
     * 휴업을 담지 않는 이유는 받는 쪽에 그 상태를 담을 값이 없기 때문입니다.
     * 장소의 상태가 운영과 폐업 둘뿐이라 휴업을 어느 쪽에 넣어도 거짓말이 됩니다.
     * 값을 늘리면 화면과 필터와 검색 색인이 따라오는데 스물여덟 건이 그럴 무게가 아닙니다.
     * 재개업하면 영업/정상 이 되어 다음 실행에 들어옵니다.
     */
    private static final String ACTIVE_STATUS = "영업/정상";

    private static final String STATUS_COLUMN = "영업상태명";
    private static final String ID_COLUMN = "관리번호";

    private final CsvReader csvReader;
    private final PlaceLinkClient placeLinkClient;
    private final ChunkWriter chunkWriter;
    private final IngestRunRepository ingestRunRepository;
    private final IngestProperties ingestProperties;
    private final PlaceLinkProperties placeLinkProperties;
    private final PlaceItemConverter converter;

    public MoisVetDirectExecutor(
            CsvReader csvReader,
            PlaceLinkClient placeLinkClient,
            ChunkWriter chunkWriter,
            IngestRunRepository ingestRunRepository,
            IngestProperties ingestProperties,
            PlaceLinkProperties placeLinkProperties,
            List<PlaceItemConverter> converterList) {

        this.csvReader = csvReader;
        this.placeLinkClient = placeLinkClient;
        this.chunkWriter = chunkWriter;
        this.ingestRunRepository = ingestRunRepository;
        this.ingestProperties = ingestProperties;
        this.placeLinkProperties = placeLinkProperties;

        // 이 실행기는 소스가 하나라 지도를 두지 않고 그 하나를 찾아 둡니다
        this.converter = converterList.stream()
                .filter(each -> each.source() == SourceType.MOIS_VET)
                .findFirst()
                .orElse(null);
    }

    /**
     * 파일을 읽어 장소 서비스로 보냅니다.
     *
     * 마감하는 길이 둘뿐입니다. 끝까지 마친 것과 실패한 것입니다.
     * 바깥을 부르지 않아 허용량에 걸리는 일이 없고,
     * 상대가 한 서비스라 한 번 실패하면 계속 실패합니다.
     */
    @Async
    public void execute(UUID runId) {
        IngestRun run = ingestRunRepository.findById(runId).orElse(null);
        if (run == null) {
            log.error("실행 기록을 찾지 못했습니다. runId={}", runId);
            return;
        }

        // 트리거가 이미 확인하지만 그 사이에 빈 구성이 바뀔 수 있으므로 한 번 더 봅니다
        if (converter == null) {
            chunkWriter.fail(runId, Map.of(), "등록된 변환기가 없습니다: " + SourceType.MOIS_VET);
            return;
        }

        log.info("동물병원 인허가를 장소 서비스로 보냅니다. runId={}", runId);

        IngestProperties.MoisVet config = ingestProperties.moisVet();
        int chunkSize = placeLinkProperties.chunkSize();

        List<String> skipped = new ArrayList<>();
        List<PlaceBulkItem> buffer = new ArrayList<>(chunkSize);
        Counter counter = new Counter();

        try {
            int read = csvReader.read(
                    Path.of(config.filePath()),
                    Charset.forName(config.charset()),
                    COLUMN_COUNT,
                    row -> {
                        if (!ACTIVE_STATUS.equals(row.get(STATUS_COLUMN))) {
                            return;
                        }
                        counter.active++;

                        String sourceId = row.get(ID_COLUMN);
                        if (sourceId == null || sourceId.isBlank()) {
                            // 실측에서는 5,474 건 전부 채워져 있었습니다
                            // 그래도 세는 이유는 새 판에서 비면 조용히 사라지기 때문입니다
                            skipped.add("(식별자 없음) " + row.get("사업장명"));
                            return;
                        }

                        PlaceBulkItem item = converter.convert(sourceId, toPayload(row));
                        if (item == null) {
                            // 이름을 찾지 못한 건입니다
                            // 받는 쪽이 이름을 필수로 요구해 그대로 보내면 그 묶음이 통째로 거절됩니다
                            skipped.add(sourceId);
                            return;
                        }

                        buffer.add(item);
                        if (buffer.size() >= chunkSize) {
                            counter.sent += send(buffer);
                        }
                    });

            counter.sent += send(buffer);

            chunkWriter.complete(runId, Map.of(), skipped, List.of());
            log.info("동물병원 인허가를 보냈습니다. runId={} 읽음={} 영업중={} 보냄={} 건너뜀={}",
                    runId, read, counter.active, counter.sent, skipped.size());

        } catch (Exception e) {
            log.error("동물병원 인허가를 보내는 중 오류가 났습니다. runId={}", runId, e);
            chunkWriter.fail(runId, Map.of(), toMessage(e));
        }
    }

    /**
     * 모아 둔 것을 보내고 비웁니다.
     *
     * 돌려받은 값을 쓰지 않습니다.
     * 우리 표에 이 소스의 행이 없어 채울 자리가 없습니다.
     * 그래도 부르는 쪽이 응답을 받는 이유는 클라이언트가 계약을 검사하기 때문입니다.
     * 어긋나면 예외를 던져 실행이 실패로 마감됩니다.
     */
    private int send(List<PlaceBulkItem> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        int size = buffer.size();
        placeLinkClient.send(List.copyOf(buffer));
        buffer.clear();
        return size;
    }

    /**
     * 읽은 행을 변환기가 받는 형태로 바꿉니다.
     *
     * 변환기가 원본을 지도로 받게 되어 있습니다.
     * 다른 소스는 우리 표의 원본을 읽어 넘기고 이 소스는 파일 행을 그대로 넘깁니다.
     * 담는 값이 전부 문자열이라 형 변환이 아니라 자리만 옮기는 일입니다.
     */
    private Map<String, Object> toPayload(Map<String, String> row) {
        return new LinkedHashMap<>(row);
    }

    /**
     * 예외를 실행 기록에 남길 한 줄로 만듭니다.
     */
    private String toMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 콜백 안에서 세는 값들입니다.
     *
     * 람다가 바깥 지역 변수를 바꿀 수 없어 담을 그릇을 하나 둡니다.
     */
    private static final class Counter {
        private int active;
        private int sent;
    }
}
