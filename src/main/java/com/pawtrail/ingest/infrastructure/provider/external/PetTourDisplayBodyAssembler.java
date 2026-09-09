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
 * 상세 응답 셋을 사람이 읽는 본문으로 조립합니다.
 *
 * 이 값은 장소 상세의 「근거문서 원문보기」가 그대로 보여주는 것입니다.
 * 원본 자체는 payload 에 응답 그대로 들어가 있고 이쪽은 표시용 형태입니다.
 * 좌표와 코드와 타임스탬프처럼 기계가 쓰는 값을 빼고 자연어만 남깁니다.
 * 판정과 무관해 보이는 문장은 잘라내지 않습니다.
 * 잘라내지 않았다는 것이 원문의 존재 이유입니다.
 *
 * *이 규칙은 나중에 고치기 어렵습니다.
 *  RawDocument 는 내용 해시가 같으면 본문을 갈아끼우지 않고 그대로 둡니다.
 *  해시는 payload 에서만 뜨므로, 조립 규칙만 바꾸면 해시가 그대로여서
 *  이미 담긴 행이 옛 본문을 유지합니다.
 *  증상이 "코드를 고쳤는데 화면이 그대로" 라 원인이 잘 드러나지 않습니다.
 *  규칙을 바꿔야 할 일이 생기면 다시 조립해 넣는 경로가 함께 필요합니다.
 */
@Slf4j
@Component
public class PetTourDisplayBodyAssembler {

    /**
     * 동반 조건입니다. 판정의 근거라 맨 위에 둡니다.
     *
     * 개요보다 앞에 두는 이유가 있습니다.
     * 이 화면을 여는 사람은 "왜 이렇게 판정됐는지" 를 보러 오는데
     * 개요가 수백 자여서 위에 두면 정작 근거를 보려고 한참 내려야 합니다.
     */
    private static final Map<String, String> CONDITION_LABELS = ordered(
            "acmpyTypeCd", "동반 유형",
            "acmpyPsblCpam", "동반 가능 반려동물",
            "acmpyNeedMtr", "동반 필수 준비물",
            "etcAcmpyInfo", "기타 동반 안내");

    /**
     * 반려동물과 관련된 그 밖의 안내입니다.
     *
     * 뒤 넷은 채움률이 낮습니다. 2026년 7월 표본에서 앞 둘이 4퍼센트였고 뒤 둘은 0이었습니다.
     * 그래도 담습니다. 비어 있으면 라벨째 빠져 출력에 아무 영향이 없고,
     * 소스가 나중에 채우기 시작하면 코드를 고치지 않아도 담깁니다.
     */
    private static final Map<String, String> RELATED_LABELS = ordered(
            "relaAcdntRiskMtr", "관련 사고 대비사항",
            "relaPosesFclty", "관련 보유 시설",
            "relaFrnshPrdlst", "비치 품목",
            "relaPurcPrdlst", "구매 가능 품목",
            "relaRntlPrdlst", "대여 가능 품목");

    /**
     * 소개 정보의 라벨입니다.
     *
     * 타입별로 표를 나누지 않고 하나로 둡니다.
     * 소스가 타입마다 접미사를 붙여 이름을 다르게 주기 때문에 겹치는 이름이 없습니다.
     * 관광지의 문의처가 infocenter 이고 문화시설은 infocenterculture 인 식입니다.
     *
     * 하나로 두면 타입을 몰라도 응답을 그대로 훑을 수 있고,
     * 우리가 아는 여섯 타입 밖의 것이 들어와도 같은 경로를 탑니다.
     * 타입별 표를 박아 두면 그때 그 타입은 이 문단이 통째로 비는데 아무 신호도 나지 않습니다.
     *
     * 2026년 9월 9일에 여섯 타입을 한 건씩 불러 확인한 이름입니다.
     * 값이 비어 있던 것도 함께 담아 두었습니다.
     */
    private static final Map<String, String> INTRO_LABELS = introLabels();

    /**
     * 본문에 담지 않는 이름입니다.
     *
     * 숫자 플래그를 값으로 가려내지 않고 이름으로 적어 두는 이유가 있습니다.
     * 값이 0 이나 1 이면 뺀다고 규칙을 세우면 플래그가 아닌 필드까지 함께 걸립니다.
     * 객실 수나 좌석 수가 0 으로 오는 행이 있고 그것은 사람이 봐야 할 값입니다.
     * 무엇이 빠질지가 그날 받은 데이터에 따라 달라지면 결과를 예측할 수 없습니다.
     * 이름으로 적어 두면 언제나 같은 것이 빠집니다.
     *
     * 있음과 없음으로 바꿔 넣지도 않습니다.
     * 그것은 우리가 문장을 만드는 것이라 더는 원문이 아닙니다.
     *
     * 시설 정보 자체가 필요하면 payload 에 온 그대로 들어 있으므로
     * 장소 쪽에서 뽑아 시설 코드로 담으면 됩니다. 여기서 잃는 것은 없습니다.
     */
    private static final Set<String> EXCLUDED_FIELDS = Set.of(
            // 식별자와 코드
            "contentid", "contenttypeid", "reservationurl",

            // 관광지 세계유산 여부
            "heritage1", "heritage2", "heritage3",

            // 음식점 어린이 놀이방
            "kidsfacility",

            // 숙박 부대시설
            "seminar", "sports", "sauna", "beauty", "beverage", "karaoke",
            "barbecue", "campfire", "bicycle", "fitness", "publicpc", "publicbath");

