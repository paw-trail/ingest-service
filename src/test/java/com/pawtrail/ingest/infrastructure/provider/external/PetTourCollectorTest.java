package com.pawtrail.ingest.infrastructure.provider.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.CollectionInterruptedException;
import com.pawtrail.ingest.domain.exception.PermanentSourceErrorException;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.domain.model.OperationProgress;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.PetTourListPage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * 이 클래스가 지키는 것은 셋입니다.
 *
 * 걸러 내는 조건이 틀리면 상세를 부를 대상이 통째로 달라집니다.
 * 재개 지점이 앞서 나가면 저장되지 않은 것을 건너뛰고, 뒤처지면 허용량을 다시 씁니다.
 * 한 건이 실패했을 때의 처리가 틀리면 그날 받아 둔 것을 통째로 잃거나
 * 반대로 남은 허용량을 헛되이 다 씁니다.
 *
 * 셋 다 오류를 내지 않고 조용히 어긋나므로 시험으로 못 박아 둡니다.
 */
@ExtendWith(MockitoExtension.class)
class PetTourCollectorTest {

    private static final String LIST = PetTourApiClient.SYNC_LIST_OPERATION;
    private static final String PET_TOUR = PetTourApiClient.DETAIL_PET_TOUR_OPERATION;
    private static final String COMMON = PetTourApiClient.DETAIL_COMMON_OPERATION;
    private static final String INTRO = PetTourApiClient.DETAIL_INTRO_OPERATION;

    @Mock
    private PetTourApiClient client;

    private final PetTourDisplayBodyAssembler assembler = new PetTourDisplayBodyAssembler();

    private List<List<RawDocumentDraft>> chunks;

    @BeforeEach
    void setUp() {
        chunks = new ArrayList<>();
    }

    @Test
    @DisplayName("맡은 소스는 PET_TOUR 다")
    void handlesPetTour() {
        assertThat(collector(10, 10).source()).isEqualTo(SourceType.PET_TOUR);
    }

    @Test
    @DisplayName("표출 중이 아닌 것과 사후면세점은 대상에서 뺀다")
    void filtersHiddenAndTaxRefundShops() {
        givenList(page(1, 10, 4, List.of(
                item("1", "1", "VE03", "여의도한강공원"),
                item("2", "0", "VE03", "감춰진 곳"),
                item("3", "1", "SH04", "가까운약국"),
                item("4", "1", "AC01", "펜션"))));
        givenDetails();

        collector(10, 10).collect(freshContext(), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::sourceId)
                .containsExactly("1", "4");
    }

