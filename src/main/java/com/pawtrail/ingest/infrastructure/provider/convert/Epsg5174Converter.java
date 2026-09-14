package com.pawtrail.ingest.infrastructure.provider.convert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * 행정안전부가 주는 평면 좌표를 위경도로 옮깁니다.
 *
 * 그 소스만 EPSG:5174 로 옵니다.
 * 보정계수가 들어가지 않은 Bessel 타원체 중부원점 횡메르카토르이며 단위가 미터입니다.
 * 값이 "199947.178" 같은 모양이라 위경도 자리에 그대로 넣으면 통째로 어긋납니다.
 *
 * 장소 서비스에 맡길 수 없습니다.
 * 그쪽은 받은 좌표가 대한민국 범위 안인지부터 봅니다.
 * 미터 단위 값은 그 범위에 들어가지 않아 쓸 수 없는 좌표로 판정되고,
 * 그러면 주소 지오코딩으로 넘어가 소스가 준 측량 좌표가 버려집니다.
 * 옮기는 일은 읽는 쪽의 몫입니다.
 *
 * 좌표계 정의를 문자열로 적습니다.
 * proj4j 코어는 EPSG 번호로 찾는 기능이 없고 그 데이터셋은 별도 아티팩트입니다.
 * 우리가 쓰는 좌표계가 하나뿐이라 데이터셋 전체를 이미지에 싣지 않습니다.
 *
 * 손으로 적은 값이 맞는지는 검증할 방법이 있습니다.
 * 착수 전에 pyproj 로 5,258 건을 옮겨 기존 장소와 거리를 재 두었고 중앙값이 3m 였습니다.
 * 같은 파일을 이 코드로 옮겨 그 값과 견주면 파라미터가 어긋났는지 바로 드러납니다.
 * 틀리면 거리가 통째로 벌어지므로 조용히 지나가지 않습니다.
 */
public final class Epsg5174Converter {

    // EPSG:5174 의 정의입니다
    //
    // towgs84 일곱 값이 Bessel 에서 WGS84 로 옮기는 변환 파라미터입니다.
    // 이 줄이 없으면 타원체 차이만큼 수백 미터가 어긋납니다.
    private static final String EPSG_5174 =
            "+proj=tmerc +lat_0=38 +lon_0=127.0028902777778 +k=1 +x_0=200000 +y_0=500000 "
                    + "+ellps=bessel +units=m +no_defs "
                    + "+towgs84=-145.907,505.034,685.756,-1.162,2.347,1.592,6.342";

    private static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";

    // 장소 서비스의 lat 과 lon 이 numeric(10,7) 입니다
    //
    // 받는 쪽도 같은 자릿수로 반올림하므로 여기서 미리 맞춰 둡니다.
    // 그래야 보낸 값과 저장된 값이 같아 나중에 대조할 때 흔들리지 않습니다.
    private static final int SCALE = 7;

    private static final CoordinateTransform TRANSFORM = createTransform();

    private Epsg5174Converter() {
    }

    private static CoordinateTransform createTransform() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem from = crsFactory.createFromParameters("EPSG:5174", EPSG_5174);
        CoordinateReferenceSystem to = crsFactory.createFromParameters("WGS84", WGS84);
        return new CoordinateTransformFactory().createTransform(from, to);
    }

    /**
     * 평면 좌표를 위경도로 옮깁니다.
     *
     * 옮기지 못하면 값이 비어 있는 결과를 돌려줍니다.
     * 그 건은 좌표 없이 보내고 받는 쪽이 주소로 지오코딩합니다.
     *
     * 값 뒤에 공백이 붙어 옵니다.
     * "199947.178659037    " 처럼 오므로 다듬지 않으면 숫자로 읽지 못합니다.
     */
    public static Result convert(String rawX, String rawY) {
        Double x = parse(rawX);
        Double y = parse(rawY);
        if (x == null || y == null) {
            return Result.empty();
        }

        ProjCoordinate source = new ProjCoordinate(x, y);
        ProjCoordinate target = new ProjCoordinate();
        try {
            TRANSFORM.transform(source, target);
        } catch (RuntimeException e) {
            // 옮기다 실패하는 경우입니다
            // 값이 좌표계 범위를 크게 벗어나면 여기로 옵니다
            return Result.empty();
        }

        if (Double.isNaN(target.x) || Double.isNaN(target.y)) {
            return Result.empty();
        }
        return Result.of(round(target.y), round(target.x));
    }

    /**
     * 자릿수를 맞춥니다. 버리지 않고 반올림합니다.
     *
     * PostgreSQL 이 numeric 에 넣을 때 하는 것이 반올림이라 같은 방식이어야
     * 다시 보내도 값이 흔들리지 않습니다.
     */
    private static String round(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static Double parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            // 숫자가 아닌 값은 없는 것과 같이 다룹니다
            // 부르는 쪽이 하는 일이 "좌표 없이 보낸다" 로 같기 때문입니다
            return null;
        }
    }

    /**
     * 옮긴 결과입니다. 문자열인 이유는 받는 쪽이 문자열로 받기 때문입니다.
     *
     * 실수로 넘기면 그 시점에 정밀도가 흔들립니다.
     */
    public record Result(boolean converted, String lat, String lon) {

        static Result of(String lat, String lon) {
            return new Result(true, lat, lon);
        }

        static Result empty() {
            return new Result(false, null, null);
        }
    }
}
