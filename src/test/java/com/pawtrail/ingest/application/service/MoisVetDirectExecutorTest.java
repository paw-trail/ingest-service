package com.pawtrail.ingest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.provider.PlaceLinkClient;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import com.pawtrail.ingest.domain.provider.dto.PlaceLinkResult;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.config.PlaceLinkProperties;
import com.pawtrail.ingest.infrastructure.provider.convert.MoisVetItemConverter;
import com.pawtrail.ingest.infrastructure.provider.file.CsvReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 파일을 읽어 바로 보내는 경로를 고정합니다.
 *
 * 이 실행기만 우리 표를 거치지 않아 확인할 자리가 다릅니다.
 * 담긴 행을 세어 볼 수 없으므로 무엇을 보냈는지를 직접 붙잡아 봅니다.
 *
 * 거르는 판정이 둘이고 그 둘이 서로 다른 이유로 건너뜁니다.
 * 영업 상태로 거르는 것은 애초에 담지 않기로 한 것이고,
 * 식별자와 이름이 없어 건너뛰는 것은 받는 쪽이 거절할 것을 미리 걸러 내는 것입니다.
 * 뒤엣것만 건너뛴 목록에 남습니다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MoisVetDirectExecutorTest {

    @Mock
    private CsvReader csvReader;

    @Mock
    private PlaceLinkClient placeLinkClient;

    @Mock
    private ChunkWriter chunkWriter;

    @Mock
    private IngestRunRepository ingestRunRepository;

    private UUID runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        IngestRun run = IngestRun.start(SourceType.MOIS_VET, RunType.DIRECT);
        when(ingestRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(placeLinkClient.send(anyList())).thenReturn(new PlaceLinkResult(0, 0, 0, List.of()));
    }

    @Test
    @DisplayName("영업 중인 행만 보낸다")
    void 영업중만_보냄() {
        givenRows(
                row("300000000000000001", "가동물병원", "영업/정상"),
                row("300000000000000002", "나동물병원", "폐업"),
                row("300000000000000003", "다동물병원", "휴업"),
                row("300000000000000004", "라동물병원", "취소/말소/만료/정지/중지"),
                row("300000000000000005", "마동물병원", "영업/정상"));

        executor(10).execute(runId);

        List<PlaceBulkItem> sent = captureSent();
        assertThat(sent).hasSize(2);
        assertThat(sent).extracting(PlaceBulkItem::name)
                .containsExactly("가동물병원", "마동물병원");
    }

    @Test
    @DisplayName("식별자가 없으면 건너뛰고 목록에 남긴다")
    void 식별자가_없으면_건너뜀() {
        givenRows(
                row("300000000000000001", "가동물병원", "영업/정상"),
                row("", "나동물병원", "영업/정상"));

        executor(10).execute(runId);

        assertThat(captureSent()).hasSize(1);
        assertThat(captureSkipped()).hasSize(1);
        assertThat(captureSkipped().get(0)).contains("나동물병원");
    }

    @Test
    @DisplayName("이름이 없으면 건너뛰고 식별자를 남긴다")
    void 이름이_없으면_건너뜀() {
        // 받는 쪽이 이름을 필수로 요구해 그대로 보내면 그 묶음이 통째로 거절됨
        givenRows(
                row("300000000000000001", "가동물병원", "영업/정상"),
                row("300000000000000002", "", "영업/정상"));

        executor(10).execute(runId);

        assertThat(captureSent()).hasSize(1);
        assertThat(captureSkipped()).containsExactly("300000000000000002");
    }

    @Test
    @DisplayName("묶음 크기에 닿을 때마다 보낸다")
    void 청크마다_보냄() {
        givenRows(
                row("300000000000000001", "가", "영업/정상"),
                row("300000000000000002", "나", "영업/정상"),
                row("300000000000000003", "다", "영업/정상"),
                row("300000000000000004", "라", "영업/정상"),
                row("300000000000000005", "마", "영업/정상"));

        executor(2).execute(runId);

        // 둘씩 두 번 보내고 남은 하나를 마지막에 보냄
        ArgumentCaptor<List<PlaceBulkItem>> captor = itemCaptor();
        verify(placeLinkClient, org.mockito.Mockito.times(3)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(2, 2, 1);
    }

    @Test
    @DisplayName("남은 것이 없으면 마지막에 보내지 않는다")
    void 딱_떨어지면_한_번_덜_보냄() {
        givenRows(
                row("300000000000000001", "가", "영업/정상"),
                row("300000000000000002", "나", "영업/정상"));

        executor(2).execute(runId);

        verify(placeLinkClient, org.mockito.Mockito.times(1)).send(anyList());
    }

    @Test
    @DisplayName("보낼 것이 하나도 없으면 아예 부르지 않는다")
    void 보낼_것이_없으면_안_부름() {
        givenRows(row("300000000000000001", "가동물병원", "폐업"));

        executor(10).execute(runId);

        verify(placeLinkClient, never()).send(anyList());
        verify(chunkWriter).complete(eq(runId), any(), any(), any());
    }

    @Test
    @DisplayName("끝까지 마치면 완료로 마감한다")
    void 마치면_완료() {
        givenRows(row("300000000000000001", "가동물병원", "영업/정상"));

        executor(10).execute(runId);

        verify(chunkWriter).complete(eq(runId), any(), any(), any());
        verify(chunkWriter, never()).fail(any(), any(), any());
    }

    @Test
    @DisplayName("읽다 실패하면 실패로 마감한다")
    void 읽다_실패하면_실패() {
        when(csvReader.read(any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("파일을 읽지 못했습니다"));

        executor(10).execute(runId);

        verify(chunkWriter).fail(eq(runId), any(), any());
        verify(chunkWriter, never()).complete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("보내다 실패하면 실패로 마감한다")
    void 보내다_실패하면_실패() {
        givenRows(row("300000000000000001", "가동물병원", "영업/정상"));
        when(placeLinkClient.send(anyList()))
                .thenThrow(new IllegalStateException("장소 서비스를 부르지 못했습니다"));

        executor(10).execute(runId);

        verify(chunkWriter).fail(eq(runId), any(), any());
        verify(chunkWriter, never()).complete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("실행 기록이 없으면 아무것도 하지 않는다")
    void 실행_기록이_없으면_아무것도_안_함() {
        UUID unknown = UUID.randomUUID();
        when(ingestRunRepository.findById(unknown)).thenReturn(Optional.empty());

        executor(10).execute(unknown);

        verify(placeLinkClient, never()).send(anyList());
        verify(chunkWriter, never()).complete(any(), any(), any(), any());
        verify(chunkWriter, never()).fail(any(), any(), any());
    }

    @Test
    @DisplayName("변환기가 없으면 실패로 마감한다")
    void 변환기가_없으면_실패() {
        MoisVetDirectExecutor executor = new MoisVetDirectExecutor(
                csvReader, placeLinkClient, chunkWriter, ingestRunRepository,
                properties(), linkProperties(10), List.of());

        executor.execute(runId);

        verify(chunkWriter).fail(eq(runId), any(), any());
        verify(csvReader, never()).read(any(), any(), anyInt(), any(), any());
    }

    // ── 도우미 ────────────────────────────────────────────────

    private MoisVetDirectExecutor executor(int chunkSize) {
        return new MoisVetDirectExecutor(
                csvReader, placeLinkClient, chunkWriter, ingestRunRepository,
                properties(), linkProperties(chunkSize),
                List.of(new MoisVetItemConverter()));
    }

    /**
     * 읽는 쪽이 행을 하나씩 넘기는 것을 흉내 냅니다.
     *
     * 실물 리더가 목록을 만들지 않고 행마다 콜백을 부르므로 시늉도 같아야 합니다.
     * 그래야 실행기가 읽는 도중에 거르고 묶는 것을 그대로 확인합니다.
     */
    @SuppressWarnings("unchecked")
    private void givenRows(Map<String, String>... rows) {
        when(csvReader.read(any(), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            Consumer<Map<String, String>> rowSink = invocation.getArgument(4);
            for (Map<String, String> row : rows) {
                rowSink.accept(row);
            }
            return rows.length;
        });
    }

    private Map<String, String> row(String id, String name, String status) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("관리번호", id);
        row.put("사업장명", name);
        row.put("영업상태명", status);
        row.put("도로명주소", "서울특별시 종로구 창경궁로 261");
        row.put("지번주소", "서울특별시 종로구 명륜2가 5-99");
        row.put("전화번호", "0212345678");
        row.put("좌표정보(X)", "199947.178659037");
        row.put("좌표정보(Y)", "453593.826987348");
        return row;
    }

    private List<PlaceBulkItem> captureSent() {
        ArgumentCaptor<List<PlaceBulkItem>> captor = itemCaptor();
        verify(placeLinkClient, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        List<PlaceBulkItem> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    @SuppressWarnings("unchecked")
    private List<String> captureSkipped() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkWriter).complete(eq(runId), any(), captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<PlaceBulkItem>> itemCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private IngestProperties properties() {
        return new IngestProperties(
                20, 0, 0, 1000, 5,
                new IngestProperties.PetTour("http://localhost", "test-only", 100, 0),
                new IngestProperties.GoCamping("http://localhost", "test-only", 100),
                new IngestProperties.Culture("build/tmp/test-culture.csv"),
                new IngestProperties.MoisVet("build/tmp/test-mois-vet.csv", "CP949"));
    }

    private PlaceLinkProperties linkProperties(int chunkSize) {
        return new PlaceLinkProperties(chunkSize, 120);
    }
}
