package com.pawtrail.ingest.infrastructure.provider.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.OperationProgress;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.GoCampingListPage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * 이 클래스가 지키는 것은 셋입니다.
 *
 * 걸러 내는 조건이 틀리면 문 닫은 곳이 목록에 남습니다.
 * 식별자를 잘못 읽으면 대소문자 하나 때문에 전량이 통째로 빠집니다.
 * 재개 지점을 남기면 아무도 읽지 않는 값이 실행 기록에 쌓입니다.
 *
 * 셋 다 오류를 내지 않고 조용히 어긋나므로 시험으로 못 박아 둡니다.
 */
@ExtendWith(MockitoExtension.class)
class GoCampingCollectorTest {

    private static final String OPERATION = GoCampingApiClient.BASED_LIST_OPERATION;

    @Mock
    private GoCampingApiClient client;

    private final GoCampingDisplayBodyAssembler assembler = new GoCampingDisplayBodyAssembler();

    private List<List<RawDocumentDraft>> chunks;

    @BeforeEach
    void setUp() {
        chunks = new ArrayList<>();
    }

    @Test
    @DisplayName("맡은 소스는 GOCAMPING 이다")
    void handlesGoCamping() {
        assertThat(collector(100, 100).source()).isEqualTo(SourceType.GOCAMPING);
    }

    @Test
    @DisplayName("운영이 아닌 것은 담지 않는다")
    void keepsOnlyOperating() {
        givenList(page(3, List.of(
                item("1", "가야영장", "운영"),
                item("2", "나야영장", "휴장"),
                item("3", "다야영장", "운영"))));

        collector(100, 100).collect(freshContext(), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::sourceId)
                .containsExactly("1", "3");
    }

    @Test
    @DisplayName("식별자는 대문자 I 인 contentId 에서 읽는다")
    void readsIdentifierFromCapitalContentId() {
        Map<String, Object> raw = item("2758", "주문진글램핑 오토캠핑장", "운영");
        givenList(page(1, List.of(raw)));

        collector(100, 100).collect(freshContext(), chunks::add);

        // 소문자 contentid 로 찾으면 값이 없는 것으로 나와 이 행이 통째로 빠짐
        assertThat(chunks.get(0).get(0).sourceId()).isEqualTo("2758");
        assertThat(chunks.get(0).get(0).displayTitle()).isEqualTo("주문진글램핑 오토캠핑장");
    }

