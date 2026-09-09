package com.pawtrail.ingest.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.ingest.application.dto.output.IngestRunOutput;
import com.pawtrail.ingest.application.dto.output.IngestRunsOutput;
import com.pawtrail.ingest.application.dto.output.PendingDocumentsOutput;
import com.pawtrail.ingest.application.dto.output.RawDocumentOutput;
import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담아 둔 것을 꺼내 줍니다.
 *
 * 부르는 쪽이 둘이고 필요한 것이 다릅니다.
 * extract 는 처리할 문서를 가져가고, 사람은 실행 이력을 보고 승인을 판단합니다.
 *
 * 읽기만 하므로 트랜잭션을 읽기 전용으로 둡니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngestQueryService {

    /**
     * 한 번에 돌려주는 문서 수의 한도입니다.
     *
     * 원본을 통째로 담아 보내는 응답이라 개수가 곧 크기입니다.
     * 고캠핑 한 건이 여든한 필드이고 문화정보원이 서른한 컬럼이라
     * 큰 값을 그대로 받으면 응답이 수십 메가바이트가 됩니다.
     */
    private static final int MAX_DOCUMENT_SIZE = 500;

    /**
     * 한 번에 돌려주는 실행 기록 수의 한도입니다.
     */
    private static final int MAX_RUN_SIZE = 200;

    private final RawDocumentRepository rawDocumentRepository;
    private final IngestRunRepository ingestRunRepository;
    private final ObjectMapper objectMapper;

    /**
     * 그 상태의 문서를 오래된 것부터 돌려줍니다.
     *
     * 언제나 첫 쪽만 돌려줍니다. 쪽 번호를 받지 않습니다.
     * extract 가 처리하면 그 문서가 대기 목록에서 빠지므로,
     * 쪽 번호로 넘기면 뒤에 있던 것이 앞으로 밀려와 그만큼을 통째로 건너뜁니다.
     * 그 일이 조용히 일어나 건수만 안 맞고 로그에는 아무것도 남지 않습니다.
     *
     * 할 일을 가져가는 것이지 목록을 훑는 것이 아니라고 보면 이 모양이 자연스럽습니다.
     * 처리하면 앞에서 빠지고 다음이 올라옵니다.
     *
     * *실패한 문서가 대기로 남으면 그것이 계속 맨 앞에 옵니다.
     *  extract 가 실패를 표시하면 대기에서 빠지므로 그 경로를 함께 두었습니다.
     */
    public PendingDocumentsOutput findDocuments(DocumentStatus status, int size) {
        int limit = clamp(size, MAX_DOCUMENT_SIZE);
        List<RawDocument> documents =
                rawDocumentRepository.findByStatus(status, PageRequest.ofSize(limit)).getContent();

        return new PendingDocumentsOutput(
                rawDocumentRepository.countByStatus(status),
                documents.stream().map(this::toOutput).toList());
    }

    /**
     * 최근 실행을 새것부터 돌려줍니다.
     *
     * @param source null 이면 소스를 가리지 않습니다
     */
    public IngestRunsOutput findRecentRuns(SourceType source, int size) {
        List<IngestRun> runs = ingestRunRepository.findRecent(source, clamp(size, MAX_RUN_SIZE));
        return new IngestRunsOutput(runs.stream().map(this::toOutput).toList());
    }

    /**
     * 원본을 문자열이 아니라 객체로 풀어 담습니다.
     *
     * 엔티티는 원본을 문자열로 들고 있습니다.
     * 그대로 돌려주면 응답의 JSON 안에 JSON 문자열이 박혀
     * 받는 쪽이 두 번 파싱해야 합니다.
     *
     * *지도로 받고 트리 객체로 받지 않습니다.
     *  스프링이 응답을 바꿀 때 쓰는 것과 우리가 주입받아 쓰는 것이 서로 다른 세대의 도구입니다.
     *  한쪽의 트리 타입을 응답에 실으면 다른 쪽이 그것을 다루지 못합니다.
     *  지도와 목록과 문자열로만 이루어진 값은 어느 쪽이든 그대로 씁니다.
     */
    @SuppressWarnings("unchecked")
    private RawDocumentOutput toOutput(RawDocument document) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(document.getPayload(), Map.class);
        } catch (JsonProcessingException e) {
            // 우리가 넣은 값이라 닿지 않아야 하는 자리임
            // 닿는다면 저장할 때 정규화가 깨졌거나 누가 직접 고친 것임
            log.error("원본을 읽지 못했습니다. id={} source={} sourceId={}",
                    document.getId(), document.getSource(), document.getSourceId(), e);
            payload = Map.of();
        }

        return new RawDocumentOutput(
                document.getId(),
                document.getSource(),
                document.getSourceId(),
                payload,
                document.getContentHash(),
                document.getSourceModified());
    }

    private IngestRunOutput toOutput(IngestRun run) {
        return new IngestRunOutput(
                run.getId(),
                run.getSource(),
                run.getRunType(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getFetchedCount(),
                run.getChangedCount(),
                run.getProgress(),
                run.getErrorMessage());
    }

    /**
     * 요청한 개수를 쓸 수 있는 범위로 맞춥니다.
     *
     * 범위를 벗어나면 거절하지 않고 맞춥니다.
     * 이것은 우리 서비스끼리 쓰는 경로이고, 개수를 잘못 적었다고 해서
     * 부르는 쪽의 일을 멈추게 할 이유가 없습니다.
     */
    private int clamp(int size, int max) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, max);
    }
}
