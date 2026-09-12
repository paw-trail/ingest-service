package com.pawtrail.ingest.domain.provider;

import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import com.pawtrail.ingest.domain.provider.dto.PlaceLinkResult;
import java.util.List;

/**
 * 받아 둔 것을 장소 서비스에 넘기는 약속입니다.
 *
 * 이 인터페이스에는 HTTP 도 서비스 이름도 나오지 않습니다.
 * 무엇을 할 수 있는지만 적고 어떻게 부르는지는 infrastructure 가 정합니다.
 *
 * 실패를 값으로 돌려주지 않습니다.
 * 수집기가 바깥 소스를 부를 때는 건별로 건너뛰는 것이 맞지만 여기는 다릅니다.
 * 상대가 한 서비스라 한 묶음이 실패하면 다음 묶음도 같은 이유로 실패합니다.
 * 계속 보내면 같은 오류를 쌓기만 하므로 부르는 쪽이 그 자리에서 멈추게 합니다.
 */
public interface PlaceLinkClient {

    /**
     * 한 묶음을 보내고 어느 레코드가 어느 장소가 됐는지를 받아옵니다.
     *
     * 보낸 것보다 짧게 돌아올 수 있습니다.
     * 좌표도 주소도 없어 장소를 만들지 못한 레코드는 짝이 없습니다.
     *
     * @throws RuntimeException 보내지 못했거나 응답을 읽지 못한 경우입니다.
     */
    PlaceLinkResult send(List<PlaceBulkItem> items);
}
