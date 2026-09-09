package com.pawtrail.ingest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.ingest.application.dto.output.PendingDocumentsOutput;
import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * 꺼내 주는 모양이 어긋나도 오류가 나지 않습니다.
 *
 * 원본을 문자열로 돌려주면 받는 쪽이 두 번 파싱해야 하고,
 * 개수를 그대로 받으면 응답이 수십 메가바이트가 됩니다.
 * 둘 다 부르는 쪽에서야 드러나므로 여기서 못 박아 둡니다.
 */
@ExtendWith(MockitoExtension.class)
class IngestQueryServiceTest {

    @Mock
    private RawDocumentRepository rawDocumentRepository;

    @Mock
    private IngestRunRepository ingestRunRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("원본을 문자열이 아니라 객체로 풀어 담는다")
    void unwrapsPayloadIntoObject() {
        givenDocuments(document("""
                {"list":{"contentid":"1019041","title":"와룡공원"}}"""));

        PendingDocumentsOutput output = service().findDocuments(DocumentStatus.PENDING, 100);

        // 문자열로 주면 응답의 JSON 안에 JSON 문자열이 박혀 받는 쪽이 두 번 파싱해야 함
        assertThat(output.documents()).hasSize(1);
        assertThat(output.documents().get(0).payload())
                .containsKey("list")
                .extracting("list")
                .isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("그 상태의 전체 건수를 함께 돌려준다")
    void includesTotalCount() {
        givenDocuments(document("{}"));
        when(rawDocumentRepository.countByStatus(DocumentStatus.PENDING)).thenReturn(17480L);

        PendingDocumentsOutput output = service().findDocuments(DocumentStatus.PENDING, 100);

        // extract 가 앞으로 몇 번을 더 불러야 하는지 판단하는 값임
        assertThat(output.total()).isEqualTo(17480L);
    }

    @Test
    @DisplayName("언제나 첫 쪽만 가져온다")
    void alwaysAsksForTheFirstPage() {
        givenDocuments(document("{}"));

        service().findDocuments(DocumentStatus.PENDING, 100);

        // 처리하면 그 문서가 대기 목록에서 빠지므로 쪽 번호로 넘기면
        // 뒤에 있던 것이 앞으로 밀려와 그만큼을 조용히 건너뜀
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(rawDocumentRepository).findByStatus(eq(DocumentStatus.PENDING), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);

        // 정렬을 여기서 담지 않습니다.
        // 그 규칙은 저장소 메서드 이름에 있고, 두 곳에 두면 한쪽만 고치는 실수가 납니다.
        // 실제로 오래된 것부터 오는지는 데이터베이스를 띄워 따로 확인합니다
        assertThat(captor.getValue().getSort().isUnsorted()).isTrue();
    }

    @Test
    @DisplayName("개수가 범위를 벗어나면 맞춰 준다")
    void clampsSize() {
        givenDocuments(document("{}"));

        service().findDocuments(DocumentStatus.PENDING, 100_000);

        // 원본을 통째로 담아 보내는 응답이라 개수가 곧 크기임
        // 거절하지 않고 맞추는 이유는 우리 서비스끼리 쓰는 경로이기 때문임
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(rawDocumentRepository).findByStatus(any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("원본을 읽지 못해도 그 문서를 버리지 않는다")
    void keepsDocumentWhenPayloadIsBroken() {
        givenDocuments(document("이것은 JSON 이 아님"));

        PendingDocumentsOutput output = service().findDocuments(DocumentStatus.PENDING, 100);

        // 우리가 넣은 값이라 닿지 않아야 하는 자리이나, 닿았을 때 그 문서만 빠지면
        // 대기 목록 맨 앞에 남아 뒤가 나가지 못함
        assertThat(output.documents()).hasSize(1);
        assertThat(output.documents().get(0).payload()).isEmpty();
    }

    @Test
    @DisplayName("소스를 안 주면 전부 본다")
    void findsAllSourcesWhenSourceIsNull() {
        when(ingestRunRepository.findRecent(isNull(), eq(20))).thenReturn(List.of(run()));

        assertThat(service().findRecentRuns(null, 20).runs()).hasSize(1);
    }

    @Test
    @DisplayName("소스를 주면 그것만 본다")
    void findsOneSourceWhenGiven() {
        when(ingestRunRepository.findRecent(SourceType.PET_TOUR, 20)).thenReturn(List.of(run()));

        var output = service().findRecentRuns(SourceType.PET_TOUR, 20);

        // 소스가 넷이라 섞이면 스무 건을 받아 세 건만 남는 일이 생김
        assertThat(output.runs()).hasSize(1);
        assertThat(output.runs().get(0).source()).isEqualTo(SourceType.PET_TOUR);
    }

    @Test
    @DisplayName("실행 기록에 진행 상태와 문구를 함께 담는다")
    void includesProgressAndMessage() {
        IngestRun run = run();
        run.stopByQuota(Map.of(), "detailIntro2", "건너뜀 1건: 1059479");
        when(ingestRunRepository.findRecent(isNull(), eq(20))).thenReturn(List.of(run));

        var output = service().findRecentRuns(null, 20).runs().get(0);

        // 사람이 승인을 판단하는 값이 바뀐 건수와 진행 상태임
        assertThat(output.progress()).isNotNull();
        assertThat(output.errorMessage()).contains("detailIntro2").contains("건너뜀 1건");
    }

    private IngestQueryService service() {
        return new IngestQueryService(rawDocumentRepository, ingestRunRepository, objectMapper);
    }

    private void givenDocuments(RawDocument... documents) {
        when(rawDocumentRepository.findByStatus(any(), any()))
                .thenReturn(new PageImpl<>(List.of(documents)));
    }

    private RawDocument document(String payload) {
        return RawDocument.create(
                SourceType.PET_TOUR,
                "1019041",
                payload,
                "와룡공원",
                "[동반 유형] 전구역 동반가능",
                "hash",
                null,
                LocalDateTime.now());
    }

    private IngestRun run() {
        return IngestRun.start(SourceType.PET_TOUR, RunType.FULL);
    }
}
