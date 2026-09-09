package com.pawtrail.ingest.application.dto.output;

import java.util.List;

/**
 * 처리 대기 문서 목록입니다.
 *
 * @param total     그 상태인 문서가 모두 몇 건인지.
 *                  extract 가 앞으로 몇 번을 더 불러야 하는지 판단하고 진행률을 찍습니다
 * @param documents 이번에 가져갈 것. 오래된 것부터입니다
 */
public record PendingDocumentsOutput(
        long total,
        List<RawDocumentOutput> documents) {
}
