package com.pawtrail.ingest.application.dto.output;

import java.util.List;

/**
 * 처리 대기 문서 목록입니다.
 *
 * @param total     그 상태이면서 장소에 이어진 문서가 모두 몇 건인지.
 *                  extract 가 앞으로 몇 번을 더 불러야 하는지 판단하고 진행률을 찍습니다.
 *                  장소에 이어지지 않은 문서는 목록에 안 나오므로 여기서도 셈하지 않습니다
 * @param documents 이번에 가져갈 것. 오래된 것부터입니다
 */
public record PendingDocumentsOutput(
        long total,
        List<RawDocumentOutput> documents) {
}
