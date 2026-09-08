package com.pawtrail.ingest.infrastructure.provider.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.OperationProgress;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.dto.PetTourListPage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 걸러 내는 조건이 틀리면 다음 이슈에서 상세를 부를 대상이 통째로 달라집니다.
 * 상세는 건당 한 번씩 호출 허용량을 쓰므로 여드레치가 한 번에 날아갈 수 있어
 * 조건과 쪽 넘김을 테스트로 못 박아 둡니다.
 *
 * 쪽 크기를 테스트마다 다르게 줍니다.
 * 한 쪽이 한 청크라 크기가 곧 청크 경계이고, 그 경계가 이 클래스가 확인하려는 것입니다.
 */
@ExtendWith(MockitoExtension.class)
class PetTourCollectorTest {

    private static final String OPERATION = PetTourApiClient.SYNC_LIST_OPERATION;

    @Mock
    private PetTourApiClient client;

    private List<List<RawDocumentDraft>> chunks;

    @BeforeEach
    void setUp() {
        chunks = new ArrayList<>();
    }

    @Test
    @DisplayName("맡은 소스는 PET_TOUR 다")
    void handlesPetTour() {
        assertThat(collectorWith(2).source()).isEqualTo(SourceType.PET_TOUR);
    }

    @Test
    @DisplayName("표출 중이 아닌 것과 사후면세점은 걸러 낸다")
    void filtersHiddenAndTaxRefundShops() {
        when(client.fetchSyncList(anyInt(), anyInt())).thenReturn(page(1, 4, 4, List.of(
                item("1", "1", "VE03", "여의도한강공원"),
                item("2", "0", "VE03", "감춰진 곳"),
                item("3", "1", "SH04", "가까운약국"),
                item("4", "1", "AC01", "펜션"))));

        collectorWith(4).collect(freshContext(), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::sourceId)
                .containsExactly("1", "4");
    }

    @Test
    @DisplayName("전부 걸러져도 빈 청크를 넘겨 진행 기록이 남는다")
    void sendsEmptyChunkToKeepProgress() {
        when(client.fetchSyncList(anyInt(), anyInt())).thenReturn(page(1, 2, 2, List.of(
                item("1", "1", "SH04", "가까운약국"),
                item("2", "1", "SH04", "가나안경원"))));

        collectorWith(2).collect(freshContext(), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEmpty();
    }

    @Test
    @DisplayName("전체 건수를 다 받을 때까지 쪽을 넘긴다")
    void pagesUntilTotalCount() {
        when(client.fetchSyncList(1, 2)).thenReturn(page(1, 2, 5, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"))));
        when(client.fetchSyncList(2, 2)).thenReturn(page(2, 2, 5, List.of(
                item("3", "1", "VE03", "다"), item("4", "1", "VE03", "라"))));
        when(client.fetchSyncList(3, 2)).thenReturn(page(3, 2, 5, List.of(
                item("5", "1", "VE03", "마"))));

        collectorWith(2).collect(freshContext(), chunks::add);

        verify(client, times(3)).fetchSyncList(anyInt(), anyInt());
        assertThat(chunks).hasSize(3);
    }

    @Test
    @DisplayName("한 쪽이 한 청크이고 넘기기 직전에 진행이 기록된다")
    void recordsOneCallPerChunk() {
        when(client.fetchSyncList(1, 2)).thenReturn(page(1, 2, 3, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"))));
        when(client.fetchSyncList(2, 2)).thenReturn(page(2, 2, 3, List.of(
                item("3", "1", "VE03", "다"))));

        CollectionContext context = freshContext();
        List<Integer> countsWhenChunkArrived = new ArrayList<>();
        collectorWith(2).collect(
                context, chunk -> countsWhenChunkArrived.add(context.countOf(OPERATION)));

        // 넘기기 직전에 기록하므로 첫 청크에서 이미 1 이어야 함
        // 순서가 뒤집히면 저장이 롤백돼도 진행만 남아 이어받을 때 그 쪽을 건너뜀
        assertThat(countsWhenChunkArrived).containsExactly(1, 2);
        assertThat(context.cursorOf(OPERATION)).isEqualTo("3");
    }

    @Test
    @DisplayName("앞 실행이 멈춘 자리부터 이어받는다")
    void resumesFromPreviousCursor() {
        when(client.fetchSyncList(7, 2)).thenReturn(page(7, 2, 14, List.of(
                item("1", "1", "VE03", "가"), item("2", "1", "VE03", "나"))));

        CollectionContext context = CollectionContext.resumeFrom(
                RunType.INCREMENTAL, Map.of(OPERATION, new OperationProgress(1000, "7")));

        collectorWith(2).collect(context, chunks::add);

        verify(client, times(1)).fetchSyncList(7, 2);
    }

    @Test
    @DisplayName("원본에 목록 항목을 열쇠 아래 그대로 담고 표시용 본문은 비운다")
    void keepsRawItemUnderListKey() {
        Map<String, Object> raw = item("1059479", "1", "VE03", "여의도한강공원");
        when(client.fetchSyncList(anyInt(), anyInt())).thenReturn(page(1, 2, 1, List.of(raw)));

        collectorWith(2).collect(freshContext(), chunks::add);

        RawDocumentDraft draft = chunks.get(0).get(0);
        assertThat(draft.payload()).containsOnlyKeys("list");
        assertThat(draft.payload().get("list")).isEqualTo(raw);
        assertThat(draft.displayTitle()).isEqualTo("여의도한강공원");
        assertThat(draft.displayBody()).isNull();
        assertThat(draft.sourceModified())
                .isEqualTo(LocalDateTime.of(2026, 3, 16, 10, 35, 59));
    }

    @Test
    @DisplayName("수정 시각을 읽지 못하면 비워 두고 나머지는 담는다")
    void keepsGoingWhenModifiedTimeIsBroken() {
        Map<String, Object> raw = item("1", "1", "VE03", "가");
        raw.put("modifiedtime", "이상한값");
        when(client.fetchSyncList(anyInt(), anyInt())).thenReturn(page(1, 2, 1, List.of(raw)));

        collectorWith(2).collect(freshContext(), chunks::add);

        assertThat(chunks.get(0).get(0).sourceModified()).isNull();
    }

    /**
     * 쪽 크기가 곧 청크 크기입니다. 설정에서 한 값으로 두 곳을 함께 정합니다.
     */
    private PetTourCollector collectorWith(int chunkSize) {
        IngestProperties properties = new IngestProperties(
                chunkSize, 0, 0, 1000,
                new IngestProperties.PetTour("https://example.test", "test-only"));
        return new PetTourCollector(client, properties);
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
        item.put("lclsSystm2", category);
        item.put("title", title);
        item.put("modifiedtime", "20260316103559");
        return item;
    }
}
