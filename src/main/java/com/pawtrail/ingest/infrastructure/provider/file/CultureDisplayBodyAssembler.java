package com.pawtrail.ingest.infrastructure.provider.file;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 문화정보원 행 하나를 사람이 읽는 본문으로 조립합니다.
 *
 * 형식 규칙은 앞의 두 소스와 같습니다.
 * 라벨을 대괄호로 감싸고, 값에 줄바꿈이 있으면 다음 줄부터 적고, 빈 값은 라벨째 뺍니다.
 * 화면에서 소스마다 다르게 보이면 안 되기 때문입니다.
 *
 * *이 규칙은 나중에 고치기 어렵습니다.
 *  RawDocument 는 내용 해시가 같으면 본문을 갈아끼우지 않고 그대로 둡니다.
 *  해시는 payload 에서만 뜨므로 조립 규칙만 바꾸면 이미 담긴 행이 옛 본문을 유지합니다.
 */
@Component
public class CultureDisplayBodyAssembler {

    /**
     * 이 소스가 값이 없을 때 쓰는 문자열입니다.
     *
     * 빈 칸이 아니라 이렇게 옵니다. 표시용 본문에서는 셋 다 빈 값으로 봅니다.
     * 화면에 「휴무일 정보없음」이 뜨면 안 됩니다.
     *
     * *원본에서는 바꾸지 않습니다.
     *  "없음" 이 진짜 값일 수 있기 때문입니다.
     *  애견 동반 추가 요금이 "없음" 이면 정보가 없다는 뜻이 아니라 요금이 없다는 뜻입니다.
     *  컬럼마다 미기입인지 실제 값인지 갈리고 그 판단은 장소 쪽이 컬럼별로 할 일입니다.
     */
    private static final Set<String> BLANK_MARKERS = Set.of("정보없음", "해당없음", "없음");

    /**
     * 동반 조건입니다. 판정의 근거라 맨 위에 둡니다.
     *
     * 예 또는 아니오로 오는 값을 그대로 담습니다.
     * 고캠핑에서는 같은 형태를 통째로 뺐는데 그쪽은 보험 가입 여부 같은 부수 정보였고,
     * 이쪽은 이 서비스가 보여주려는 값 그 자체입니다.
     *
     * 「가능」이나 「불가」로 바꾸지 않습니다.
     * 우리가 문장을 만드는 것이라 더는 원문이 아닙니다.
     * 사람이 읽기 좋은 화면은 장소 쪽이 정제한 값으로 따로 만듭니다.
     */
    private static final Map<String, String> CONDITION_LABELS = ordered(
            "반려동물 동반 가능정보", "반려동물 동반",
            "반려동물 전용 정보", "반려동물 전용",
            "입장 가능 동물 크기", "입장 가능 크기",
            "반려동물 제한사항", "제한사항",
            "애견 동반 추가 요금", "동반 추가 요금");

    /**
     * 사람이 쓴 설명입니다.
     */
    private static final Map<String, String> INTRO_LABELS = ordered(
            "기본 정보_장소설명", "장소 설명");

    /**
     * 언제 어떻게 이용하는지입니다.
     */
    private static final Map<String, String> USAGE_LABELS = ordered(
            "전화번호", "문의처",
            "운영시간", "운영시간",
            "휴무일", "휴무일",
            "입장(이용료)가격 정보", "이용료",
            "주차 가능여부", "주차");

    /**
     * 어떤 성격의 장소인지입니다.
     *
     * 카테고리1 은 담지 않습니다. 이 파일의 모든 행이 "반려동물업" 이라 뜻이 없습니다.
     */
    private static final Map<String, String> KIND_LABELS = ordered(
            "장소(실내) 여부", "실내",
            "장소(실외)여부", "실외",
            "카테고리2", "분류",
            "카테고리3", "세부 분류");

