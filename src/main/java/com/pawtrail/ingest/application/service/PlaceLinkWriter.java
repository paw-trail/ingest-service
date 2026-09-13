package com.pawtrail.ingest.application.service;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.provider.dto.PlaceLinkResult;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장소 서비스가 돌려준 식별자를 원본에 채웁니다.
 *
 * 실행기와 나눈 이유가 스프링 프록시 때문입니다.
 * 같은 객체 안의 메서드를 부르면 트랜잭션이 걸리지 않아
 * 실행 전체가 한 트랜잭션이 되거나 아예 트랜잭션 없이 돕니다.
 * 수집 쪽에서 실행기와 기록기를 나눈 것과 같은 이유입니다.
 *
 * 청크마다 커밋합니다.
 * 만 칠천 건을 한 트랜잭션으로 묶으면 도중에 끊겼을 때 앞의 것까지 통째로 사라지고,
 * 그동안 그 행들이 잠겨 있어 다른 일이 끼어들 수도 없습니다.
 *
 * 마감은 여기서 하지 않습니다.
 * 끝까지 마친 것과 실패한 것을 기록하는 일은 수집 쪽 기록기가 이미 하고 있고,
 * 실행 기록을 다루는 방식이 같아 같은 코드를 두 번 쓸 이유가 없습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceLinkWriter {

    private final RawDocumentRepository rawDocumentRepository;
    private final IngestRunRepository ingestRunRepository;

    /**
     * 한 묶음의 결과를 반영합니다.
     *
     * 돌려받은 짝을 원본 식별자로 바꿔 한 번에 읽습니다.
     * 소스 식별자로 하나씩 찾으면 묶음마다 천 번을 조회하게 됩니다.
     *
     * 짝이 없는 레코드는 건드리지 않습니다.
     * 좌표도 주소도 없어 장소를 만들지 못한 것이라 채울 값이 없습니다.
     * 그 수는 결과의 건너뛴 수로 드러납니다.
     *
     * 실행 기록에는 보낸 수와 채운 수를 더합니다.
     * 진행 위치는 남기지 않습니다. 끊기면 처음부터 다시 하기로 했기 때문입니다.
     *
     * 여기서 약속을 다시 검사하지 않습니다.
     * 장소 식별자가 비었는지, 보낸 적 없는 것인지, 같은 것이 두 번 왔는지는
     * 응답을 읽는 자리가 이미 보고 어긋나면 이 메서드를 부르지 않습니다.
     *
     * @param rawIdByKey 그 묶음에서 소스와 소스 식별자로 원본 식별자를 찾는 표
     * @param result     장소 서비스가 돌려준 것
     * @return 실제로 채운 건수
     */
    @Transactional
    public int applyChunk(UUID runId, int sentCount,
                          Map<String, UUID> rawIdByKey, PlaceLinkResult result) {

        List<UUID> rawIds = result.links().stream()
                .map(link -> rawIdByKey.get(key(link.source(), link.sourceId())))
                .filter(Objects::nonNull)
                .toList();

        Map<UUID, RawDocument> found = rawDocumentRepository.findAllByIds(rawIds).stream()
                .collect(Collectors.toMap(RawDocument::getId, Function.identity()));

        int linked = 0;
        for (PlaceLinkResult.SourceLink link : result.links()) {
            UUID rawId = rawIdByKey.get(key(link.source(), link.sourceId()));
            if (rawId == null) {
                // 응답을 읽는 자리가 이미 걸렀어야 하는 값임
                //
                // 닿는다면 그쪽 검사와 이 표를 만드는 코드가 어긋난 것임
                log.error("보낸 적 없는 레코드가 여기까지 왔습니다. runId={} source={} sourceId={}",
                        runId, link.source(), link.sourceId());
                throw new CustomException(IngestErrorCode.PLACE_LINK_FAILED);
            }
            RawDocument document = found.get(rawId);
            if (document == null) {
                // 방금 그 식별자로 읽어 왔는데 없는 경우임
                //
                // 그 사이에 누가 지운 것이라 이 묶음의 다른 건도 믿을 수 없음
                log.error("원본을 찾지 못했습니다. runId={} rawId={}", runId, rawId);
                throw new CustomException(IngestErrorCode.PLACE_LINK_FAILED);
            }
            document.linkPlace(link.placeId());
            rawDocumentRepository.save(document);
            linked++;
        }

        IngestRun run = ingestRunRepository.findById(runId)
                .orElseThrow(() -> new CustomException(IngestErrorCode.INGEST_RUN_NOT_FOUND));
        run.applyChunk(sentCount, linked, Map.of());

        log.debug("묶음을 반영했습니다. runId={} sent={} linked={} created={} merged={} skipped={}",
                runId, sentCount, linked, result.created(), result.merged(), result.skipped());
        return linked;
    }

    // 소스와 소스 식별자를 한 열쇠로 묶음
    //
    // 소스 식별자만으로는 데이터셋이 다른 같은 번호를 가를 수 없음
    // 응답을 읽는 자리가 쓰는 것과 같은 방식이어야 함
    static String key(SourceType source, String sourceId) {
        return source + "\u0000" + sourceId;
    }
}
