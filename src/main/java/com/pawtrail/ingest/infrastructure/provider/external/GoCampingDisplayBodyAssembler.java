package com.pawtrail.ingest.infrastructure.provider.external;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 고캠핑 응답을 사람이 읽는 본문으로 조립합니다.
 *
 * 이 값은 장소 상세의 「근거문서 원문보기」가 그대로 보여주는 것입니다.
 * 원본 자체는 payload 에 응답 그대로 들어가 있고 이쪽은 표시용 형태입니다.
 *
 * 형식 규칙은 반려동물 동반여행 쪽과 같습니다.
 * 라벨을 대괄호로 감싸고, 값에 줄바꿈이 있으면 다음 줄부터 적고, 빈 값은 라벨째 뺍니다.
 * 화면에서 두 소스가 다르게 보이면 안 되기 때문입니다.
 *
 * *이 규칙은 나중에 고치기 어렵습니다.
 *  RawDocument 는 내용 해시가 같으면 본문을 갈아끼우지 않고 그대로 둡니다.
 *  해시는 payload 에서만 뜨므로 조립 규칙만 바꾸면 이미 담긴 행이 옛 본문을 유지합니다.
 */
@Slf4j
@Component
public class GoCampingDisplayBodyAssembler {

    /**
     * 동반 조건입니다. 판정의 근거라 맨 위에 둡니다.
     *
     * 값이 네 갈래로 옵니다. 가능, 가능(소형견), 불가능, 그리고 빈 값입니다.
     * 빈 값도 뜻이 있습니다. 판정을 네 단계로 둔 이유가 정보 없음이라는 칸을 두기 위함이라
     * 소스가 알려 주지 않았다는 사실 자체가 재료입니다.
     * 다만 표시용 본문에서는 다른 빈 값과 같이 라벨째 빠집니다.
     */
    private static final Map<String, String> CONDITION_LABELS = ordered(
            "animalCmgCl", "반려동물 동반");

    /**
     * 소개 문구입니다. 사람이 쓴 자유 텍스트라 판정과 무관해 보여도 남깁니다.
     *
     * 잘라내지 않았다는 것이 원문의 존재 이유입니다.
     */
    private static final Map<String, String> INTRO_LABELS = ordered(
            "lineIntro", "한 줄 소개",
            "intro", "소개",
            "featureNm", "특징",
            "tooltip", "안내");

    /**
     * 언제 어떻게 이용하는지입니다.
     *
     * 문의처를 여기 둡니다.
     * 반려동물 동반여행 쪽도 소개 정보의 문의처를 같은 라벨로 담고 있어,
     * 빠뜨리면 화면에서 두 소스가 다르게 보입니다.
     * 채움률은 예순두 퍼센트입니다.
     */
    private static final Map<String, String> USAGE_LABELS = ordered(
            "tel", "문의처",
            "operPdCl", "운영 기간",
            "operDeCl", "운영 요일",
            "resveCl", "예약",
            "brazierCl", "화로대",
            "lctCl", "입지");

    /**
     * 시설입니다. 값이 쉼표로 이어진 다중값으로 옵니다.
     *
     * 쪼개지 않고 원문 그대로 담습니다.
     * 쪼개어 시설 코드로 만드는 것은 장소 쪽이 할 일이고,
     * 원문보기는 소스가 준 문장을 그대로 보여주는 자리입니다.
     */
    private static final Map<String, String> FACILITY_LABELS = ordered(
            "sbrsCl", "부대시설",
            "sbrsEtc", "기타 부대시설",
            "posblFcltyCl", "주변 이용시설",
            "posblFcltyEtc", "기타 주변시설",
            "themaEnvrnCl", "테마 환경",
            "eqpmnLendCl", "대여 장비",
            "glampInnerFclty", "글램핑 내부시설",
            "caravInnerFclty", "카라반 내부시설");

