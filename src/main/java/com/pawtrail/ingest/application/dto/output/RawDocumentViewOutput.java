package com.pawtrail.ingest.application.dto.output;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.model.RawDocument;
import java.time.LocalDateTime;

/**
 * 사람이 읽는 원문 하나입니다.
 *
 * 같은 원본을 다루지만 extract 가 가져가는 것과 반대입니다.
 * 그쪽은 소스 응답을 그대로 주고 표시용 문장을 빼는데,
 * 여기는 표시용 문장만 주고 소스 응답을 담지 않습니다.
 * 쓰는 쪽이 기계와 사람으로 갈리기 때문입니다.
 *
 * 소스 응답을 담지 않는 이유가 둘입니다.
 *
 * 원문의 범위를 이미 정해 두었습니다.
 * 기계용 필드를 빼고 사람이 읽는 자연어만 남기기로 했고 본문이 그 기준으로 조립된 것입니다.
 * 좌표와 분류 코드와 타임스탬프는 사람이 읽을 것이 아닙니다.
 *
 * 그리고 개인정보가 섞여 있습니다.
 * 고캠핑 응답에 관리자 개인 이름과 사업자번호가 들어 있어 그대로 내보내면 화면까지 흘러갑니다.
 * 걸러 내려면 소스마다 규칙을 또 만들어야 하는데 그것이 본문을 조립하며 이미 한 일입니다.
 *
 * 표시 이름을 담지 않습니다.
 * 이 서비스의 소스 열거값에는 그 값이 주석에만 있고, 부르는 쪽이 이미 가지고 있습니다.
 * 여기서 내보내면 같은 화면에 출처 이름이 두 곳에서 오게 되어 한쪽만 고치는 날 어긋납니다.
 *
 * @param source           어느 데이터셋에서 왔는지입니다. 표시 이름은 부르는 쪽이 붙입니다.
 * @param title            소스가 부른 이름입니다. 장소 이름과 다를 수 있고 그 차이가 정보입니다.
 * @param body             사람이 읽는 본문입니다. 조립할 내용이 없으면 비어 있습니다.
 * @param sourceModifiedAt 소스가 알려준 마지막 수정 시각입니다. 없을 수 있습니다.
 * @param fetchedAt        우리가 받아 온 시각입니다. 값이 낡았는지 판단하는 재료입니다.
 */
public record RawDocumentViewOutput(SourceType source,
                                    String title,
                                    String body,
                                    LocalDateTime sourceModifiedAt,
                                    LocalDateTime fetchedAt) {

    public static RawDocumentViewOutput from(RawDocument document) {
        return new RawDocumentViewOutput(
                document.getSource(),
                document.getDisplayTitle(),
                document.getDisplayBody(),
                document.getSourceModified(),
                document.getFetchedAt());
    }
}
