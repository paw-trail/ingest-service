package com.pawtrail.ingest.domain.provider;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import java.util.Map;

/**
 * 받아 둔 원본을 장소 서비스가 받는 형태로 바꿉니다.
 *
 * 소스마다 구현을 따로 둡니다.
 * 응답 구조가 전혀 달라 한 메서드에 조건문으로 담으면
 * 소스를 더할 때마다 그 메서드가 길어지고 어느 줄이 어느 소스 것인지 읽히지 않습니다.
 * 수집기를 소스별로 나눈 것과 같은 이유입니다.
 *
 * 이 인터페이스가 도메인에 있는 이유는 무엇을 할 수 있는지만 적기 때문입니다.
 * 어느 키에서 어떻게 꺼내는지는 소스 사정이라 infrastructure 가 정합니다.
 *
 * 정규화는 하지 않습니다.
 * 이름을 다듬고 주소를 표준화하고 좌표를 검사하는 일은 받는 쪽이 합니다.
 * 여기서 하면 같은 규칙이 두 서비스에 생기고 두 규칙이 갈리는 날이 옵니다.
 */
public interface PlaceItemConverter {

    /**
     * 이 변환기가 맡은 소스입니다.
     */
    SourceType source();

    /**
     * 원본 하나를 요청 한 건으로 바꿉니다.
     *
     * 이름을 찾지 못하면 null 을 돌려줍니다.
     * 받는 쪽이 이름을 필수로 요구해 그대로 보내면 그 청크가 통째로 거절됩니다.
     * 한 건 때문에 나머지 구백아흔아홉 건을 잃지 않도록 부르는 쪽이 걸러 냅니다.
     *
     * @param sourceId 그 데이터셋 안에서의 식별자입니다.
     * @param payload  소스가 준 원본입니다. 이미 맵으로 읽어 둔 것을 받습니다.
     */
    PlaceBulkItem convert(String sourceId, Map<String, Object> payload);
}
