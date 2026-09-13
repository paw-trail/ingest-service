package com.pawtrail.ingest.application.service;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.provider.PlaceItemConverter;
import com.pawtrail.ingest.domain.provider.PlaceLinkClient;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import com.pawtrail.ingest.domain.provider.dto.PlaceLinkResult;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import com.pawtrail.ingest.infrastructure.config.PlaceLinkProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 받아 둔 원본을 장소 서비스로 넘깁니다.
 *
 * 수집 실행기와 나란한 자리입니다.
 * 그쪽은 바깥에서 받아 우리 표에 쌓고 이쪽은 쌓인 것을 다른 서비스에 보냅니다.
 *
 * 트랜잭션을 걸지 않습니다.
 * 이 메서드가 도는 동안 한 트랜잭션이 열려 있으면 만 칠천 건이 한 덩어리가 되고,
 * 그 사이에 장소 서비스를 부르는 시간까지 전부 들어갑니다.
 * 실제 쓰기는 기록기가 묶음마다 따로 합니다.
 *
 * 진행 위치를 남기지 않습니다.
 * 끊기면 처음부터 다시 합니다.
 * 받는 쪽이 같은 것을 여러 번 받아도 같은 결과를 내기 때문입니다.
 * 이미 붙은 소스 레코드는 기존 장소를 찾아 붙어 식별자가 다시 발급되지 않습니다.
 */
@Slf4j
@Service
public class PlaceLinkExecutor {

    private final RawDocumentRepository rawDocumentRepository;
    private final IngestRunRepository ingestRunRepository;
    private final PlaceLinkClient placeLinkClient;
    private final PlaceLinkWriter placeLinkWriter;
    private final ChunkWriter chunkWriter;
    private final ObjectMapper objectMapper;
    private final PlaceLinkProperties properties;
    private final Map<SourceType, PlaceItemConverter> converters;

    public PlaceLinkExecutor(
            RawDocumentRepository rawDocumentRepository,
            IngestRunRepository ingestRunRepository,
            PlaceLinkClient placeLinkClient,
            PlaceLinkWriter placeLinkWriter,
            ChunkWriter chunkWriter,
            ObjectMapper objectMapper,
            PlaceLinkProperties properties,
            List<PlaceItemConverter> converterList) {

        this.rawDocumentRepository = rawDocumentRepository;
        this.ingestRunRepository = ingestRunRepository;
        this.placeLinkClient = placeLinkClient;
        this.placeLinkWriter = placeLinkWriter;
        this.chunkWriter = chunkWriter;
        this.objectMapper = objectMapper;
        this.properties = properties;

        // 소스로 찾을 수 있게 담아 둠
        //
        // 변환기가 스스로 어느 소스인지 밝히므로 여기서 짝을 짓지 않아도 됨
        // 수집기를 담는 방식과 같음
        this.converters = new EnumMap<>(SourceType.class);
        for (PlaceItemConverter converter : converterList) {
            this.converters.put(converter.source(), converter);
        }
    }

    /**
     * 한 소스의 원본을 전부 넘깁니다.
     *
     * 마감하는 길이 둘뿐입니다. 끝까지 마친 것과 실패한 것입니다.
     * 수집과 달리 허용량에 걸리는 일도 스스로 접는 일도 없습니다.
     * 바깥을 부르지 않아 아낄 자원이 없고, 상대가 한 서비스라 한 번 실패하면 계속 실패합니다.
     */
    @Async
    public void execute(UUID runId) {
        IngestRun run = ingestRunRepository.findById(runId).orElse(null);
        if (run == null) {
            log.error("실행 기록을 찾지 못했습니다. runId={}", runId);
            return;
        }

        SourceType source = run.getSource();
        PlaceItemConverter converter = converters.get(source);

        // 트리거가 이미 확인하지만 그 사이에 빈 구성이 바뀔 수 있으므로 한 번 더 봄
        if (converter == null) {
            chunkWriter.fail(runId, Map.of(), "등록된 변환기가 없습니다: " + source);
            return;
        }

        log.info("장소 서비스로 넘기기를 시작합니다. runId={} source={}", runId, source);

        List<String> skipped = new ArrayList<>();
        int chunkSize = properties.chunkSize();
        int pageIndex = 0;
        int sentTotal = 0;
        int linkedTotal = 0;

        try {
            while (true) {
                Page<RawDocument> page =
                        rawDocumentRepository.findBySource(source, PageRequest.of(pageIndex, chunkSize));
                if (page.isEmpty()) {
                    break;
                }

                // 소스와 소스 식별자로 원본을 되찾는 표임
                //
                // 돌려받는 것이 그 짝과 장소 식별자라
                // 우리 표의 어느 행인지는 우리가 기억하고 있어야 함
                //
                // 소스까지 열쇠에 넣음
                // 한 실행은 한 소스만 다루나 그것은 지금 부르는 쪽 사정이고,
                // 다른 데이터셋의 같은 번호가 섞여 오면 소스 식별자만으로는 가를 수 없음
                Map<String, UUID> rawIdByKey = new LinkedHashMap<>();
                List<PlaceBulkItem> items = new ArrayList<>(page.getNumberOfElements());

                for (RawDocument document : page.getContent()) {
                    PlaceBulkItem item = toItem(converter, document);
                    if (item == null) {
                        skipped.add(document.getSourceId());
                        continue;
                    }
                    items.add(item);
                    rawIdByKey.put(
                            PlaceLinkWriter.key(document.getSource(), document.getSourceId()),
                            document.getId());
                }

                if (!items.isEmpty()) {
                    PlaceLinkResult result = placeLinkClient.send(items);
                    linkedTotal += placeLinkWriter.applyChunk(
                            runId, items.size(), rawIdByKey, result);
                    sentTotal += items.size();
                }

                if (!page.hasNext()) {
                    break;
                }
                pageIndex++;
            }

            chunkWriter.complete(runId, Map.of(), skipped, List.of());
            log.info("장소 서비스로 넘기기를 마쳤습니다. runId={} sent={} linked={} skipped={}",
                    runId, sentTotal, linkedTotal, skipped.size());

        } catch (Exception e) {
            log.error("장소 서비스로 넘기는 중 오류가 났습니다. runId={}", runId, e);
            chunkWriter.fail(runId, Map.of(), toMessage(e));
        }
    }

    /**
     * 원본 하나를 요청 한 건으로 바꿉니다.
     *
     * 읽지 못하거나 이름이 없으면 비웁니다.
     * 받는 쪽이 이름을 필수로 요구해 그대로 보내면 그 묶음이 통째로 거절됩니다.
     * 한 건 때문에 나머지 구백아흔아홉 건을 잃지 않도록 여기서 걸러 냅니다.
     */
    private PlaceBulkItem toItem(PlaceItemConverter converter, RawDocument document) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(document.getPayload(), Map.class);
        } catch (JsonProcessingException e) {
            // 우리가 넣은 값이라 닿지 않아야 하는 자리임
            // 닿는다면 저장할 때 정규화가 깨졌거나 누가 직접 고친 것임
            log.error("원본을 읽지 못했습니다. id={} source={} sourceId={}",
                    document.getId(), document.getSource(), document.getSourceId(), e);
            return null;
        }
        return converter.convert(document.getSourceId(), payload);
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
}