    /**
     * 사전에 없는 이름을 이미 알렸는지 기억합니다.
     *
     * 없는 이름을 만날 때마다 남기면 한 실행에 천 번이 넘게 쌓여 로그가 그것으로 덮입니다.
     * 이름마다 한 번만 남기면 무엇이 새로 생겼는지는 그대로 드러나면서 양이 늘지 않습니다.
     */
    private final Set<String> reportedUnknownFields = ConcurrentHashMap.newKeySet();

    /**
     * 상세 셋을 묶어 본문을 만듭니다.
     *
     * 덩어리 사이는 빈 줄로 나눕니다.
     * 값이 하나도 없는 덩어리는 통째로 빠지므로 빈 줄만 남는 자리가 생기지 않습니다.
     *
     * @param petTour 동반 조건 응답. 항목이 없으면 빈 지도
     * @param common  공통 정보 응답. 항목이 없으면 빈 지도
     * @param intro   소개 정보 응답. 항목이 없으면 빈 지도
     * @return 조립한 본문. 담을 것이 하나도 없으면 null
     */
    public String assemble(
            Map<String, Object> petTour,
            Map<String, Object> common,
            Map<String, Object> intro) {

        List<String> blocks = new ArrayList<>();

        addBlock(blocks, fromDictionary(petTour, CONDITION_LABELS));
        addBlock(blocks, fromDictionary(petTour, RELATED_LABELS));
        addBlock(blocks, fromDictionary(common, ordered("overview", "개요")));
        addBlock(blocks, fromIntro(intro));

        return blocks.isEmpty() ? null : String.join("\n\n", blocks);
    }

    /**
     * 사전에 적힌 순서대로 값을 꺼내 줄을 만듭니다.
     */
    private List<String> fromDictionary(Map<String, Object> source, Map<String, String> labels) {
        List<String> lines = new ArrayList<>();
        if (source == null) {
            return lines;
        }
        labels.forEach((field, label) -> {
            String value = valueOf(source, field);
            if (value != null) {
                lines.add(line(label, value));
            }
        });
        return lines;
    }

    /**
     * 소개 정보는 응답에 온 순서대로 훑습니다.
     *
     * 사전 순서가 아니라 응답 순서를 따르는 이유는 소스가 읽기 좋은 차례로 주기 때문입니다.
     * 문의처와 이용시간과 주차가 대체로 그 순서로 옵니다.
     *
     * 사전에 없는 이름은 담지 않습니다.
     * 라벨이 없으면 영문 이름이 그대로 화면에 뜨는데 그것은 사람이 읽을 수 있는 형태가 아닙니다.
     * 대신 무엇이 왔는지 로그로 남겨 다음에 사전을 채울 수 있게 합니다.
     */
    private List<String> fromIntro(Map<String, Object> intro) {
        List<String> lines = new ArrayList<>();
        if (intro == null) {
            return lines;
        }
        for (String field : intro.keySet()) {
            if (EXCLUDED_FIELDS.contains(field)) {
                continue;
            }
            String value = valueOf(intro, field);
            if (value == null) {
                continue;
            }
            String label = INTRO_LABELS.get(field);
            if (label == null) {
                reportUnknown(field, intro);
                continue;
            }
            lines.add(line(label, value));
        }
        return lines;
    }

    /**
     * 라벨과 값을 한 줄로 만듭니다.
     *
     * 값에 줄바꿈이 있으면 라벨 다음 줄부터 적고 없으면 같은 줄에 붙입니다.
     * 길이로 자르지 않는 이유는 그 기준이 근거 없는 숫자가 되기 때문입니다.
     * 개요와 기타 동반 안내가 줄바꿈을 갖고 있어 자연히 다음 줄로 갑니다.
     *
     * 값 안에도 대괄호가 들어옵니다. 쇼핑 영업시간이 그렇습니다.
     * 라벨은 언제나 줄 맨 앞에 오고 값은 그 뒤라 자리로 구분됩니다.
     */
    private String line(String label, String value) {
        return value.contains("\n")
                ? "[" + label + "]\n" + value
                : "[" + label + "] " + value;
    }

