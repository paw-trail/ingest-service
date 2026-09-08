package com.pawtrail.ingest.infrastructure.provider.external.dto;

import java.util.List;
import java.util.Map;

/**
 * 목록 한 쪽을 받아 온 결과입니다.
 *
 * 항목을 타입 있는 객체가 아니라 지도로 담습니다.
 * 이 값이 그대로 원본으로 저장되기 때문입니다.
 * 필드를 하나라도 빠뜨리면 원본이 아니게 되고,
 * 소스가 필드를 늘렸을 때 조용히 잃습니다.
 *
 * 원본을 잘라내지 않는 것이 이 표의 존재 이유이므로
 * 여기서 구조를 좁히면 나중에 다시 받아 오는 수밖에 없습니다.
 * 그 재수집이 호출 허용량을 쓰는 일이라 값이 큽니다.
 *
 * @param totalCount 소스가 알려 준 전체 건수. 마지막 쪽인지 판단하는 데 씁니다
 * @param pageNo     받아 온 쪽 번호
 * @param numOfRows  요청한 쪽 크기
 * @param items      항목 원본. 걸러내기 전의 상태입니다
 */
public record PetTourListPage(
        int totalCount,
        int pageNo,
        int numOfRows,
        List<Map<String, Object>> items) {
}
