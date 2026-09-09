package com.pawtrail.ingest.infrastructure.provider.external;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.CollectionInterruptedException;
import com.pawtrail.ingest.domain.exception.PermanentSourceErrorException;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.SourceCollector;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import com.pawtrail.ingest.domain.repository.SourceModifiedView;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.PetTourListPage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * 두 단계로 돕니다.
 *
 * <pre>
 * 1단계   목록을 훑어 대상만 모읍니다. 아무것도 저장하지 않습니다
 * 2단계   대상 하나마다 상세 셋을 부르고, 몇 건씩 모아 한 번에 저장합니다
 * </pre>
 *
 * 1단계가 저장하지 않는 것이 이 구조의 핵심입니다.
 * 목록만 담긴 행이 새로 생기지 않으므로 뒤에 받은 상세를 지우는 경로가 아예 없습니다.
 * 도중에 멈춰도 쓴 것이 없어 되돌릴 것도 없습니다.
 * 그리고 저장과 무관해지므로 목록을 받아 오는 크기를 저장 단위와 따로 잡을 수 있습니다.
 *
 * 수집기를 둘로 나누지 않은 이유가 여기 이어집니다.
 * 저장은 원본을 통째로 갈아끼우므로 목록만 담는 수집기가 따로 있으면
 * 그것이 돌 때 상세로 받아 둔 열쇠 셋을 지웁니다.
 *
 * 재개 지점을 목록의 쪽 번호가 아니라 식별자로 두는 이유도 있습니다.
 * 목록은 날마다 흔들립니다. 이틀 사이에 전체 건수가 두 번 줄어드는 것을 실제로 봤습니다.
 * 항목 하나가 빠지면 그 뒤가 모두 앞 쪽으로 밀리므로,
 * 쪽 번호로 이어받으면 앞 쪽으로 밀려간 항목을 영영 건너뜁니다.
 * 그것도 아무 신호 없이 조용히 일어납니다.
 * 식별자로 정렬해 두면 목록이 흔들려도 이어받는 자리가 정확합니다.
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
    // 표출 중인 9,691건 가운데 8,611건이 여기 해당해 빼고 나면 1,079건이 남습니다.
    //
    // 소스에 이것만 빼 달라고 요청할 방법이 없어 전량을 받아 걸러 냅니다.
    // 목록은 한 번에 여러 건을 주므로 그 비용이 크지 않습니다.
    private static final String TAX_REFUND_SHOP = "SH04";

    /**
     * 한 장소에 부르는 상세 셋입니다.
     *
     * 재개 지점을 언제나 함께 옮깁니다.
     * 하나라도 실패하면 그 장소를 통째로 버리므로 셋이 멈춘 자리는 늘 같습니다.
     * 갈라 두면 어느 하나만 앞서 나가고, 그 어긋남은 다음 날 이어받을 때에야 드러납니다.
     */
    private static final String[] DETAIL_OPERATIONS = {
            PetTourApiClient.DETAIL_PET_TOUR_OPERATION,
            PetTourApiClient.DETAIL_COMMON_OPERATION,
            PetTourApiClient.DETAIL_INTRO_OPERATION};

    private static final DateTimeFormatter SOURCE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final SourceType SOURCE = SourceType.PET_TOUR;

    private final PetTourApiClient client;
    private final PetTourDisplayBodyAssembler assembler;
    private final IngestProperties properties;

    // 증분이 상세를 부를지 판단하려면 담아 둔 수정 시각을 봐야 합니다.
    // 전량 수집일 때는 쓰지 않습니다
    private final RawDocumentRepository rawDocumentRepository;

    @Override
    public SourceType source() {
        return SourceType.PET_TOUR;
    }

    @Override
    public void collect(CollectionContext context, Consumer<List<RawDocumentDraft>> chunkSink) {
        List<Map<String, Object>> targets = collectTargets(context);
        collectDetails(context, targets, chunkSink);
    }

    /**
     * 1단계. 목록을 처음부터 끝까지 훑어 대상만 모읍니다.
     *
     * 언제나 전량을 훑습니다. 이어받는 실행에서도 그렇습니다.
     * 일부만 받으면 그다음이 어디인지 정할 수 없고,
     * 목록 호출은 하루 1,000회 가운데 열 번 남짓이라 다시 훑는 값이 쌉니다.
     * 그 대신 새로 생긴 장소가 그날 바로 잡히고 내려간 장소도 함께 드러납니다.
     *
     * 재개 지점은 남기지 않습니다.
     * 남겨 두면 아무도 읽지 않는 값이 실행 기록에 남아,
     * 다음 사람이 그것을 보고 목록도 이어받는 줄로 오해합니다.
     * 호출 수는 기록합니다. 그 값이 허용량을 얼마나 썼는지를 알려 줍니다.
     *
     * 식별자로 정렬해 돌려줍니다.
     * 문자열로 견줍니다. 숫자로 바꾸면 진행 정도가 눈에 잘 들어오지만,
     * 소스가 언젠가 숫자가 아닌 식별자를 주기 시작하면 그날 읽기가 통째로 깨집니다.
     * 진행 정도는 호출 수가 이미 알려 주므로 숫자로 둘 값어치가 크지 않습니다.
     */
    private List<Map<String, Object>> collectTargets(CollectionContext context) {
        int pageSize = properties.petTour().listPageSize();
        int pageNo = 1;
        int fetched = 0;
        List<Map<String, Object>> targets = new ArrayList<>();

        // 재개 지점을 비웁니다.
        // 앞 실행이 쪽 번호를 남겼더라도 여기서 지워집니다. 이제 쓰지 않는 값입니다.
        // 호출 수는 건드리지 않습니다. 그것은 클라이언트가 요청마다 올립니다.
        context.markCursor(null, PetTourApiClient.SYNC_LIST_OPERATION);

        while (true) {
            PetTourListPage page =
                    client.fetchSyncList(pageNo, pageSize, context::recordCall);
            fetched += page.items().size();

            for (Map<String, Object> item : page.items()) {
                if (!isTarget(item)) {
                    continue;
                }
                String contentId = string(item, "contentid");
                if (contentId == null || contentId.isBlank()) {
                    // 식별자가 없으면 저장할 수도 상세를 부를 수도 없음
                    log.warn("식별자가 없는 항목을 건너뜁니다. title={}", string(item, "title"));
                    continue;
                }
                targets.add(item);
            }

            if (isLastPage(pageNo, pageSize, page.totalCount()) || page.items().isEmpty()) {
                break;
            }
            pageNo++;
            sleep(properties.callIntervalMs());
        }

        targets.sort(Comparator.comparing(item -> string(item, "contentid")));

        int all = targets.size();
        if (context.runType() == RunType.INCREMENTAL) {
            targets = keepChanged(targets);
        }

        log.info("대상을 모았습니다. 받은 건수={} 대상={}건(전체 {}건) 목록 호출={}회",
                fetched, targets.size(), all,
                context.countOf(PetTourApiClient.SYNC_LIST_OPERATION));
        return targets;
    }

    /**
     * 바뀐 것만 남깁니다.
     *
     * 목록이 알려준 수정 시각이 우리가 담아 둔 값보다 늦으면 바뀐 것으로 봅니다.
     * 상세를 부를지 판단할 수 있는 재료가 이것뿐입니다.
     *
     * *내용 해시는 쓸 수 없습니다.
     *  그 값은 상세를 다 받은 뒤에 뜨므로 부를지 말지를 정하는 데 쓰면 순서가 맞지 않습니다.
     *  받고 나서 안 바뀐 것을 아는 것은 이미 허용량을 쓴 뒤입니다.
     *
     * 판단할 근거가 없으면 부르는 쪽으로 갑니다.
     * 담아 둔 것이 없는 새 장소이거나, 어느 한쪽 시각을 읽지 못한 경우입니다.
     * 놓치는 것보다 허용량을 조금 더 쓰는 편이 낫습니다.
     *
     * *이 판단이 전제 하나에 매달려 있습니다.
     *  상세가 바뀌면 목록의 수정 시각도 바뀐다는 것인데 아직 확인되지 않았습니다.
     *  틀리면 조건이 바뀐 것을 영영 놓치고, 그것은 이 서비스가 막으려는 헛걸음을
     *  우리가 만드는 셈입니다.
     *  그래서 수집이 끝난 뒤에 표본을 실제로 불러 대조하는 검증기를 따로 두었습니다.
     */
    private List<Map<String, Object>> keepChanged(List<Map<String, Object>> targets) {
        Map<String, LocalDateTime> stored = new HashMap<>();
        for (SourceModifiedView view : rawDocumentRepository.findSourceModified(SOURCE)) {
            // 값이 비어 있을 수 있어 스트림으로 지도를 만들지 않습니다.
            // 그렇게 하면 그 자리에서 널 참조로 끊깁니다
            stored.put(view.getSourceId(), view.getSourceModified());
        }

        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> item : targets) {
            if (isChanged(item, stored)) {
                changed.add(item);
            }
        }
        return changed;
    }

    private boolean isChanged(Map<String, Object> item, Map<String, LocalDateTime> stored) {
        String contentId = string(item, "contentid");
        if (!stored.containsKey(contentId)) {
            // 처음 보는 장소
            return true;
        }
        LocalDateTime storedAt = stored.get(contentId);
        LocalDateTime listedAt = parseModified(string(item, "modifiedtime"));
        if (storedAt == null || listedAt == null) {
            return true;
        }
        return listedAt.isAfter(storedAt);
    }

    /**
     * 2단계. 대상 하나마다 상세 셋을 부르고 몇 건씩 모아 저장합니다.
     *
     * 한 건이 끝내 실패하면 그 건만 건너뛰고 이어 갑니다.
     * 흩어진 실패는 소스 사정이라 한 건 때문에 그날 받아 둔 것을 통째로 버릴 이유가 없습니다.
     * 그러나 연달아 실패하면 소스가 멈췄거나 우리 요청이 잘못된 것이라,
     * 계속 부르면 남은 허용량을 전부 헛되이 씁니다. 그때는 스스로 접습니다.
     *
     * 허용량에 걸리면 그때까지 완성한 것을 먼저 넘기고 예외를 올려보냅니다.
     * 넘기지 않고 던지면 이미 쓴 호출이 데이터를 남기지 못한 채 사라집니다.
     */
    private void collectDetails(
            CollectionContext context,
            List<Map<String, Object>> targets,
            Consumer<List<RawDocumentDraft>> chunkSink) {

        String cursor = resumeCursor(context);
        List<RawDocumentDraft> chunk = new ArrayList<>();
        int consecutiveFailures = 0;
        int done = 0;

        for (Map<String, Object> item : targets) {
            String contentId = string(item, "contentid");

            // 앞 실행이 여기까지 저장했음
            if (cursor != null && contentId.compareTo(cursor) <= 0) {
                continue;
            }

            RawDocumentDraft draft;
            try {
                draft = fetchDetails(context, item, contentId);

            } catch (QuotaExhaustedException e) {
                flush(context, chunk, chunkSink);
                log.info("허용량에 걸려 멈춥니다. operation={} 이번 실행 처리={}건",
                        e.getOperation(), done);
                throw e;

            } catch (PermanentSourceErrorException e) {
                // 다음 항목도 반드시 같은 결과임
                // 건너뛰며 이어 가면 남은 대상 전부를 헛되이 부르고 허용량을 다 씀
                // 실행기가 이것을 예상하지 못한 오류와 같은 길로 흘려보내 FAILED 로 마감하고
                // 재개 지점을 물려주지 않음.  고치지 않은 채로 이어받으면 같은 자리에서 또 죽음
                flush(context, chunk, chunkSink);
                log.error("고쳐야 하는 오류라 멈춥니다. contentId={} 이번 실행 처리={}건",
                        contentId, done, e);
                throw e;

            } catch (RuntimeException e) {
                // 스레드가 끊긴 것은 건너뛸 실패가 아니라 그만두라는 신호임
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }

                context.recordSkip(contentId);
                consecutiveFailures++;
                log.warn("한 건을 건너뜁니다. contentId={} 연속={}건 원인={}",
                        contentId, consecutiveFailures, e.toString());

                if (consecutiveFailures >= properties.maxConsecutiveFailures()) {
                    flush(context, chunk, chunkSink);
                    throw new CollectionInterruptedException(
                            "연속 " + consecutiveFailures + "건이 실패해 멈췄습니다."
                                    + " 마지막 contentId=" + contentId);
                }
                continue;
            }

            consecutiveFailures = 0;
            chunk.add(draft);
            done++;

            if (chunk.size() >= properties.chunkSize()) {
                flush(context, chunk, chunkSink);
            }
        }

        flush(context, chunk, chunkSink);
        log.info("상세 수집을 마쳤습니다. 처리={}건 건너뜀={}건", done, context.skipped().size());
    }

    /**
     * 모아 둔 것을 넘깁니다. 넘기기 직전에 재개 지점을 옮깁니다.
     *
     * 순서가 중요합니다.
     * 받는 쪽이 저장과 진행 기록을 한 트랜잭션으로 묶으므로,
     * 저장이 실패하면 재개 지점도 함께 되돌아갑니다.
     *
     * 재개 지점은 이 청크에 담긴 것의 마지막입니다.
     * 앞서 나가면 저장되지 않은 것을 처리한 것으로 기록해 이어받을 때 건너뜁니다.
     */
    private void flush(
            CollectionContext context,
            List<RawDocumentDraft> chunk,
            Consumer<List<RawDocumentDraft>> chunkSink) {

        if (chunk.isEmpty()) {
            return;
        }
        context.markCursor(chunk.get(chunk.size() - 1).sourceId(), DETAIL_OPERATIONS);
        chunkSink.accept(new ArrayList<>(chunk));
        chunk.clear();
    }

    /**
     * 어디부터 이어받을지 정합니다.
     *
     * 셋 가운데 가장 뒤처진 자리를 씁니다.
     * 정상이라면 셋이 같은 값이지만, 어긋나 있다면 앞선 쪽을 믿는 것이 위험합니다.
     * 뒤처진 쪽을 쓰면 몇 건을 다시 받을 뿐이고 앞선 쪽을 쓰면 그 사이가 빕니다.
     * 다시 받는 것은 허용량을 조금 더 쓰는 일이고 비는 것은 데이터를 잃는 일입니다.
     *
     * 하나라도 재개 지점이 없으면 처음부터 시작합니다.
     * 그 오퍼레이션은 한 번도 저장까지 간 적이 없다는 뜻입니다.
     */
    private String resumeCursor(CollectionContext context) {
        String earliest = null;
        for (String operation : DETAIL_OPERATIONS) {
            String cursor = context.cursorOf(operation);
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            if (earliest == null || cursor.compareTo(earliest) < 0) {
                earliest = cursor;
            }
        }
        return earliest;
    }

    /**
     * 한 장소의 상세 셋을 부르고 저장할 형태로 옮깁니다.
     *
     * 셋 가운데 하나라도 실패하면 이 장소를 통째로 버립니다.
     * 반쪽짜리로 담으면 내용 해시가 완성본과 달라져,
     * 다음 전량 수집이 그것을 바뀐 것으로 보고 추출을 한 번 더 돌립니다.
     * 그 비용이 모델 호출입니다.
     *
     * 소개 정보만 타입을 함께 넘겨야 합니다.
     * 타입이 비어 있으면 부르지 않고 넘어갑니다.
     * 필수 값이 빠진 요청은 반드시 거절당하므로 부르면 허용량만 버립니다.
     *
     * 호출 수를 여기서 세지 않고 클라이언트에 맡깁니다.
     * 클라이언트가 안에서 다시 시도하므로 요청이 한 번에 여러 번 나갈 수 있는데,
     * 바깥에서 한 번만 세면 기록이 실제로 나간 요청 수보다 작아집니다.
     * 그래서 요청을 내보내는 자리에서 세도록 기록 통로를 넘깁니다.
     *
     * 재개 지점은 여기서 건드리지 않습니다.
     * 이 장소가 저장까지 갔는지는 아직 알 수 없고 그것은 넘기는 자리에서 정합니다.
     */
    private RawDocumentDraft fetchDetails(
            CollectionContext context, Map<String, Object> item, String contentId) {

        String contentTypeId = string(item, "contenttypeid");

        Map<String, Object> petTour =
                client.fetchPetTourDetail(contentId, context::recordCall);
        sleep(properties.callIntervalMs());

        Map<String, Object> common =
                client.fetchCommonDetail(contentId, context::recordCall);
        sleep(properties.callIntervalMs());

        Map<String, Object> intro = Map.of();
        if (contentTypeId == null || contentTypeId.isBlank()) {
            log.warn("타입이 없어 소개 정보를 건너뜁니다. contentId={}", contentId);
        } else {
            intro = client.fetchIntroDetail(contentId, contentTypeId, context::recordCall);
            sleep(properties.callIntervalMs());
        }

        // 원본은 응답을 열쇠 아래에 그대로 둡니다.
        //
        // 소개 정보가 등록되지 않은 콘텐츠는 빈 지도가 들어옵니다.
        // 그것도 그대로 담습니다. 열쇠를 빼면 받아 봤는데 없었던 것과
        // 아예 안 불러 본 것을 나중에 구분할 수 없습니다.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", item);
        payload.put("petTour", petTour);
        payload.put("common", common);
        payload.put("intro", intro);

        return new RawDocumentDraft(
                SourceType.PET_TOUR,
                contentId,
                payload,
                string(item, "title"),
                assembler.assemble(petTour, common, intro),
                parseModified(string(item, "modifiedtime")));
    }

    /**
     * 담을 항목인지 봅니다.
     *
     * 이 판단이 틀리면 상세를 부를 대상이 통째로 달라집니다.
     * 상세는 건당 세 번씩 호출 허용량을 쓰므로 여러 날치가 한 번에 날아갈 수 있습니다.
     */
    private boolean isTarget(Map<String, Object> item) {
        return VISIBLE.equals(string(item, "showflag"))
                && !TAX_REFUND_SHOP.equals(string(item, "lclsSystm2"));
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
