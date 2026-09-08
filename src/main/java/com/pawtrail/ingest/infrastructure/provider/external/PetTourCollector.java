package com.pawtrail.ingest.infrastructure.provider.external;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.SourceCollector;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.PetTourListPage;
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
 * 한국관광공사 반려동물 동반여행 정보를 수집합니다.
 *
 * 지금은 목록까지입니다.
 * 동반 조건은 상세에만 있고 그것은 다음 이슈에서 이 클래스에 더합니다.
 * 수집기를 따로 만들지 않고 여기에 더하는 이유가 있습니다.
 * 저장은 원본을 통째로 갈아끼우므로 목록만 담는 수집기가 따로 있으면
 * 나중에 그것이 돌 때 상세로 받아 둔 것을 지웁니다.
 *
 * 그래서 지금 담기는 원본은 일부러 불완전합니다.
 * 목록 항목만 들어 있고 표시용 본문이 비어 있습니다.
 * 다음 이슈가 돌면 내용이 달라져 전부 갱신됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PetTourCollector implements SourceCollector {

    // 표출 중인 콘텐츠입니다. 이 값이 아니면 화면에서 내려간 것입니다
    private static final String VISIBLE = "1";

    // 사후면세점입니다.
    //
    // 외국인에게 세금을 나중에 돌려주는 일반 매장이라 전국에 수만 곳이고
    // 실제로 약국과 안경원이 대부분입니다. 관광 목적지가 아닙니다.
    // 표출 중인 9,691건 가운데 8,611건이 여기 해당해 빼고 나면 1,080건이 남습니다.
    //
    // 소스에 이것만 빼 달라고 요청할 방법이 없어 전량을 받아 걸러 냅니다.
    // 목록은 한 번에 여러 건을 주므로 그 비용이 크지 않습니다.
    private static final String TAX_REFUND_SHOP = "SH04";

    private static final DateTimeFormatter SOURCE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PetTourApiClient client;
    private final IngestProperties properties;

    @Override
    public SourceType source() {
        return SourceType.PET_TOUR;
    }

    /**
     * 목록을 쪽 단위로 받아 걸러 낸 뒤 그대로 넘깁니다.
     *
     * 한 쪽이 한 청크입니다.
     * 진행 기록은 한 쪽을 받을 때마다 남고 저장은 청크마다 일어나는데,
     * 둘의 크기가 어긋나면 한 쪽이 여러 청크에 걸칩니다.
     * 그러면 아직 저장되지 않은 항목까지 처리한 것으로 기록되어
     * 이어받을 때 그 자리를 건너뜁니다.
     *
     * 진행을 먼저 기록하고 그다음에 넘깁니다.
     * 넘기는 쪽이 저장과 진행 기록을 한 트랜잭션으로 묶으므로,
     * 저장이 실패하면 진행 기록도 함께 되돌아갑니다.
     */
    @Override
    public void collect(CollectionContext context, Consumer<List<RawDocumentDraft>> chunkSink) {
        int pageSize = properties.chunkSize();
        int pageNo = resumePage(context);
        int fetched = 0;
        int kept = 0;

        while (true) {
            PetTourListPage page = client.fetchSyncList(pageNo, pageSize);

            List<RawDocumentDraft> drafts = new ArrayList<>();
            for (Map<String, Object> item : page.items()) {
                if (isTarget(item)) {
                    drafts.add(toDraft(item));
                }
            }

            fetched += page.items().size();
            kept += drafts.size();

            context.recordCall(PetTourApiClient.SYNC_LIST_OPERATION, String.valueOf(pageNo + 1));
            chunkSink.accept(drafts);

            if (isLastPage(pageNo, pageSize, page.totalCount()) || page.items().isEmpty()) {
                log.info("목록 수집을 마쳤습니다. 받은 건수={} 남긴 건수={} 전체={}",
                        fetched, kept, page.totalCount());
                return;
            }

            pageNo++;
            sleep(properties.callIntervalMs());
        }
    }

    /**
     * 어디부터 받을지 정합니다.
     *
     * 앞 실행이 호출 허용량에 걸려 멈췄으면 그 자리부터 시작합니다.
     * 처음이면 첫 쪽입니다.
     */
    private int resumePage(CollectionContext context) {
        String cursor = context.cursorOf(PetTourApiClient.SYNC_LIST_OPERATION);
        if (cursor == null || cursor.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            // 우리가 넣은 값이라 닿지 않아야 하지만, 닿으면 처음부터 다시 받는 편이 안전함
            log.warn("재개 지점을 읽지 못해 처음부터 시작합니다. cursor={}", cursor);
            return 1;
        }
    }

    /**
     * 담을 항목인지 봅니다.
     *
     * 이 판단이 틀리면 다음 이슈에서 상세를 부를 대상이 통째로 달라집니다.
     * 상세는 건당 한 번씩 호출 허용량을 쓰므로 여드레치가 한 번에 날아갈 수 있습니다.
     */
    private boolean isTarget(Map<String, Object> item) {
        return VISIBLE.equals(string(item, "showflag"))
                && !TAX_REFUND_SHOP.equals(string(item, "lclsSystm2"));
    }

    /**
     * 저장할 형태로 옮깁니다.
     *
     * 원본은 목록 항목을 열쇠 아래에 그대로 둡니다.
     * 다음 이슈가 상세를 받아 오면 같은 자리에 열쇠를 더합니다.
     *
     * 표시용 본문은 비워 둡니다.
     * 사람이 읽을 자연어가 조건 문구인데 그것이 상세에만 있습니다.
     * 목록에 있는 것은 좌표와 분류 코드처럼 기계가 쓰는 값이라 원문에 담을 것이 아닙니다.
     */
    private RawDocumentDraft toDraft(Map<String, Object> item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", item);

        return new RawDocumentDraft(
                SourceType.PET_TOUR,
                string(item, "contentid"),
                payload,
                string(item, "title"),
                null,
                parseModified(string(item, "modifiedtime")));
    }

    /**
     * 소스가 알려 준 수정 시각을 읽습니다.
     *
     * 열네 자리 숫자로 옵니다. 고캠핑은 날짜만 주고 문화정보원은 또 다른 형태라
     * 소스마다 읽는 방법이 다릅니다.
     */
    private LocalDateTime parseModified(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, SOURCE_TIME);
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
        return value == null ? null : String.valueOf(value);
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
