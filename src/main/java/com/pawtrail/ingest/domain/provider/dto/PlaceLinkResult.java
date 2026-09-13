package com.pawtrail.ingest.domain.provider.dto;

import com.pawtrail.ingest.domain.enums.SourceType;
import java.util.List;
import java.util.UUID;

/**
 * 장소 서비스가 한 묶음을 처리하고 돌려준 결과입니다.
 *
 * 건수와 짝을 함께 받습니다.
 * 건수는 실행 기록에 남겨 나중에 무엇이 얼마나 들어갔는지를 보는 데 쓰고,
 * 짝은 우리 표의 장소 식별자를 채우는 데 씁니다.
 *
 * 짝이 보낸 것보다 짧을 수 있습니다.
 * 좌표도 주소도 없어 장소를 만들지 못한 레코드는 알려 줄 식별자가 없습니다.
 * 그 수는 skipped 로 드러납니다.
 *
 * 응답에 있는 필드를 다 적지 않습니다.
 * 잠긴 장소에 쌓인 대기 행 건수도 오지만 그것은 그쪽 관리자가 볼 값이고
 * 우리가 할 일이 달라지지 않아 담지 않습니다.
 *
 * @param created 새로 만들어진 장소 수입니다.
 * @param merged  이미 있는 장소에 붙은 수입니다.
 * @param skipped 장소를 만들지 못해 넘어간 수입니다.
 * @param links   어느 소스 레코드가 어느 장소가 됐는지입니다.
 */
public record PlaceLinkResult(int created,
                              int merged,
                              int skipped,
                              List<SourceLink> links) {

    /**
     * 소스 레코드 하나와 그것이 속한 장소입니다.
     *
     * 소스까지 함께 받는 이유가 있습니다.
     * 한 묶음에 한 소스만 담기지만 그것은 지금 부르는 쪽 사정이고,
     * 값 안에 열쇠가 다 들어 있으면 순서에 기대지 않아도 짝을 맞출 수 있습니다.
     */
    public record SourceLink(SourceType source, String sourceId, UUID placeId) {
    }
}