    /**
     * 본문에 담지 않는 컬럼입니다.
     *
     * 시설명은 표시용 제목으로 따로 갑니다.
     * 나머지는 좌표와 주소 조각과 우편번호와 홈페이지와 작성일로 기계가 쓰는 값입니다.
     *
     * 이 목록을 두는 이유는 사전에 없는 이름이 왔을 때 그것이 새로 생긴 컬럼인지
     * 일부러 뺀 것인지 갈라 보기 위함입니다.
     */
    private static final Set<String> EXCLUDED_COLUMNS = Set.of(
            "시설명", "카테고리1",
            "시도 명칭", "시군구 명칭", "법정읍면동명칭", "리 명칭", "번지",
            "도로명 이름", "건물 번호", "위도", "경도", "우편번호",
            "도로명주소", "지번주소", "홈페이지", "최종작성일");

    /**
     * 행 하나를 본문으로 만듭니다.
     *
     * 덩어리 사이는 빈 줄로 나눕니다.
     * 값이 하나도 없는 덩어리는 통째로 빠지므로 빈 줄만 남는 자리가 생기지 않습니다.
     *
     * @param row 컬럼 이름을 열쇠로 하는 행
     * @return 조립한 본문. 담을 것이 하나도 없으면 null
     */
    public String assemble(Map<String, String> row) {
        if (row == null) {
            return null;
        }

        List<String> blocks = new ArrayList<>();
        addBlock(blocks, fromDictionary(row, CONDITION_LABELS));
        addBlock(blocks, fromDictionary(row, INTRO_LABELS));
        addBlock(blocks, fromDictionary(row, USAGE_LABELS));
        addBlock(blocks, fromDictionary(row, KIND_LABELS));

        return blocks.isEmpty() ? null : String.join("\n\n", blocks);
    }

    /**
     * 사전에 적힌 순서대로 값을 꺼내 줄을 만듭니다.
     */
    private List<String> fromDictionary(Map<String, String> row, Map<String, String> labels) {
        List<String> lines = new ArrayList<>();
        labels.forEach((column, label) -> {
            String value = valueOf(row, column);
            if (value != null) {
                lines.add(line(label, value));
            }
        });
        return lines;
    }

    /**
     * 라벨과 값을 한 줄로 만듭니다.
     *
     * 값에 줄바꿈이 있으면 라벨 다음 줄부터 적고 없으면 같은 줄에 붙입니다.
     */
    private String line(String label, String value) {
        return value.contains("\n")
                ? "[" + label + "]\n" + value
                : "[" + label + "] " + value;
    }

    /**
     * 담을 값인지 보고 다듬어 돌려줍니다. 담을 것이 없으면 null 입니다.
     *
     * 빈 칸과 함께 "정보없음" "해당없음" "없음" 도 빈 값으로 봅니다.
     */
    private String valueOf(Map<String, String> row, String column) {
        String raw = row.get(column);
        if (raw == null) {
            return null;
        }
        String value = raw.replace("\r\n", "\n").strip();
        if (value.isEmpty() || BLANK_MARKERS.contains(value)) {
            return null;
        }
        return value;
    }

    private void addBlock(List<String> blocks, List<String> lines) {
        if (!lines.isEmpty()) {
            blocks.add(String.join("\n", lines));
        }
    }

    /**
     * 사전에도 제외 목록에도 없는 컬럼을 알려줍니다.
     *
     * 소스가 컬럼을 늘리면 그것이 조용히 빠지는데 아무 신호가 없으면 알아챌 방법이 없습니다.
     * 수집기가 처음 한 행을 읽을 때 한 번만 부릅니다.
     * 파일이 만 건이 넘어 행마다 확인하면 같은 경고가 그만큼 쌓입니다.
     */
    public List<String> unknownColumns(Map<String, String> row) {
        List<String> unknown = new ArrayList<>();
        for (String column : row.keySet()) {
            if (EXCLUDED_COLUMNS.contains(column) || isKnownLabel(column)) {
                continue;
            }
            unknown.add(column);
        }
        return unknown;
    }

    private boolean isKnownLabel(String column) {
        return CONDITION_LABELS.containsKey(column)
                || INTRO_LABELS.containsKey(column)
                || USAGE_LABELS.containsKey(column)
                || KIND_LABELS.containsKey(column);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