    @Test
    @DisplayName("식별자 순서로 상세를 부른다")
    void sortsTargetsByContentId() {
        givenList(page(1, 10, 3, List.of(
                item("999", "1", "VE03", "다"),
                item("1000", "1", "VE03", "가"),
                item("125266", "1", "VE03", "나"))));
        givenDetails();

        collector(10, 10).collect(freshContext(), chunks::add);

        // 문자열로 견줍니다. 소스가 숫자가 아닌 식별자를 주기 시작해도 깨지지 않습니다
        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::sourceId)
                .containsExactly("1000", "125266", "999");
    }

    @Test
    @DisplayName("목록은 언제나 전량을 훑고 재개 지점을 남기지 않는다")
    void alwaysWalksWholeListAndLeavesNoCursor() {
        when(client.fetchSyncList(eq(1), eq(2), any())).thenAnswer(returning(LIST,
                page(1, 2, 5, List.of(item("1", "1", "VE03", "가"),
                        item("2", "1", "VE03", "나")))));
        when(client.fetchSyncList(eq(2), eq(2), any())).thenAnswer(returning(LIST,
                page(2, 2, 5, List.of(item("3", "1", "VE03", "다"),
                        item("4", "1", "VE03", "라")))));
        when(client.fetchSyncList(eq(3), eq(2), any())).thenAnswer(returning(LIST,
                page(3, 2, 5, List.of(item("5", "1", "VE03", "마")))));
        givenDetails();

        // 앞 실행이 쪽 번호를 남겼어도 무시하고 처음부터 훑음
        CollectionContext context = CollectionContext.resumeFrom(
                RunType.FULL, Map.of(LIST, new OperationProgress(102, "103")));

        collector(2, 10).collect(context, chunks::add);

        verify(client, times(3)).fetchSyncList(anyInt(), anyInt(), any());
        assertThat(context.countOf(LIST)).isEqualTo(3);
        assertThat(context.cursorOf(LIST)).isNull();
    }

    @Test
    @DisplayName("한 장소마다 상세 셋을 부르고 원본에 네 열쇠로 담는다")
    void callsThreeDetailsAndKeepsFourKeys() {
        Map<String, Object> raw = item("1059479", "1", "VE03", "여의도한강공원");
        givenList(page(1, 10, 1, List.of(raw)));

        when(client.fetchPetTourDetail(eq("1059479"), any()))
                .thenAnswer(returning(PET_TOUR, Map.of("acmpyTypeCd", "전구역 동반가능")));
        when(client.fetchCommonDetail(eq("1059479"), any()))
                .thenAnswer(returning(COMMON, Map.of("overview", "설명")));
        when(client.fetchIntroDetail(eq("1059479"), eq("12"), any()))
                .thenAnswer(returning(INTRO, Map.of("restdate", "연중무휴")));

        CollectionContext context = freshContext();
        collector(10, 10).collect(context, chunks::add);

        RawDocumentDraft draft = chunks.get(0).get(0);
        assertThat(draft.payload()).containsOnlyKeys("list", "petTour", "common", "intro");
        assertThat(draft.payload().get("list")).isEqualTo(raw);
        assertThat(draft.displayTitle()).isEqualTo("여의도한강공원");
        assertThat(draft.displayBody()).isEqualTo("""
                [동반 유형] 전구역 동반가능

                [개요] 설명

                [휴무일] 연중무휴""");
        assertThat(draft.sourceModified())
                .isEqualTo(LocalDateTime.of(2026, 3, 16, 10, 35, 59));

        assertThat(context.countOf(PET_TOUR)).isEqualTo(1);
        assertThat(context.countOf(COMMON)).isEqualTo(1);
        assertThat(context.countOf(INTRO)).isEqualTo(1);
    }

    @Test
    @DisplayName("타입이 없으면 소개 정보를 부르지 않는다")
    void skipsIntroWhenTypeIsMissing() {
        Map<String, Object> raw = item("1", "1", "VE03", "가");
        raw.remove("contenttypeid");
        givenList(page(1, 10, 1, List.of(raw)));
        when(client.fetchPetTourDetail(anyString(), any()))
                .thenAnswer(returning(PET_TOUR, Map.of()));
        when(client.fetchCommonDetail(anyString(), any()))
                .thenAnswer(returning(COMMON, Map.of()));

        CollectionContext context = freshContext();
        collector(10, 10).collect(context, chunks::add);

        // 필수 값이 빠진 요청은 반드시 거절당하므로 부르면 허용량만 버림
        verify(client, never()).fetchIntroDetail(anyString(), any(), any());
        assertThat(context.countOf(INTRO)).isZero();
        assertThat(chunks.get(0).get(0).payload()).containsKey("intro");
    }

    @Test
    @DisplayName("청크가 차면 넘기고 재개 지점은 그 청크의 마지막이다")
    void movesCursorToLastItemOfChunk() {
        givenList(page(1, 10, 5, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"),
                item("3", "1", "VE03", "다"), item("4", "1", "VE03", "라"),
                item("5", "1", "VE03", "마"))));
        givenDetails();

        CollectionContext context = freshContext();
        List<String> cursorsWhenChunkArrived = new ArrayList<>();
        collector(10, 2).collect(
                context, chunk -> cursorsWhenChunkArrived.add(context.cursorOf(PET_TOUR)));

        // 넘기기 직전에 옮기므로 청크가 도착한 시점에 이미 그 청크의 마지막이어야 함
        // 앞서 나가면 저장되지 않은 것을 처리한 것으로 기록해 이어받을 때 건너뜀
        assertThat(cursorsWhenChunkArrived).containsExactly("2", "4", "5");
        assertThat(context.cursorOf(COMMON)).isEqualTo("5");
        assertThat(context.cursorOf(INTRO)).isEqualTo("5");
    }

    @Test
    @DisplayName("앞 실행이 저장한 자리 다음부터 상세를 부른다")
    void resumesAfterLastSavedContentId() {
        givenList(page(1, 10, 4, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"),
                item("3", "1", "VE03", "다"), item("4", "1", "VE03", "라"))));
        givenDetails();

        Map<String, OperationProgress> previous = new HashMap<>();
        previous.put(PET_TOUR, new OperationProgress(1000, "2"));
        previous.put(COMMON, new OperationProgress(1000, "2"));
        previous.put(INTRO, new OperationProgress(999, "2"));

        collector(10, 10).collect(
                CollectionContext.resumeFrom(RunType.FULL, previous), chunks::add);

        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::sourceId)
                .containsExactly("3", "4");
        verify(client, never()).fetchPetTourDetail(eq("1"), any());
        verify(client, never()).fetchPetTourDetail(eq("2"), any());
    }

    @Test
    @DisplayName("셋의 재개 지점이 어긋나 있으면 가장 뒤처진 자리를 쓴다")
    void resumesFromTheEarliestCursor() {
        givenList(page(1, 10, 3, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"),
                item("3", "1", "VE03", "다"))));
        givenDetails();

        Map<String, OperationProgress> previous = new HashMap<>();
        previous.put(PET_TOUR, new OperationProgress(10, "2"));
        previous.put(COMMON, new OperationProgress(10, "1"));
        previous.put(INTRO, new OperationProgress(10, "2"));

        collector(10, 10).collect(
                CollectionContext.resumeFrom(RunType.FULL, previous), chunks::add);

        // 다시 받는 것은 허용량을 조금 더 쓰는 일이고 비는 것은 데이터를 잃는 일임
        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::sourceId)
                .containsExactly("2", "3");
    }

    @Test
    @DisplayName("한 건이 실패하면 그 건만 건너뛰고 이어 간다")
    void skipsFailedItemAndKeepsGoing() {
        givenList(page(1, 10, 3, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"),
                item("3", "1", "VE03", "다"))));
        givenDetails();
        when(client.fetchCommonDetail(eq("2"), any()))
                .thenAnswer(failing(COMMON, new IllegalStateException("소스 오류")));

        CollectionContext context = freshContext();
        collector(10, 10).collect(context, chunks::add);

        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::sourceId)
                .containsExactly("1", "3");
        assertThat(context.skipped()).containsExactly("2");

        // 응답을 못 받았어도 허용량은 이미 쓴 것이라 호출 수는 올라야 함
        assertThat(context.countOf(COMMON)).isEqualTo(3);
    }

    @Test
    @DisplayName("연달아 실패하면 모아 둔 것을 넘기고 스스로 멈춘다")
    void interruptsAfterConsecutiveFailures() {
        givenList(page(1, 10, 5, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"),
                item("3", "1", "VE03", "다"), item("4", "1", "VE03", "라"),
                item("5", "1", "VE03", "마"))));
        givenDetails();
        when(client.fetchPetTourDetail(eq("2"), any()))
                .thenAnswer(failing(PET_TOUR, new IllegalStateException("소스 오류")));
        when(client.fetchPetTourDetail(eq("3"), any()))
                .thenAnswer(failing(PET_TOUR, new IllegalStateException("소스 오류")));

        CollectionContext context = freshContext();

        assertThatThrownBy(() -> collector(10, 10, 2).collect(context, chunks::add))
                .isInstanceOf(CollectionInterruptedException.class)
                .hasMessageContaining("연속 2건");

        // 먼저 받은 것은 살려서 넘겨야 함, 안 그러면 이미 쓴 호출이 데이터 없이 사라짐
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).extracting(RawDocumentDraft::sourceId).containsExactly("1");
        assertThat(context.cursorOf(PET_TOUR)).isEqualTo("1");
        assertThat(context.skipped()).containsExactly("2", "3");
        verify(client, never()).fetchPetTourDetail(eq("4"), any());
    }

    @Test
    @DisplayName("고쳐야 하는 오류는 건너뛰지 않고 모아 둔 것을 넘긴 뒤 멈춘다")
    void stopsImmediatelyOnPermanentError() {
        givenList(page(1, 10, 5, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"),
                item("3", "1", "VE03", "다"), item("4", "1", "VE03", "라"),
                item("5", "1", "VE03", "마"))));
        givenDetails();
        when(client.fetchPetTourDetail(eq("3"), any())).thenAnswer(failing(PET_TOUR,
                new PermanentSourceErrorException(PET_TOUR, "30", "등록되지 않은 인증키")));

        CollectionContext context = freshContext();

        assertThatThrownBy(() -> collector(10, 2).collect(context, chunks::add))
                .isInstanceOf(PermanentSourceErrorException.class);

        // 다음 항목도 반드시 같은 결과라 건너뛰며 이어 가면 남은 대상을 헛되이 부름
        assertThat(context.skipped()).isEmpty();
        verify(client, never()).fetchPetTourDetail(eq("4"), any());

        // 먼저 받은 것은 살려서 넘겨야 함
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).extracting(RawDocumentDraft::sourceId).containsExactly("1", "2");
        assertThat(context.cursorOf(PET_TOUR)).isEqualTo("2");
    }

    @Test
    @DisplayName("허용량에 걸리면 모아 둔 것을 넘기고 예외를 올려보낸다")
    void flushesBeforeQuotaStop() {
        givenList(page(1, 10, 3, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"),
                item("3", "1", "VE03", "다"))));
        givenDetails();
        when(client.fetchIntroDetail(eq("3"), eq("12"), any()))
                .thenAnswer(failing(INTRO, new QuotaExhaustedException(INTRO)));

        CollectionContext context = freshContext();

        assertThatThrownBy(() -> collector(10, 10).collect(context, chunks::add))
                .isInstanceOf(QuotaExhaustedException.class);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).extracting(RawDocumentDraft::sourceId).containsExactly("1", "2");
        assertThat(context.cursorOf(PET_TOUR)).isEqualTo("2");
        assertThat(context.cursorOf(INTRO)).isEqualTo("2");
    }

    @Test
    @DisplayName("대상이 하나도 없으면 청크를 넘기지 않는다")
    void sendsNothingWhenNoTargets() {
        givenList(page(1, 10, 2, List.of(
                item("1", "1", "SH04", "가까운약국"),
                item("2", "1", "SH04", "가나안경원"))));

        collector(10, 10).collect(freshContext(), chunks::add);

        // 1단계는 아무것도 저장하지 않음, 목록만 담긴 행이 생기면 뒤에 받은 상세를 지움
        assertThat(chunks).isEmpty();
        verify(client, never()).fetchPetTourDetail(anyString(), any());
    }

    @Test
    @DisplayName("수정 시각을 읽지 못하면 비워 두고 나머지는 담는다")
    void keepsGoingWhenModifiedTimeIsBroken() {
        Map<String, Object> raw = item("1", "1", "VE03", "가");
        raw.put("modifiedtime", "이상한값");
        givenList(page(1, 10, 1, List.of(raw)));
        givenDetails();

        collector(10, 10).collect(freshContext(), chunks::add);

        assertThat(chunks.get(0).get(0).sourceModified()).isNull();
    }

    private PetTourCollector collector(int listPageSize, int chunkSize) {
        return collector(listPageSize, chunkSize, 5);
    }

    private PetTourCollector collector(
            int listPageSize, int chunkSize, int maxConsecutiveFailures) {

        IngestProperties properties = new IngestProperties(
                chunkSize, 0, 0, 1000, maxConsecutiveFailures,
                new IngestProperties.PetTour("https://example.test", "test-only", listPageSize),
                new IngestProperties.GoCamping("https://example.test", "test-only", 100),
                new IngestProperties.Culture("build/tmp/test-culture.csv"));
        return new PetTourCollector(client, assembler, properties);
    }

    /**
     * 목록이 한 쪽으로 끝나는 경우에 씁니다.
     */
    private void givenList(PetTourListPage page) {
        when(client.fetchSyncList(anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            recordAttempt(invocation, LIST);
            return page;
        });
    }

    /**
     * 상세 셋이 다 빈 응답으로 오는 상황을 깔아 둡니다.
     *
     * 소개 정보가 등록되지 않은 콘텐츠가 실제로 있어 빈 지도가 정상 경로입니다.
     * 개별 시험이 필요한 항목만 위에서 다시 지정합니다.
     */
    private void givenDetails() {
        lenient().when(client.fetchPetTourDetail(anyString(), any()))
                .thenAnswer(returning(PET_TOUR, Map.of()));
        lenient().when(client.fetchCommonDetail(anyString(), any()))
                .thenAnswer(returning(COMMON, Map.of()));
        lenient().when(client.fetchIntroDetail(anyString(), any(), any()))
                .thenAnswer(returning(INTRO, Map.of()));
    }

    /**
     * 값을 돌려주기 전에 기록 통로를 부릅니다.
     *
     * 실물 클라이언트가 요청을 내보낼 때마다 그것을 부르므로 시늉도 같아야 합니다.
     * 부르지 않으면 호출 수가 0 으로 남아 이 클래스의 확인이 헛돕니다.
     */
    private <T> Answer<T> returning(String operation, T result) {
        return invocation -> {
            recordAttempt(invocation, operation);
            return result;
        };
    }

    /**
     * 기록 통로를 부른 뒤 실패시킵니다.
     *
     * 응답을 못 받았어도 요청은 이미 나간 것이라 허용량을 씁니다.
     * 실물이 그렇게 세므로 시늉도 같아야 합니다.
     */
    private <T> Answer<T> failing(String operation, RuntimeException error) {
        return invocation -> {
            recordAttempt(invocation, operation);
            throw error;
        };
    }

    private void recordAttempt(
            org.mockito.invocation.InvocationOnMock invocation, String operation) {

        Object last = invocation.getArgument(invocation.getArguments().length - 1);
        @SuppressWarnings("unchecked")
        Consumer<String> onAttempt = (Consumer<String>) last;
        onAttempt.accept(operation);
    }

    private CollectionContext freshContext() {
        return CollectionContext.startFresh(RunType.FULL);
    }

    private PetTourListPage page(
            int pageNo, int numOfRows, int totalCount, List<Map<String, Object>> items) {

        return new PetTourListPage(totalCount, pageNo, numOfRows, items);
    }

    private Map<String, Object> item(
            String contentId, String showFlag, String category, String title) {

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("contentid", contentId);
        item.put("showflag", showFlag);
        item.put("contenttypeid", "12");
        item.put("lclsSystm2", category);
        item.put("title", title);
        item.put("modifiedtime", "20260316103559");
        return item;
    }
}