    /**
     * 담을 값인지 보고 다듬어 돌려줍니다. 담을 것이 없으면 null 입니다.
     *
     * 이 소스는 값이 없을 때 null 이 아니라 빈 문자열로 줍니다.
     * 원본을 정규화할 때 빈 문자열을 null 로 바꾸지만 그 일은 저장하는 쪽에서 일어나므로,
     * 조립하는 이 시점에는 아직 빈 문자열입니다. 그래서 여기서 따로 걸러야 합니다.
     *
     * 앞뒤 공백은 털어 냅니다. 안쪽 줄바꿈은 그대로 둡니다.
     * 기타 동반 안내가 줄바꿈으로 항목을 나누어 오는데 그것이 곧 문서의 짜임입니다.
     */
    private String valueOf(Map<String, Object> source, String field) {
        Object raw = source.get(field);
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

    private void reportUnknown(String field, Map<String, Object> intro) {
        if (reportedUnknownFields.add(field)) {
            log.warn("소개 정보에 사전에 없는 이름이 왔습니다. field={} contentTypeId={}",
                    field, intro.get("contenttypeid"));
        }
    }

    /**
     * 이름과 라벨을 짝지어 순서를 지킨 사전을 만듭니다.
     */
    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> introLabels() {
        Map<String, String> map = new LinkedHashMap<>();

        // 12 관광지
        map.put("infocenter", "문의처");
        map.put("restdate", "휴무일");
        map.put("usetime", "이용시간");
        map.put("parking", "주차");
        map.put("opendate", "개장일");
        map.put("expguide", "체험 안내");
        map.put("expagerange", "체험 가능 연령");
        map.put("accomcount", "수용 인원");
        map.put("useseason", "이용 시기");
        map.put("chkbabycarriage", "유모차 대여");
        map.put("chkpet", "반려동물 동반");
        map.put("chkcreditcard", "신용카드");

        // 14 문화시설
        map.put("infocenterculture", "문의처");
        map.put("restdateculture", "휴무일");
        map.put("usetimeculture", "이용시간");
        map.put("parkingculture", "주차");
        map.put("parkingfee", "주차요금");
        map.put("usefee", "이용요금");
        map.put("discountinfo", "할인 정보");
        map.put("spendtime", "관람 소요시간");
        map.put("scale", "규모");
        map.put("accomcountculture", "수용 인원");
        map.put("chkbabycarriageculture", "유모차 대여");
        map.put("chkpetculture", "반려동물 동반");
        map.put("chkcreditcardculture", "신용카드");

        // 28 레포츠
        map.put("infocenterleports", "문의처");
        map.put("restdateleports", "휴무일");
        map.put("usetimeleports", "이용시간");
        map.put("usefeeleports", "이용요금");
        map.put("parkingleports", "주차");
        map.put("parkingfeeleports", "주차요금");
        map.put("openperiod", "개장 기간");
        map.put("reservation", "예약 안내");
        map.put("scaleleports", "규모");
        map.put("accomcountleports", "수용 인원");
        map.put("expagerangeleports", "체험 가능 연령");
        map.put("chkbabycarriageleports", "유모차 대여");
        map.put("chkpetleports", "반려동물 동반");
        map.put("chkcreditcardleports", "신용카드");

        // 32 숙박
        map.put("infocenterlodging", "문의처");
        map.put("checkintime", "입실 시각");
        map.put("checkouttime", "퇴실 시각");
        map.put("roomcount", "객실 수");
        map.put("roomtype", "객실 유형");
        map.put("scalelodging", "규모");
        map.put("accomcountlodging", "수용 인원");
        map.put("parkinglodging", "주차");
        map.put("reservationlodging", "예약 안내");
        map.put("refundregulation", "환불 규정");
        map.put("chkcooking", "취사");
        map.put("subfacility", "부대시설");
        map.put("foodplace", "식음료장");
        map.put("pickup", "픽업 서비스");

        // 38 쇼핑
        map.put("infocentershopping", "문의처");
        map.put("restdateshopping", "휴무일");
        map.put("opentime", "영업시간");
        map.put("parkingshopping", "주차");
        map.put("saleitem", "판매 품목");
        map.put("saleitemcost", "판매 품목 가격");
        map.put("restroom", "화장실");
        map.put("fairday", "장서는 날");
        map.put("opendateshopping", "개장일");
        map.put("shopguide", "매장 안내");
        map.put("culturecenter", "문화센터");
        map.put("scaleshopping", "규모");
        map.put("chkbabycarriageshopping", "유모차 대여");
        map.put("chkpetshopping", "반려동물 동반");
        map.put("chkcreditcardshopping", "신용카드");

        // 39 음식점
        map.put("infocenterfood", "문의처");
        map.put("restdatefood", "휴무일");
        map.put("opentimefood", "영업시간");
        map.put("parkingfood", "주차");
        map.put("firstmenu", "대표 메뉴");
        map.put("treatmenu", "취급 메뉴");
        map.put("packing", "포장");
        map.put("seat", "좌석 수");
        map.put("smoking", "흡연");
        map.put("scalefood", "규모");
        map.put("opendatefood", "개업일");
        map.put("discountinfofood", "할인 정보");
        map.put("reservationfood", "예약 안내");
        map.put("chkcreditcardfood", "신용카드");
        map.put("lcnsno", "인허가번호");

        return Collections.unmodifiableMap(map);
    }
}
