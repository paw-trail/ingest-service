package com.pawtrail.ingest.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.ingest.application.support.JsonNormalizer;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.PetTourApiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 증분 수집이 서 있는 전제를 표본으로 검증합니다.
 *
 * 증분은 이 문장 하나에 매달려 있습니다.
 * 상세가 바뀌면 목록의 수정 시각도 바뀐다는 것인데 아직 확인되지 않았습니다.
 *
 * 틀리면 조건이 바뀐 것을 영영 놓칩니다.
 * 어떤 장소가 동반 가능에서 불가능으로 바뀌었는데 목록의 시각이 그대로면
 * 우리는 계속 옛 조건을 보여줍니다.
 * 이 서비스가 막으려는 헛걸음을 우리가 만드는 셈입니다.
 *
 * 지금은 확인할 수 없습니다. 소스가 실제로 무언가를 고쳐야 관찰되는데
 * 그 시점을 우리가 정할 수 없습니다.
 * 그래서 확인을 기다리지 않고, 전제가 틀렸을 때 그것이 드러나는 길을 만들어 둡니다.
 *
 * *일시적인 장치입니다.
 *  몇 주 돌려 한 번도 걸리지 않으면 전제가 맞는 것이니 줄이거나 없앱니다.
 *  걸리면 판단 기준을 다시 정합니다. 어느 쪽이든 지금 모르는 것을 알게 됩니다.
 *
 * *수집기 안에 두지 않았습니다.
 *  수집기는 받아서 넘기는 일만 합니다. 저장된 것을 읽지 않습니다.
 *  거기에 저장소와 해시 계산을 넣으면 그 약속이 흐려지고,
 *  다른 소스가 그 인터페이스를 그대로 쓰고 있어 영향이 번집니다.
 *
 *  대신 이 클래스가 소스별 클라이언트를 직접 봅니다.
 *  실행기가 수집기를 찾아 부르는 것과 같은 모양이며, 전제를 검증하는 일 자체가
 *  그 소스에만 있는 일이라 소스 이름이 드러나는 편이 오히려 읽기 낫습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PetTourSampleVerifier {

    private static final SourceType SOURCE = SourceType.PET_TOUR;

    private final PetTourApiClient client;
    private final RawDocumentRepository rawDocumentRepository;
    private final JsonNormalizer jsonNormalizer;
    private final ObjectMapper objectMapper;
    private final IngestProperties properties;

    /**
     * 표본을 실제로 불러 담아 둔 것과 견줍니다.
     *
     * 전량 수집일 때는 아무것도 하지 않습니다. 그때는 어차피 다 받으므로 검증할 것이 없습니다.
     *
     * *실행 결과를 바꾸지 않습니다.
     *  이것은 진단이지 수집이 아닙니다.
     *  표본을 부르다 실패해도 그 실행은 정상으로 마감되어야 합니다.
     *  허용량에 걸리는 것도 마찬가지입니다. 수집은 이미 끝났습니다.
     *  그래서 무엇이 나든 여기서 삼키고 그때까지 알아낸 것만 돌려줍니다.
     *
     *  표본을 뽑는 조회까지 그 안에 있습니다.
     *  그것이 밖으로 나가면 실행이 실패로 마감되고, 실패한 실행은 재개 지점을
     *  물려주지 않아 다음 실행이 처음부터 다시 훑습니다.
     *
     * 부르는 호출은 진행 기록에 셉니다.
     * 같은 오퍼레이션을 쓰므로 세지 않으면 허용량 계산이 어긋납니다.
     * 다만 재개 지점은 옮기지 않습니다. 저장하지 않기 때문입니다.
     *
     * @return 사람이 읽을 문구. 이상이 없으면 빈 목록
     */
    public List<String> verify(CollectionContext context) {
        if (context.runType() != RunType.INCREMENTAL) {
            return List.of();
        }
        int size = properties.petTour().sampleSize();
        if (size <= 0) {
            return List.of();
        }

        List<String> changed = new ArrayList<>();
        int checked = 0;

        // 조회부터 감쌉니다.
        //
        // 표본을 뽑는 조회도 실패할 수 있습니다.
        // 연결이 끊기거나 조회가 오래 걸리면 그 자리에서 예외가 납니다.
        //
        // 그것이 밖으로 나가면 실행기가 예상하지 못한 오류로 보고 실패로 마감합니다.
        // 수집은 이미 끝까지 마쳤는데 실행이 실패로 남고,
        // 실패한 실행은 재개 지점을 물려주지 않아 다음 실행이 처음부터 다시 훑습니다.
        // 진단하려다 수집을 망치는 셈이라 이 메서드는 무엇도 밖으로 내보내지 않습니다.
        try {
            List<RawDocument> sample = rawDocumentRepository.findOldestFetched(SOURCE, size);

            for (RawDocument document : sample) {
                if (hasChanged(document, context)) {
                    changed.add(document.getSourceId());
                }
                checked++;
                sleep(properties.callIntervalMs());
            }

        } catch (Exception e) {
            // 실행 중 오류를 뜻하는 것만 잡습니다.
            // 메모리 부족 같은 것은 삼키면 안 되므로 여기서 걸리지 않습니다.
            //
            // 그때까지 알아낸 것은 그대로 씁니다.
            // 열 건을 보고 끊겼어도 그 안에 어긋난 것이 있었다면 그것은 알려야 합니다
            log.warn("표본 검증을 도중에 멈춥니다. 확인={}건 이유={}", checked, e.toString());
        }

        if (changed.isEmpty()) {
            log.info("표본 검증을 마쳤습니다. 확인={}건 어긋남 없음", checked);
            return List.of();
        }

        // 전제가 틀렸다는 증거입니다.
        // 목록의 시각은 그대로인데 상세가 달라진 장소가 있다는 뜻이며,
        // 증분이 이런 변경을 놓치고 있었다는 말이 됩니다
        log.error("증분 전제가 어긋났습니다. 확인={}건 어긋남={}건 sourceIds={}",
                checked, changed.size(), changed);
        return List.of("표본 검증: " + checked + "건 중 " + changed.size()
                + "건이 목록 시각은 그대로인데 상세가 달라짐 (" + String.join(", ", changed) + ")");
    }

    /**
     * 상세 셋을 다시 받아 내용 해시를 견줍니다.
     *
     * 담아 둔 것과 같은 방식으로 원본을 묶고 같은 규칙으로 해시를 떠야
     * 견주는 것이 뜻을 갖습니다.
     * 목록 응답은 다시 받지 않고 담아 둔 것을 그대로 씁니다.
     * 목록은 이미 이번 실행에서 훑었고 그 값이 같다는 것이 판단의 전제였습니다.
     */
    private boolean hasChanged(RawDocument document, CollectionContext context) {
        Map<String, Object> stored = readPayload(document);
        Map<String, Object> list = asMap(stored.get("list"));
        String contentId = document.getSourceId();
        String contentTypeId = string(list, "contenttypeid");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", list);
        payload.put("petTour", client.fetchPetTourDetail(contentId, context::recordCall));
        sleep(properties.callIntervalMs());
        payload.put("common", client.fetchCommonDetail(contentId, context::recordCall));
        sleep(properties.callIntervalMs());
        payload.put("intro", contentTypeId == null || contentTypeId.isBlank()
                ? Map.of()
                : client.fetchIntroDetail(contentId, contentTypeId, context::recordCall));

        String hash = jsonNormalizer.hash(jsonNormalizer.normalize(payload));
        return !hash.equals(document.getContentHash());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(RawDocument document) {
        try {
            return objectMapper.readValue(document.getPayload(), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("담아 둔 원본을 읽지 못했습니다", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String string(Map<String, Object> item, String key) {
        Object value = item.get(key);
        return value == null ? null : String.valueOf(value).strip();
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("표본 검증이 중단됐습니다", e);
        }
    }
}
