package com.pawtrail.ingest.application.dto.output;

import java.util.List;

/**
 * 한 장소의 원문 묶음입니다.
 *
 * 목록을 그대로 내보내지 않고 한 겹 감쌉니다.
 * 배열을 최상위로 두면 나중에 필드를 더할 때 응답 모양이 통째로 바뀌어
 * 부르는 쪽이 파싱하는 코드를 고쳐야 합니다.
 * 이 서비스의 다른 목록 응답도 같은 모양입니다.
 *
 * 총 건수를 담지 않습니다.
 * 처리 대기 목록은 앞으로 몇 번을 더 불러야 하는지가 필요해 그 값을 담지만,
 * 여기는 한 장소에 이어질 수 있는 원본이 많아야 셋이라 쪽을 나눌 일이 없습니다.
 *
 * @param documents 그 장소가 어느 원본에서 왔는지입니다. 없으면 빈 목록입니다.
 */
public record PlaceDocumentsOutput(List<RawDocumentViewOutput> documents) {
}
