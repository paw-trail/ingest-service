package com.pawtrail.ingest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import com.pawtrail.ingest.infrastructure.persistence.jpa.RawDocumentJpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 처리 상태를 내용 해시가 같을 때만 바꾸는지를 실제 데이터베이스로 확인합니다.
 *
 * *모의 객체로는 잡을 수 없는 자리입니다.
 *  해시 조건은 쿼리 문장 안에 있어서, 서비스를 아무리 시험해도
 *  조건이 빠지거나 다른 칸을 쓰는 것을 알아챌 수 없습니다.
 *
 * *상태 칸 하나만 바뀌는지도 봅니다.
 *  엔티티째 저장하던 때는 모든 칸이 다시 쓰여, 그사이 재수집이 바꾼 원본을
 *  옛 값으로 되덮을 수 있었습니다.
 */
@SpringBootTest
@Testcontainers
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RawDocumentStatusUpdateTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private RawDocumentRepository rawDocumentRepository;

    @Autowired
    private RawDocumentJpaRepository rawDocumentJpaRepository;

    @Test
    @DisplayName("해시가 같으면 상태와 감사 칸만 바꾸고 원본과 장소는 그대로 둔다")
    void marksWhenHashMatches() {
        UUID placeId = UUID.randomUUID();
        RawDocument document = save("A", "hash-A", placeId);
        LocalDateTime stamp = LocalDateTime.of(2026, 9, 18, 12, 0);

        boolean marked = rawDocumentRepository.markStatusIfUnchanged(
                document.getId(), "hash-A", DocumentStatus.DONE, stamp, "extract-test");

        RawDocument reloaded = rawDocumentJpaRepository.findById(document.getId()).orElseThrow();
        assertThat(marked).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.DONE);
        assertThat(reloaded.getUpdatedBy()).isEqualTo("extract-test");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(stamp);
        // jsonb 가 공백을 다시 찍으므로 글자 그대로가 아니라 값으로 봄
        assertThat(reloaded.getPayload()).contains("\"A\"");
        assertThat(reloaded.getPlaceId()).isEqualTo(placeId);
    }

    @Test
    @DisplayName("재수집이 내용을 바꾼 뒤 옛 해시로 온 결과는 대기로 남는다")
    void keepsPendingWhenContentChangedMeanwhile() {
        RawDocument document = save("A", "hash-A", UUID.randomUUID());

        // extract 가 hash-A 로 가져간 사이에 재수집이 내용을 바꿈
        RawDocument collected = rawDocumentJpaRepository.findById(document.getId()).orElseThrow();
        collected.applyIfChanged("{\"v\":\"B\"}", "제목 B", "본문 B", "hash-B", null, LocalDateTime.now());
        rawDocumentJpaRepository.flush();

        boolean marked = rawDocumentRepository.markStatusIfUnchanged(
                document.getId(), "hash-A", DocumentStatus.DONE, LocalDateTime.now(), "extract-test");

        // 옛 내용으로 뽑은 결과라 DONE 으로 덮지 않음 — 다음 실행이 새 내용을 가져감
        RawDocument reloaded = rawDocumentJpaRepository.findById(document.getId()).orElseThrow();
        assertThat(marked).isFalse();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(reloaded.getContentHash()).isEqualTo("hash-B");
        assertThat(reloaded.getPayload()).contains("\"B\"");
    }

    @Test
    @DisplayName("있는 식별자만 돌려준다")
    void findsExistingIdsOnly() {
        RawDocument first = save("A", "hash-A", UUID.randomUUID());
        RawDocument second = save("B", "hash-B", UUID.randomUUID());
        UUID missing = UUID.randomUUID();

        Set<UUID> existing = rawDocumentRepository.findExistingIds(
                List.of(first.getId(), second.getId(), missing));

        assertThat(existing).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    private RawDocument save(String sourceId, String contentHash, UUID placeId) {
        RawDocument document = RawDocument.create(
                SourceType.PET_TOUR,
                sourceId,
                "{\"v\":\"" + sourceId + "\"}",
                "제목 " + sourceId,
                "본문",
                contentHash,
                null,
                LocalDateTime.now());
        document.linkPlace(placeId);
        RawDocument saved = rawDocumentJpaRepository.save(document);
        rawDocumentJpaRepository.flush();
        return saved;
    }
}
