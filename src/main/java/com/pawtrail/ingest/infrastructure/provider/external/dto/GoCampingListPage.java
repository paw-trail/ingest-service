package com.pawtrail.ingest.infrastructure.provider.external.dto;

import java.util.List;
import java.util.Map;

/**
 * 고캠핑 목록 한 쪽입니다.
 *
 * 항목을 타입 있는 객체로 받지 않고 지도 그대로 둡니다.
 * 원본을 통째로 보관하는 것이 이 서비스의 일이라 필드를 골라 담으면
 * 나중에 더 필요해졌을 때 다시 받아야 합니다. 재수집이 여기서 가장 비싼 자원입니다.
 *
 * 고캠핑은 필드가 여든한 개인데 그중 우리가 쓰는 것은 스무 개 남짓입니다.
 * 나머지를 버리지 않는 이유가 그것입니다.
 *
 * 항목이 없을 때 소스가 빈 객체가 아니라 빈 문자열을 주는 경우가 있어,
 * 클라이언트가 형태를 확인한 뒤 이 객체를 만듭니다.
 *
 * @param totalCount 조건에 맞는 전체 건수. 쪽을 더 넘길지 판단하는 값
 * @param pageNo     이 응답이 몇 쪽인지
 * @param numOfRows  이 응답에 담긴 건수
 * @param items      항목 목록. 각 항목이 응답 그대로임
 */
public record GoCampingListPage(
        int totalCount,
        int pageNo,
        int numOfRows,
        List<Map<String, Object>> items) {
}
