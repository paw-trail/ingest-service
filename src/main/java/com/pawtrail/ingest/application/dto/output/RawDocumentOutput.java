package com.pawtrail.ingest.application.dto.output;

import com.pawtrail.ingest.domain.enums.SourceType;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * extract 가 가져가는 원본 문서 하나입니다.
 *
 * *표시용 제목과 본문을 넣지 않습니다.
 *  extract 는 원본에서 조건 문구를 뽑지 표시용 본문을 보지 않습니다.
 *  만 칠천 건을 백 건씩 나눠 주는데 쓰지 않는 필드가 실리면 응답만 커집니다.
 *  쓰임이 확인되면 그때 넣는 편이 되돌리기 쉽습니다.
 *
 * @param id             문서 식별자. 처리 결과를 되돌려 쓸 때 이 값을 그대로 보냅니다
 * @param source         어느 소스에서 왔는지
 * @param sourceId       소스가 부여한 식별자. 사람이 어느 장소인지 알아볼 수 있는 값입니다
 * @param placeId        이 문서가 이어진 장소. extract 가 뽑은 조건을 장소 단위로 policy 에 보낼 때 씁니다.
 *                       장소에 이어지지 않은 문서는 목록에 나오지 않으므로 늘 값이 있습니다
 * @param payload        소스 응답 원본
 * @param contentHash    내용 해시. 처리 결과를 되돌려 쓸 때 이 값을 그대로 보냅니다.
 *                       그 사이에 재수집이 내용을 바꿨으면 상태가 바뀌지 않습니다
 * @param sourceModified 소스가 알려준 마지막 수정 시각
 */
public record RawDocumentOutput(
        UUID id,
        SourceType source,
        String sourceId,
        UUID placeId,
        Map<String, Object> payload,
        String contentHash,
        LocalDateTime sourceModified) {
}
