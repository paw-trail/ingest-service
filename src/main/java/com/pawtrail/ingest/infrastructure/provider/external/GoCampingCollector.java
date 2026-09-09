package com.pawtrail.ingest.infrastructure.provider.external;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.SourceCollector;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.GoCampingListPage;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 한국관광공사 고캠핑 정보를 수집합니다.
 *
 * 반려동물 동반여행 쪽과 구조가 크게 다릅니다.
 * 저쪽은 목록을 훑어 대상을 모은 뒤 대상마다 상세를 세 번씩 불러야 해서
 * 단계를 둘로 나누고 재개 지점을 두었습니다.
 * 이쪽은 상세 조회 오퍼레이션이 아예 없습니다.
 * 여든한 개 필드가 목록 응답에 전부 들어 있어 한 번 부르면 그날 필요한 것이 다 옵니다.
 *
 * 그래서 하는 일이 단순합니다.
 *
 * <pre>
 * 목록을 받아 ─→ 운영 중인 것만 남겨 ─→ 몇 건씩 모아 저장
 * </pre>
 *
 * 재개 지점을 남기지 않습니다.
 * 저장 도중에 죽으면 실행이 실패로 마감되는데, 실패한 실행의 재개 지점은
 * 다음 실행이 물려받지 않습니다. 남겨 두어도 읽을 사람이 없습니다.
 * 허용량으로 멈추는 일도 없습니다. 하루 천 번 가운데 한 번을 씁니다.
 * 다시 받는 비용도 호출 한 번뿐이고, 이미 담긴 것은 내용이 같아 그대로 넘어갑니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoCampingCollector implements SourceCollector {

    /**
     * 담을 상태입니다. 이 값이 아니면 문을 닫았거나 쉬는 중입니다.
     *
     * 폐업한 곳은 목록에 아예 오지 않습니다.
     * 그래서 걸러 낼 것은 휴장뿐이며 백이십 건 남짓입니다.
     */
    private static final String OPERATING = "운영";

    /**
     * 소스가 알려 주는 수정 시각의 형식입니다.
     *
     * 날짜만 옵니다. 반려동물 동반여행 쪽은 열네 자리 숫자라 서로 다릅니다.
     */
    private static final DateTimeFormatter SOURCE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final GoCampingApiClient client;
    private final GoCampingDisplayBodyAssembler assembler;
    private final IngestProperties properties;

    @Override
    public SourceType source() {
        return SourceType.GOCAMPING;
    }

    @Override
    public void collect(CollectionContext context, Consumer<List<RawDocumentDraft>> chunkSink) {
        // 재개 지점을 비웁니다.
        // 앞 실행이 남긴 값이 있더라도 여기서 지워집니다. 이제 쓰지 않는 값입니다.
        // 호출 수는 건드리지 않습니다. 그것은 클라이언트가 요청마다 올립니다.
        context.markCursor(null, GoCampingApiClient.BASED_LIST_OPERATION);

        List<Map<String, Object>> items = fetchAll(context);

        List<RawDocumentDraft> chunk = new ArrayList<>();
        int kept = 0;

        for (Map<String, Object> item : items) {
            if (!OPERATING.equals(string(item, "manageSttus"))) {
                continue;
            }

            // 대문자 I 입니다.
            // 반려동물 동반여행 쪽은 contentid 로 전부 소문자라 서로 다릅니다.
            // 소문자로 찾으면 값이 없는 것으로 나오고 그 행이 통째로 빠집니다.
            String contentId = string(item, "contentId");
            if (contentId == null || contentId.isBlank()) {
                log.warn("식별자가 없는 항목을 건너뜁니다. facltNm={}", string(item, "facltNm"));
                continue;
            }

            chunk.add(toDraft(item, contentId));
            kept++;

            if (chunk.size() >= properties.chunkSize()) {
                flush(chunk, chunkSink);
            }
        }

        flush(chunk, chunkSink);
        log.info("고캠핑 수집을 마쳤습니다. 받은 건수={} 담은 건수={}", items.size(), kept);
    }

    /**
     * 목록을 끝까지 받아 옵니다.
     *
     * 쪽 크기를 크게 잡아 대개 한 번에 끝납니다.
     * 그래도 쪽을 넘길 수 있게 두는 이유가 있습니다.
     * 소스가 자라 한 쪽에 안 들어가는 날이 오면 뒷부분이 조용히 잘리는데,
     * 그때 우리는 그것을 알아챌 방법이 없습니다.
     * 지금은 한 번에 끝나므로 이 반복문이 도는 비용이 없습니다.
     */
    private List<Map<String, Object>> fetchAll(CollectionContext context) {
        int pageSize = properties.goCamping().listPageSize();
        int pageNo = 1;
        List<Map<String, Object>> items = new ArrayList<>();

        while (true) {
            GoCampingListPage page =
                    client.fetchBasedList(pageNo, pageSize, context::recordCall);
            items.addAll(page.items());

            if (isLastPage(pageNo, pageSize, page.totalCount()) || page.items().isEmpty()) {
                log.info("목록을 받았습니다. 전체={} 받은 건수={} 호출={}회",
                        page.totalCount(), items.size(), pageNo);
                return items;
            }
            pageNo++;
            sleep(properties.callIntervalMs());
        }
    }

    /**
     * 모아 둔 것을 넘깁니다.
     *
     * 재개 지점을 옮기지 않습니다. 이 소스는 그것을 쓰지 않습니다.
     */
    private void flush(List<RawDocumentDraft> chunk, Consumer<List<RawDocumentDraft>> chunkSink) {
        if (chunk.isEmpty()) {
            return;
        }
        chunkSink.accept(new ArrayList<>(chunk));
        chunk.clear();
    }

    /**
     * 저장할 형태로 옮깁니다.
     *
     * 원본은 항목을 열쇠 아래에 그대로 둡니다.
     * 응답이 하나뿐이라 평평하게 담을 수도 있지만 두 가지가 걸립니다.
     *
     * 읽는 쪽이 소스마다 갈립니다.
     * 추출이 조건 문구를 찾을 때 반려동물 동반여행은 payload 안의 열쇠를 하나 더 거치는데
     * 이쪽만 평평하면 진입점을 소스마다 기억해야 합니다.
     *
     * 그리고 나중에 동기화 목록을 함께 부르게 되면 두 번째 응답을 넣을 자리가 없습니다.
     * 그때 구조를 바꾸면 이미 담긴 것을 전부 다시 써야 합니다.
     * 열쇠를 하나 두는 비용은 지금 없습니다.
     */
    private RawDocumentDraft toDraft(Map<String, Object> item, String contentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", item);

        return new RawDocumentDraft(
                SourceType.GOCAMPING,
                contentId,
                payload,
                string(item, "facltNm"),
                assembler.assemble(item),
                parseModified(string(item, "modifiedtime")));
    }

    /**
     * 소스가 알려 준 수정 시각을 읽습니다.
     *
     * 날짜만 오므로 그날 시작 시각으로 둡니다.
     * 이 값은 나중에 상세를 다시 부를지 판단하는 자리에 쓰이는데,
     * 이 소스는 상세 호출이 없어 그 판단이 필요 없습니다.
     * 그래도 담습니다. 소스가 언제 고쳤는지는 사람이 보는 값이기도 합니다.
     */
    private LocalDateTime parseModified(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip(), SOURCE_DATE).atStartOfDay();
        } catch (RuntimeException e) {
            log.warn("수정 시각을 읽지 못했습니다. value={}", raw);
            return null;
        }
    }

    /**
     * 마지막 쪽인지 봅니다.
     *
     * 전체 건수가 0 이면 한 바퀴만 돌고 끝냅니다.
     * 소스가 그 값을 안 줄 때 끝없이 도는 것을 막습니다.
     */
    private boolean isLastPage(int pageNo, int pageSize, int totalCount) {
        return totalCount <= 0 || (long) pageNo * pageSize >= totalCount;
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
            throw new IllegalStateException("수집이 중단됐습니다", e);
        }
    }
}