    @Test
    @DisplayName("식별자가 없으면 그 항목만 건너뛴다")
    void skipsItemWithoutIdentifier() {
        Map<String, Object> broken = item("", "이름만 있는 곳", "운영");
        givenList(page(2, List.of(broken, item("2", "나야영장", "운영"))));

        collector(100, 100).collect(freshContext(), chunks::add);

        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::sourceId)
                .containsExactly("2");
    }

    @Test
    @DisplayName("원본을 list 열쇠 아래에 그대로 담는다")
    void keepsRawItemUnderListKey() {
        Map<String, Object> raw = item("2758", "주문진글램핑 오토캠핑장", "운영");
        raw.put("animalCmgCl", "가능");
        givenList(page(1, List.of(raw)));

        collector(100, 100).collect(freshContext(), chunks::add);

        RawDocumentDraft draft = chunks.get(0).get(0);
        // 읽는 쪽이 payload.list 를 공통 진입점으로 쓸 수 있어야 함
        // 나중에 동기화 목록을 함께 부르면 열쇠만 하나 더 붙이면 됨
        assertThat(draft.payload()).containsOnlyKeys("list");
        assertThat(draft.payload().get("list")).isEqualTo(raw);
        assertThat(draft.displayBody()).isEqualTo("[반려동물 동반] 가능");
    }

    @Test
    @DisplayName("청크가 차면 나누어 넘긴다")
    void splitsIntoChunks() {
        givenList(page(5, List.of(
                item("1", "가", "운영"), item("2", "나", "운영"), item("3", "다", "운영"),
                item("4", "라", "운영"), item("5", "마", "운영"))));

        collector(100, 2).collect(freshContext(), chunks::add);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(2);
        assertThat(chunks.get(2)).hasSize(1);
    }

    @Test
    @DisplayName("재개 지점을 남기지 않고 호출 수만 올린다")
    void leavesNoCursor() {
        givenList(page(1, List.of(item("1", "가", "운영"))));

        // 앞 실행이 남긴 값이 있어도 지워져야 함
        CollectionContext context = CollectionContext.resumeFrom(
                RunType.FULL, Map.of(OPERATION, new OperationProgress(1, "9999")));

        collector(100, 100).collect(context, chunks::add);

        // 기록해도 읽을 경로가 없음 — 저장 중에 죽으면 FAILED 이고 그것은 이어받지 않음
        assertThat(context.cursorOf(OPERATION)).isNull();
        assertThat(context.countOf(OPERATION)).isEqualTo(1);
    }

    @Test
    @DisplayName("한 쪽에 다 안 들어오면 쪽을 넘긴다")
    void pagesWhenListDoesNotFit() {
        when(client.fetchBasedList(eq(1), eq(2), any())).thenAnswer(returning(
                new GoCampingListPage(5, 1, 2,
                        List.of(item("1", "가", "운영"), item("2", "나", "운영")))));
        when(client.fetchBasedList(eq(2), eq(2), any())).thenAnswer(returning(
                new GoCampingListPage(5, 2, 2,
                        List.of(item("3", "다", "운영"), item("4", "라", "운영")))));
        when(client.fetchBasedList(eq(3), eq(2), any())).thenAnswer(returning(
                new GoCampingListPage(5, 3, 2, List.of(item("5", "마", "운영")))));

        CollectionContext context = freshContext();
        collector(2, 100).collect(context, chunks::add);

        // 소스가 자라 한 쪽에 안 들어가면 뒷부분이 조용히 잘림, 그것을 막는 자리
        verify(client, times(3)).fetchBasedList(anyInt(), anyInt(), any());
        assertThat(context.countOf(OPERATION)).isEqualTo(3);
        assertThat(chunks.get(0)).hasSize(5);
    }

    @Test
    @DisplayName("수정 시각을 날짜 형식으로 읽는다")
    void parsesDateOnlyModifiedTime() {
        givenList(page(1, List.of(item("1", "가", "운영"))));

        collector(100, 100).collect(freshContext(), chunks::add);

        // 반려동물 동반여행 쪽은 열네 자리 숫자라 소스마다 형식이 다름
        assertThat(chunks.get(0).get(0).sourceModified())
                .isEqualTo(LocalDateTime.of(2026, 9, 8, 0, 0));
    }

    @Test
    @DisplayName("수정 시각을 읽지 못하면 비워 두고 나머지는 담는다")
    void keepsGoingWhenModifiedTimeIsBroken() {
        Map<String, Object> raw = item("1", "가", "운영");
        raw.put("modifiedtime", "20260908");
        givenList(page(1, List.of(raw)));

        collector(100, 100).collect(freshContext(), chunks::add);

        assertThat(chunks.get(0).get(0).sourceModified()).isNull();
        assertThat(chunks.get(0).get(0).sourceId()).isEqualTo("1");
    }

    @Test
    @DisplayName("담을 것이 하나도 없으면 청크를 넘기지 않는다")
    void sendsNothingWhenAllFiltered() {
        givenList(page(2, List.of(
                item("1", "가", "휴장"), item("2", "나", "휴장"))));

        collector(100, 100).collect(freshContext(), chunks::add);

        assertThat(chunks).isEmpty();
    }

    private GoCampingCollector collector(int listPageSize, int chunkSize) {
        IngestProperties properties = new IngestProperties(
                chunkSize, 0, 0, 1000, 5,
                new IngestProperties.PetTour("https://example.test", "test-only", 100, 0),
                new IngestProperties.GoCamping(
                        "https://example.test", "test-only", listPageSize),
                new IngestProperties.Culture("build/tmp/test-culture.csv"));
        return new GoCampingCollector(client, assembler, properties);
    }

    private void givenList(GoCampingListPage page) {
        when(client.fetchBasedList(anyInt(), anyInt(), any())).thenAnswer(returning(page));
    }

    /**
     * 값을 돌려주기 전에 기록 통로를 부릅니다.
     *
     * 실물 클라이언트가 요청을 내보낼 때마다 그것을 부르므로 시늉도 같아야 합니다.
     * 부르지 않으면 호출 수가 0 으로 남아 이 클래스의 확인이 헛돕니다.
     */
    private Answer<GoCampingListPage> returning(GoCampingListPage page) {
        return invocation -> {
            recordAttempt(invocation);
            return page;
        };
    }

    private void recordAttempt(InvocationOnMock invocation) {
        Object last = invocation.getArgument(invocation.getArguments().length - 1);
        @SuppressWarnings("unchecked")
        Consumer<String> onAttempt = (Consumer<String>) last;
        onAttempt.accept(OPERATION);
    }

    private CollectionContext freshContext() {
        return CollectionContext.startFresh(RunType.FULL);
    }

    private GoCampingListPage page(int totalCount, List<Map<String, Object>> items) {
        return new GoCampingListPage(totalCount, 1, items.size(), items);
    }

    private Map<String, Object> item(String contentId, String facltNm, String manageSttus) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("contentId", contentId);
        item.put("facltNm", facltNm);
        item.put("manageSttus", manageSttus);
        item.put("modifiedtime", "2026-09-08");
        return item;
    }
}
