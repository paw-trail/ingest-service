package com.pawtrail.ingest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 */
@ExtendWith(MockitoExtension.class)
class RawDocumentStatusServiceTest {

    @Mock
    private RawDocumentRepository rawDocumentRepository;

    @Test
    @DisplayName("처리 완료와 실패를 함께 반영한다")
    void appliesDoneAndFailed() {
        RawDocument first = document();
        RawDocument second = document();
        RawDocument third = document();
        given(first, second, third);

        var service = new RawDocumentStatusService(rawDocumentRepository);
        var result = service.apply(List.of(idOf(first), idOf(second)), List.of(idOf(third)));

        assertThat(result.updated()).isEqualTo(3);
        assertThat(first.getStatus()).isEqualTo(DocumentStatus.DONE);
        assertThat(second.getStatus()).isEqualTo(DocumentStatus.DONE);
        assertThat(third.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("없는 식별자가 하나라도 있으면 전체를 거절한다")
    void rejectsWhenAnyIdIsMissing() {
        RawDocument found = document();
        UUID missing = UUID.randomUUID();
        given(found);

        var service = new RawDocumentStatusService(rawDocumentRepository);

        assertThatThrownBy(() -> service.apply(List.of(idOf(found), missing), List.of()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(IngestErrorCode.RAW_DOCUMENT_NOT_FOUND));

        // 조용히 건너뛰면 그 문서가 영영 대기로 남아 목록 맨 앞을 막음
        // 찾은 것도 바꾸지 않아야 절반만 바뀐 상태가 안 생김
        assertThat(found.getStatus()).isEqualTo(DocumentStatus.PENDING);
    }

    @Test
    @DisplayName("같은 식별자가 양쪽에 있으면 거절한다")
    void rejectsOverlappingIds() {
        UUID id = UUID.randomUUID();
        var service = new RawDocumentStatusService(rawDocumentRepository);

        assertThatThrownBy(() -> service.apply(List.of(id), List.of(id)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(IngestErrorCode.RAW_DOCUMENT_STATUS_CONFLICT));

        // 어느 것으로 둘지 우리가 고를 수 없음, 조회조차 하지 않고 거절함
        verify(rawDocumentRepository, never()).findAllByIds(anyCollection());
    }

    @Test
    @DisplayName("같은 식별자가 두 번 오면 한 번으로 센다")
    void countsDuplicatesOnce() {
        RawDocument document = document();
        given(document);

        var service = new RawDocumentStatusService(rawDocumentRepository);
        var result = service.apply(List.of(idOf(document), idOf(document)), List.of());

        // 결과가 달라지지 않으므로 거절하지 않음
        assertThat(result.updated()).isEqualTo(1);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.DONE);
    }

    @Test
    @DisplayName("둘 다 비어 있으면 0 을 돌려주고 조회하지 않는다")
    void returnsZeroWhenNothingToUpdate() {
        var service = new RawDocumentStatusService(rawDocumentRepository);

        // 처리할 것이 없었다는 뜻이라 오류가 아님
        assertThat(service.apply(List.of(), List.of()).updated()).isZero();
        assertThat(service.apply(null, null).updated()).isZero();
        verify(rawDocumentRepository, never()).findAllByIds(anyCollection());
    }

    private void given(RawDocument... documents) {
        when(rawDocumentRepository.findAllByIds(anyCollection())).thenReturn(List.of(documents));
    }

    /**
     * 식별자는 저장할 때 붙으므로 시험에서는 직접 넣어 줍니다.
     */
    private RawDocument document() {
        RawDocument document = RawDocument.create(
                SourceType.PET_TOUR,
                UUID.randomUUID().toString(),
                "{}",
                "제목",
                "본문",
                "hash",
                null,
                LocalDateTime.now());
        setId(document, UUID.randomUUID());
        return document;
    }

    private UUID idOf(RawDocument document) {
        return document.getId();
    }

    private void setId(RawDocument document, UUID id) {
        try {
            Field field = RawDocument.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("시험용 식별자를 넣지 못했습니다", e);
        }
    }
}