    /**
     * 그 밖의 안내입니다.
     */
    private static final Map<String, String> EXTRA_LABELS = ordered(
            "clturEvent", "문화행사",
            "exprnProgrm", "체험 프로그램",
            "induty", "업종",
            "facltDivNm", "시설 구분",
            "mangeDivNm", "관리 형태",
            "direction", "찾아오는 길");

    /**
     * 본문에 담지 않는 이름입니다.
     *
     * 세 부류입니다.
     *
     * 첫째는 개인정보입니다.
     * mgcDiv 는 항목명이 관리기관구분인데 실제 값이 관리자 개인 이름입니다.
     * 운영 중인 이천구백구십삼 건에서 고유값이 천백사십칠 개이고 대부분이 사람 이름입니다.
     * 이 값이 주소나 전화번호와 같은 줄에 놓이면 개인을 식별하게 되는데,
     * 원문보기는 관리자 전용이 아니라 사용자가 여는 화면입니다.
     * 운영 주체를 알리는 일은 facltDivNm 과 mangeDivNm 이 대신하며 채움률도 그쪽이 높습니다.
     * bizrno 와 trsagntNo 는 법인과 인허가 번호라 개인정보는 아니지만 자연어가 아닙니다.
     *
     * 둘째는 수량입니다.
     * 사이트 수나 화장실 수처럼 숫자만 들어 있어 문장으로 읽히지 않습니다.
     * 게다가 값이 영일 때 실제로 없는 것인지 적지 않은 것인지 구분할 수 없어
     * 값 자체를 믿기 어렵습니다.
     *
     * 셋째는 기계가 쓰는 값입니다. 좌표와 주소와 이미지 주소와 시각입니다.
     *
     * 수량 필드를 값으로 가려내지 않고 이름으로 적어 두는 이유는
     * 값이 영이나 일이면 뺀다고 규칙을 세우면 그렇지 않은 필드까지 함께 걸리기 때문입니다.
     * 무엇이 빠질지가 그날 받은 데이터에 따라 달라지면 결과를 예측할 수 없습니다.
     */
    private static final Set<String> EXCLUDED_FIELDS = Set.of(
            // 개인정보와 식별번호
            "mgcDiv", "bizrno", "trsagntNo",

            // 예 또는 아니오
            "insrncAt", "trlerAcmpnyAt", "caravAcmpnyAt", "clturEventAt", "exprnProgrmAt",

            // 수량
            "allar", "manageNmpr", "gnrlSiteCo", "autoSiteCo", "glampSiteCo",
            "caravSiteCo", "indvdlCaravSiteCo", "sitedStnc",
            "siteMg1Width", "siteMg2Width", "siteMg3Width",
            "siteMg1Vrticl", "siteMg2Vrticl", "siteMg3Vrticl",
            "siteMg1Co", "siteMg2Co", "siteMg3Co",
            "siteBottomCl1", "siteBottomCl2", "siteBottomCl3", "siteBottomCl4", "siteBottomCl5",
            "toiletCo", "swrmCo", "wtrplCo", "extshrCo",
            "frprvtWrppCo", "frprvtSandCo", "fireSensorCo",

            // 기계가 쓰는 값
            "contentId", "facltNm", "manageSttus", "mapX", "mapY", "zipcode",
            "addr1", "addr2", "doNm", "sigunguNm", "homepage", "resveUrl", "firstImageUrl",
            "createdtime", "modifiedtime", "prmisnDe", "hvofBgnde", "hvofEnddle", "tourEraCl");

    /**
     * 사전에도 제외 목록에도 없는 이름을 이미 알렸는지 기억합니다.
     *
     * 만날 때마다 남기면 한 실행에 수천 줄이 쌓여 로그가 그것으로 덮입니다.
     * 이름마다 한 번만 남기면 무엇이 새로 생겼는지는 드러나면서 양이 늘지 않습니다.
     */
    private final Set<String> reportedUnknownFields = ConcurrentHashMap.newKeySet();

