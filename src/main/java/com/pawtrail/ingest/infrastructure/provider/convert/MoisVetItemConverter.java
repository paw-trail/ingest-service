package com.pawtrail.ingest.infrastructure.provider.convert;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.PlaceItemConverter;
import com.pawtrail.ingest.domain.provider.dto.PlaceBulkItem;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 행정안전부 동물병원 인허가를 장소 서비스가 받는 형태로 바꿉니다.
 *
 * 앞의 셋과 다른 점이 둘입니다.
 *
 * 원본을 거치지 않습니다.
 * 다른 변환기는 우리 표에 담아 둔 것을 읽지만 이것은 파일에서 읽은 행을 바로 받습니다.
 * 그래도 같은 인터페이스를 쓰는 이유는 하는 일이 같기 때문입니다.
 * 어느 키를 어느 칸에 넣을지를 정하는 일이고, 그것이 소스마다 다르다는 점도 같습니다.
 *
 * 좌표계를 옮깁니다.
 * 이 소스만 평면 좌표로 오므로 여기서 위경도로 바꿉니다. Epsg5174Converter 를 보십시오.
 *
 * 채우는 칸이 적습니다.
 * 인허가 대장이라 사진도 개요도 운영시간도 없습니다.
 * 그 값들은 문화정보원 쪽에 있고 받는 쪽이 빈 칸을 메우면서 합쳐집니다.
 */
@Component
public class MoisVetItemConverter implements PlaceItemConverter {

    private static final String NAME = "사업장명";
    private static final String ROAD = "도로명주소";
    private static final String JIBUN = "지번주소";
    private static final String TEL = "전화번호";
    private static final String COORD_X = "좌표정보(X)";
    private static final String COORD_Y = "좌표정보(Y)";

    // 지역번호가 붙어 있는 번호의 길이입니다
    //
    // 실측에서 여덟 자리와 아홉 자리가 511 건 나왔는데 지역번호가 빠진 것입니다.
    // "36728441" 은 서울 국번만 있어 그대로 걸 수 없습니다.
    // 소재지 시도로 앞자리를 유추하는 것은 추정이라 하지 않고 버립니다.
    //
    // 문화정보원이 전화를 96.7% 주고 형식도 정상이라 그쪽이 대부분을 채웁니다.
    // 받는 쪽이 빈 칸만 메우므로 이 값은 그쪽에 없는 병원에만 들어갑니다.
    private static final int MIN_TEL_LENGTH = 10;
    private static final int MAX_TEL_LENGTH = 11;

    @Override
    public SourceType source() {
        return SourceType.MOIS_VET;
    }

    @Override
    public PlaceBulkItem convert(String sourceId, Map<String, Object> payload) {
        String name = text(payload, NAME);
        if (name == null) {
            return null;
        }

        Epsg5174Converter.Result coordinate =
                Epsg5174Converter.convert(text(payload, COORD_X), text(payload, COORD_Y));

        return new PlaceBulkItem(
                SourceType.MOIS_VET,
                sourceId,
                name,
                text(payload, ROAD),
                text(payload, JIBUN),

                // 주소 첫 토큰이 언제나 시도라 폴백이 필요 없음
                null,

                coordinate.lat(),
                coordinate.lon(),

                // 옮기지 못한 건은 출처를 비움
                //
                // 받는 쪽이 좌표가 없으면 주소로 지오코딩하고 그때 출처를 스스로 정함
                // 여기서 CONVERTED 를 적어 두면 값이 없는데 출처만 있는 상태가 됨
                coordinate.converted() ? "CONVERTED" : null,

                // 분류 코드가 없는 소스임
                //
                // 받는 쪽이 소스만 보고 동물병원으로 정함
                // 고캠핑을 야영장으로 정하는 것과 같은 자리
                null,
                null,
                null,

                phone(text(payload, TEL)),

                // 인허가 대장이라 없는 값들
                null,
                null,
                null,
                null,
                null,
                null,
                null,

                // 기준일을 주지 않음
                //
                // 최종수정시점이 있으나 그 행을 마지막으로 고친 시각이지
                // 데이터가 언제 것인지를 뜻하지 않음
                null,

                // 편의시설 원본 넷도 없음
                null,
                null,
                null,
                null);
    }

    /**
     * 지역번호까지 갖춘 번호만 남깁니다.
     *
     * 하이픈을 넣지 않습니다.
     * 어디에 넣을지는 지역번호 자릿수를 알아야 정해지고 그것은 표시 형식입니다.
     * 화면이 정할 일이라 숫자 그대로 보냅니다.
     */
    private String phone(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < MIN_TEL_LENGTH || digits.length() > MAX_TEL_LENGTH) {
            return null;
        }
        return digits;
    }

    /**
     * 값을 문자열로 꺼냅니다. 비어 있으면 null 입니다.
     *
     * 파일에서 읽은 것이라 값이 전부 문자열입니다.
     * 앞뒤 공백이 붙어 오는 열이 있어 다듬습니다.
     */
    private String text(Map<String, Object> payload, String column) {
        Object value = payload.get(column);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
