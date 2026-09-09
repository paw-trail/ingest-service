package com.pawtrail.ingest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import com.pawtrail.ingest.infrastructure.persistence.jpa.RawDocumentJpaRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 대기 문서가 오래된 것부터 오는지를 실제 데이터베이스로 확인합니다.
 *
 * *모의 객체로는 잡을 수 없는 자리입니다.
 *  정렬은 저장소 메서드 이름에 들어 있어서, 부르는 쪽을 아무리 시험해도
 *  이름에서 정렬이 빠진 것을 알아챌 수 없습니다.
 *
 * *정렬이 없어도 조회는 실패하지 않습니다.
 *  대개 물리적 순서로 돌아오지만 그것은 그날의 실행 계획일 뿐 보장이 아닙니다.
 *  extract 가 상태를 계속 바꾸면 행이 옮겨 다니고,
 *  그러면 처리하지 못한 오래된 문서가 뒤로 밀려 영영 나오지 않을 수 있습니다.
 *  대기 목록을 쪽 번호 없이 언제나 첫 쪽만 주기로 한 판단이 이 정렬에 기대고 있습니다.
 *
 * 데이터베이스를 컨테이너로 직접 띄우는 이유는 컨텍스트 검사와 같습니다.
 * 설정 서버가 떠 있는지에 따라 결과가 갈리면 검사로서 의미가 없습니다.
 */
@SpringBootTest
@Testcontainers
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RawDocumentRepositoryOrderTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private RawDocumentRepository rawDocumentRepository;

    @Autowired
    private RawDocumentJpaRepository rawDocumentJpaRepository;

    @Test
    @DisplayName("대기 문서를 오래된 것부터 돌려준다")
    void returnsOldestFirst() {
        rawDocumentJpaRepository.deleteAll();

        // 저장 차례를 뒤섞습니다.
        // 정렬이 없으면 넣은 차례나 물리적 자리에 따라 결과가 달라집니다
        List<RawDocument> saved = List.of(
                save("C"), save("A"), save("E"), save("B"), save("D"));
        rawDocumentJpaRepository.flush();

        List<RawDocument> found = rawDocumentRepository
                .findByStatus(DocumentStatus.PENDING, PageRequest.ofSize(10))
                .getContent();

        // 식별자가 시간 순서를 담은 uuid v7 이라 먼저 저장한 것이 앞에 와야 합니다
        List<UUID> expected = saved.stream()
                .map(RawDocument::getId)
                .sorted(Comparator.naturalOrder())
                .toList();

        assertThat(found).extracting(RawDocument::getId).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("앞쪽을 처리하면 다음 것이 올라온다")
    void movesOnAfterProcessing() {
        rawDocumentJpaRepository.deleteAll();

        List<RawDocument> saved = List.of(save("A"), save("B"), save("C"), save("D"));
        rawDocumentJpaRepository.flush();

        List<RawDocument> first = rawDocumentRepository
                .findByStatus(DocumentStatus.PENDING, PageRequest.ofSize(2))
                .getContent();
        first.forEach(RawDocument::markDone);
        rawDocumentJpaRepository.flush();

        List<RawDocument> next = rawDocumentRepository
                .findByStatus(DocumentStatus.PENDING, PageRequest.ofSize(2))
                .getContent();

        // 처리한 것이 대기 목록에서 빠지고 그다음이 맨 앞으로 옵니다
        // 쪽 번호로 넘겼다면 여기서 세 번째와 네 번째가 아니라 그 뒤가 나왔을 것입니다
        assertThat(next).extracting(RawDocument::getId)
                .doesNotContainAnyElementsOf(first.stream().map(RawDocument::getId).toList())
                .hasSize(2);
        assertThat(rawDocumentRepository.countByStatus(DocumentStatus.PENDING)).isEqualTo(2);
        assertThat(saved).hasSize(4);
    }

    private RawDocument save(String sourceId) {
        return rawDocumentJpaRepository.save(RawDocument.create(
                SourceType.PET_TOUR,
                sourceId,
                "{}",
                "제목 " + sourceId,
                "본문",
                "hash-" + sourceId,
                null,
                LocalDateTime.now()));
    }
}
