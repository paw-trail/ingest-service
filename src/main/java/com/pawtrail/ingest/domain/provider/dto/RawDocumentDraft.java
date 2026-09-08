package com.pawtrail.ingest.domain.provider.dto;

import com.pawtrail.ingest.domain.enums.SourceType;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 수집기가 만들어 넘기는 원본 한 건입니다.
 *
 * 엔티티가 아니라 이 형태로 넘기는 이유는 두 가지입니다.
 * 수집기는 이 문서가 처음 보는 것인지 이미 있는 것인지 알 필요가 없고,
 * 해시 계산을 한 곳에만 두어야 직렬화 규칙이 갈리지 않습니다.
 *
 * contentHash 가 여기 없는 것이 그 뜻입니다.
 * 공통 코드가 payload 를 정규화하면서 함께 뜹니다.
 *
 * @param source         소스 종류
 * @param sourceId       소스가 부여한 식별자.
 *                       place_source_link.source_id 와 같은 값이어야 병합이 성립하므로
 *                       접미사를 붙이거나 가공하지 않음
 * @param payload        소스 응답 원본.
 *                       Jackson 타입이 아니라 Map 인 이유는 도메인이 직렬화 라이브러리를
 *                       알지 않게 하기 위함임.
 *                       PET_TOUR 만 응답이 넷이라 list, petTour, common, intro 를 열쇠로 담고
 *                       고캠핑과 문화정보원은 응답이 하나라 그대로 담음
 * @param displayTitle   원문보기 제목
 * @param displayBody    원문보기 본문. 기계용 필드를 뺀 자연어만
 * @param sourceModified 소스가 알려준 마지막 수정 시각. 없는 소스면 null
 */
public record RawDocumentDraft(
        SourceType source,
        String sourceId,
        Map<String, Object> payload,
        String displayTitle,
        String displayBody,
        LocalDateTime sourceModified) {
}
