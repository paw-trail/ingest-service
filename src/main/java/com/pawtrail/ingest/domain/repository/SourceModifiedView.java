package com.pawtrail.ingest.domain.repository;

import java.time.LocalDateTime;

/**
 * 증분 판단에 필요한 두 값만 담습니다.
 *
 * 원본을 통째로 읽지 않는 이유가 있습니다.
 * 문서 하나에 소스 응답이 통째로 들어 있어 천 건을 다 읽으면 수십 메가바이트가 됩니다.
 * 상세를 부를지 판단하는 데 필요한 것은 식별자와 수정 시각 둘뿐입니다.
 */
public interface SourceModifiedView {

    String getSourceId();

    /**
     * 소스가 알려준 마지막 수정 시각입니다.
     *
     * 비어 있을 수 있습니다.
     * 소스가 안 줬거나 형식이 달라 읽지 못한 경우입니다.
     * 그때는 판단할 근거가 없으므로 부르는 쪽으로 갑니다.
     */
    LocalDateTime getSourceModified();
}
