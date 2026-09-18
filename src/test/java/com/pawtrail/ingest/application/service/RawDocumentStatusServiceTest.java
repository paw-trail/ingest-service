package com.pawtrail.ingest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawtrail.common.audit.AuditorProvider;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.application.dto.input.StatusMarkInput;
import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 이 서비스의 첫 쓰기 경로라 어긋났을 때 대가가 큽니다.
 *
 * 없는 식별자를 조용히 건너뛰면 그 문서가 영영 대기로 남아 목록 맨 앞을 막습니다.
 * 대기 목록은 언제나 오래된 것부터 주므로 맨 앞이 막히면 그 뒤가 나가지 못합니다.
 *
 * 부분 실패도 생기면 안 됩니다.
 * 부르는 쪽이 묶음으로 일하므로 절반만 바뀐 상태는 다음에 무엇을 다시 해야 할지
 * 아무도 모르는 상태입니다.
 *
 * 내용 해시가 달라 건너뛰는 것은 부분 실패가 아닙니다.
 * 그 문서는 대기로 남고 다음 실행이 새 내용으로 다시 가져가는 것이 맞는 결과입니다.
 * 해시 조건이 데이터베이스에서 실제로 걸리는지는 RawDocumentStatusUpdateTest 가 봅니다.
 */
@ExtendWith(MockitoExtension.class)
class RawDocumentStatusServiceTest {

    private static final String AUDITOR = "ingest-batch";

    @Mock
    private RawDocumentRepository rawDocumentRepository;

    @Mock
    private AuditorProvider auditorProvider;

    @Test
    @DisplayName("처리 완료와 실패를 함께 반영한다")
    void appliesDoneAndFailed() {
        StatusMarkInput first = mark("hash-1");
        StatusMarkInput second = mark("hash-2");
        StatusMarkInput third = mark("hash-3");
        givenExisting(first, second, third);
        givenAuditor();
        givenUnchanged(true);

        var result = service().apply(List.of(first, second), List.of(third));

        assertThat(result.updated()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        verify(rawDocumentRepository).markStatusIfUnchanged(
                eq(first.id()), eq("hash-1"), eq(DocumentStatus.DONE), any(LocalDateTime.class), eq(AUDITOR));
        verify(rawDocumentRepository).markStatusIfUnchanged(
                eq(second.id()), eq("hash-2"), eq(DocumentStatus.DONE), any(LocalDateTime.class), eq(AUDITOR));
        verify(rawDocumentRepository).markStatusIfUnchanged(
                eq(third.id()), eq("hash-3"), eq(DocumentStatus.FAILED), any(LocalDateTime.class), eq(AUDITOR));
    }

    @Test
    @DisplayName("처리하는 사이에 내용이 바뀐 문서는 건너뛰고 건수로 알린다")
    void skipsDocumentsChangedMeanwhile() {
        StatusMarkInput unchanged = mark("hash-1");
        StatusMarkInput changed = mark("stale-hash");
        givenExisting(unchanged, changed);
        givenAuditor();
        when(rawDocumentRepository.markStatusIfUnchanged(
                eq(unchanged.id()), anyString(), any(), any(), anyString())).thenReturn(true);
        when(rawDocumentRepository.markStatusIfUnchanged(
                eq(changed.id()), anyString(), any(), any(), anyString())).thenReturn(false);

        var result = service().apply(List.of(unchanged, changed), List.of());

        // 거절하지 않음 — 그 문서는 대기로 남아 다음 실행이 새 내용으로 다시 가져감
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 요청의 감사 값은 하나로 묶는다")
    void usesOneAuditStampPerRequest() {
        StatusMarkInput first = mark("hash-1");
        StatusMarkInput second = mark("hash-2");
        givenExisting(first, second);
        givenAuditor();
        givenUnchanged(true);

        service().apply(List.of(first), List.of(second));

        ArgumentCaptor<LocalDateTime> stamps = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(rawDocumentRepository, times(2)).markStatusIfUnchanged(
                any(), anyString(), any(), stamps.capture(), eq(AUDITOR));
        assertThat(stamps.getAllValues()).hasSize(2).containsOnly(stamps.getAllValues().get(0));
    }

    @Test
    @DisplayName("없는 식별자가 하나라도 있으면 전체를 거절한다")
    void rejectsWhenAnyIdIsMissing() {
        StatusMarkInput found = mark("hash-1");
        StatusMarkInput missing = mark("hash-2");
        givenExisting(found);

        assertThatThrownBy(() -> service().apply(List.of(found, missing), List.of()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(IngestErrorCode.RAW_DOCUMENT_NOT_FOUND));

        // 조용히 건너뛰면 그 문서가 영영 대기로 남아 목록 맨 앞을 막음
        // 찾은 것도 바꾸지 않아야 절반만 바뀐 상태가 안 생김
        verify(rawDocumentRepository, never()).markStatusIfUnchanged(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("같은 식별자가 양쪽에 있으면 거절한다")
    void rejectsOverlappingIds() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service().apply(
                List.of(new StatusMarkInput(id, "hash")), List.of(new StatusMarkInput(id, "hash"))))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(IngestErrorCode.RAW_DOCUMENT_STATUS_CONFLICT));

        // 어느 것으로 둘지 우리가 고를 수 없음, 조회조차 하지 않고 거절함
        verify(rawDocumentRepository, never()).findExistingIds(anyCollection());
    }

    @Test
    @DisplayName("같은 식별자가 두 번 오면 한 번으로 센다")
    void countsDuplicatesOnce() {
        StatusMarkInput document = mark("hash-1");
        givenExisting(document);
        givenAuditor();
        givenUnchanged(true);

        var result = service().apply(List.of(document, document), List.of());

        // 결과가 달라지지 않으므로 거절하지 않음
        assertThat(result.updated()).isEqualTo(1);
        verify(rawDocumentRepository, times(1)).markStatusIfUnchanged(
                eq(document.id()), eq("hash-1"), eq(DocumentStatus.DONE), any(), anyString());
    }

    @Test
    @DisplayName("둘 다 비어 있으면 0 을 돌려주고 조회하지 않는다")
    void returnsZeroWhenNothingToUpdate() {
        // 처리할 것이 없었다는 뜻이라 오류가 아님
        var empty = service().apply(List.of(), List.of());
        var none = service().apply(null, null);

        assertThat(empty.updated()).isZero();
        assertThat(empty.skipped()).isZero();
        assertThat(none.updated()).isZero();
        verify(rawDocumentRepository, never()).findExistingIds(anyCollection());
    }

    private RawDocumentStatusService service() {
        return new RawDocumentStatusService(rawDocumentRepository, auditorProvider);
    }

    private StatusMarkInput mark(String contentHash) {
        return new StatusMarkInput(UUID.randomUUID(), contentHash);
    }

    private void givenExisting(StatusMarkInput... marks) {
        Set<UUID> ids = new java.util.HashSet<>();
        for (StatusMarkInput mark : marks) {
            ids.add(mark.id());
        }
        when(rawDocumentRepository.findExistingIds(anyCollection())).thenReturn(ids);
    }

    private void givenAuditor() {
        when(auditorProvider.current()).thenReturn(AUDITOR);
    }

    private void givenUnchanged(boolean unchanged) {
        when(rawDocumentRepository.markStatusIfUnchanged(any(), anyString(), any(), any(), anyString()))
                .thenReturn(unchanged);
    }
}