    /**
     * 항목 하나를 본문으로 만듭니다.
     *
     * 덩어리 사이는 빈 줄로 나눕니다.
     * 값이 하나도 없는 덩어리는 통째로 빠지므로 빈 줄만 남는 자리가 생기지 않습니다.
     *
     * @param item 목록 응답의 항목 하나
     * @return 조립한 본문. 담을 것이 하나도 없으면 null
     */
    public String assemble(Map<String, Object> item) {
        if (item == null) {
            return null;
        }

        List<String> blocks = new ArrayList<>();
        addBlock(blocks, fromDictionary(item, CONDITION_LABELS));
        addBlock(blocks, fromDictionary(item, INTRO_LABELS));
        addBlock(blocks, fromDictionary(item, USAGE_LABELS));
        addBlock(blocks, fromDictionary(item, FACILITY_LABELS));
        addBlock(blocks, fromDictionary(item, EXTRA_LABELS));

        reportUnknownFields(item);

        return blocks.isEmpty() ? null : String.join("\n\n", blocks);
    }

    /**
     * 사전에 적힌 순서대로 값을 꺼내 줄을 만듭니다.
     */
    private List<String> fromDictionary(Map<String, Object> item, Map<String, String> labels) {
        List<String> lines = new ArrayList<>();
        labels.forEach((field, label) -> {
            String value = valueOf(item, field);
            if (value != null) {
                lines.add(line(label, value));
            }
        });
        return lines;
    }

    /**
     * 사전에도 제외 목록에도 없는 이름을 로그로 남깁니다.
     *
     * 소스가 필드를 늘리면 그것이 조용히 빠지는데, 아무 신호가 없으면 알아챌 방법이 없습니다.
     * 담지는 않습니다. 라벨이 없으면 영문 이름이 그대로 화면에 뜨는데
     * 그것은 사람이 읽을 수 있는 형태가 아닙니다.
     */
    private void reportUnknownFields(Map<String, Object> item) {
        for (String field : item.keySet()) {
            if (EXCLUDED_FIELDS.contains(field) || isKnownLabel(field)) {
                continue;
            }
            if (valueOf(item, field) == null) {
                continue;
            }
            if (reportedUnknownFields.add(field)) {
                log.warn("고캠핑 응답에 사전에 없는 이름이 왔습니다. field={}", field);
            }
        }
    }

    private boolean isKnownLabel(String field) {
        return CONDITION_LABELS.containsKey(field)
                || INTRO_LABELS.containsKey(field)
                || USAGE_LABELS.containsKey(field)
                || FACILITY_LABELS.containsKey(field)
                || EXTRA_LABELS.containsKey(field);
    }

    /**
     * 라벨과 값을 한 줄로 만듭니다.
     *
     * 값에 줄바꿈이 있으면 라벨 다음 줄부터 적고 없으면 같은 줄에 붙입니다.
     * 길이로 자르지 않는 이유는 그 기준이 근거 없는 숫자가 되기 때문입니다.
     */
    private String line(String label, String value) {
        return value.contains("\n")
                ? "[" + label + "]\n" + value
                : "[" + label + "] " + value;
    }

    /**
     * 담을 값인지 보고 다듬어 돌려줍니다. 담을 것이 없으면 null 입니다.
     *
     * 이 소스는 값이 없을 때 빈 문자열로 줍니다.
     * 원본을 정규화할 때 빈 문자열을 null 로 바꾸지만 그 일은 저장하는 쪽에서 일어나므로,
     * 조립하는 이 시점에는 아직 빈 문자열입니다.
     *
     * 앞뒤 공백은 털어 냅니다. 주소를 비롯한 몇 필드에 끝 공백이 붙어 옵니다.
     * 안쪽 줄바꿈은 그대로 둡니다. 소개 문구가 여러 줄로 오는 경우가 있습니다.
     */
    private String valueOf(Map<String, Object> item, String field) {
        Object raw = item.get(field);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).replace("\r\n", "\n").strip();
        return value.isEmpty() ? null : value;
    }

    private void addBlock(List<String> blocks, List<String> lines) {
        if (!lines.isEmpty()) {
            blocks.add(String.join("\n", lines));
        }
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
