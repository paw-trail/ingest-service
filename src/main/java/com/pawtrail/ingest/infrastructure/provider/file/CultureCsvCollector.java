package com.pawtrail.ingest.infrastructure.provider.file;

import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.SourceCollector;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 한국문화정보원 문화시설 CSV 를 수집합니다.
 *
 * 앞의 두 소스와 크게 다릅니다. 바깥을 한 번도 부르지 않습니다.
 * 소스가 저장소에 함께 커밋한 파일이라 호출 허용량도 재시도도 재개 지점도 없습니다.
 *
 * <pre>
 * 파일을 읽어 ─→ 완전 중복 제거 ─→ 대상 필터 ─→ 같은 키는 최신만 ─→ 청크로 저장
 *   70,650        23,980        13,436        13,408
 * </pre>
 *
 * 거르는 단계가 셋인 이유가 있습니다.
 *
 * 첫째는 전 컬럼이 똑같은 행이 46,670개 있기 때문입니다.
 * 우리동물병원 마흔 곳이 각각 마흔 번씩 복제된 식이며 좌표까지 같습니다.
 *
 * 둘째는 담을 것을 고르기 때문입니다.
 * 동물약국과 미용실과 위탁관리는 담지 않습니다. 화면에 그 카테고리가 없습니다.
 *
 * 셋째가 이번에 파일을 열다 찾은 것입니다.
 * 같은 장소의 2022년 판과 2025년 판이 둘 다 남아 있고 작성일만 다릅니다.
 * 전 컬럼 동일 판정에 걸리지 않아 첫 단계를 그냥 지나갑니다.
 * 지우지 않으면 둘이 같은 식별자라 뒤에 오는 행이 앞을 덮어쓰는데,
 * 그러면 어느 판이 남을지가 파일에 적힌 순서에 달립니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CultureCsvCollector implements SourceCollector {

    /**
     * 담을 세부 분류입니다.
     *
     * 동물약국 8,449 · 미용 2,034 · 위탁관리 61 은 담지 않습니다.
     * 사람이 여행길에 들르는 곳이 아니고, 화면에 그 카테고리를 보여줄 자리가 없습니다.
     * 약국만 해도 검색 색인이 크게 부풀지만 그것을 부르는 화면이 하나도 없습니다.
     */
    private static final Set<String> TARGET_CATEGORIES = Set.of(
            "여행지", "박물관", "미술관", "문예회관", "펜션", "호텔", "카페", "식당",
            "반려문화시설", "동물병원", "반려동물용품");

    /**
     * 세부 분류가 비어 있을 때 대신 보는 컬럼입니다.
     */
    private static final String CATEGORY_COLUMN = "카테고리3";
    private static final String FALLBACK_CATEGORY_COLUMN = "카테고리2";

    private static final String NAME_COLUMN = "시설명";
    private static final String ADDRESS_COLUMN = "지번주소";
    private static final String WRITTEN_AT_COLUMN = "최종작성일";

    /**
     * 식별자를 만들 때 이름과 주소 사이에 두는 문자입니다.
     *
     * 2026년 9월 9일 실측에서 두 컬럼 어디에도 이 문자가 나오지 않았습니다.
     */
    private static final String KEY_SEPARATOR = "|";

    private static final DateTimeFormatter WRITTEN_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CultureCsvReader reader;
    private final CultureDisplayBodyAssembler assembler;
    private final IngestProperties properties;

    @Override
    public SourceType source() {
        return SourceType.CULTURE_CSV;
    }

    @Override
    public void collect(CollectionContext context, Consumer<List<RawDocumentDraft>> chunkSink) {
        // 진행 기록을 남기지 않습니다.
        //
        // 그 값은 오퍼레이션마다 호출 허용량을 얼마나 썼는지를 담는 자리인데
        // 이 소스는 바깥을 부르지 않아 셀 것이 없습니다.
        // 파일 읽기를 지어낸 이름으로 세면 그 값의 뜻이 깨집니다.
        // 얼마나 담았는지는 실행 기록의 건수 두 개가 이미 보여줍니다.

        List<Map<String, String>> rows = reader.read(Path.of(properties.culture().filePath()));
        reportUnknownColumns(rows);

        List<Map<String, String>> distinct = removeExactDuplicates(rows);
        List<Map<String, String>> targets = filterTargets(distinct);
        Map<String, Map<String, String>> latest = keepLatestPerKey(targets);

        log.info("거르기를 마쳤습니다. 원본={} 완전중복제거={} 대상={} 최신만={}",
                rows.size(), distinct.size(), targets.size(), latest.size());

        List<RawDocumentDraft> chunk = new ArrayList<>();
        latest.forEach((key, row) -> {
            chunk.add(toDraft(row, key));
            if (chunk.size() >= properties.chunkSize()) {
                flush(chunk, chunkSink);
            }
        });
        flush(chunk, chunkSink);

        log.info("문화정보원 수집을 마쳤습니다. 담은 건수={}", latest.size());
    }

    /**
     * 전 컬럼이 똑같은 행을 걷어냅니다.
     *
     * 파일에 46,670개가 있습니다.
     * 좌표까지 같은 행이 반복되므로 지우지 않으면 같은 장소가 수십 번 들어갑니다.
     * 순서는 파일에 적힌 그대로 지킵니다. 먼저 나온 것을 남깁니다.
     */
    private List<Map<String, String>> removeExactDuplicates(List<Map<String, String>> rows) {
        Set<List<String>> seen = new LinkedHashSet<>();
        List<Map<String, String>> distinct = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (seen.add(List.copyOf(row.values()))) {
                distinct.add(row);
            }
        }
        return distinct;
    }

    /**
     * 담을 세부 분류만 남깁니다.
     */
    private List<Map<String, String>> filterTargets(List<Map<String, String>> rows) {
        List<Map<String, String>> targets = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (TARGET_CATEGORIES.contains(categoryOf(row))) {
                targets.add(row);
            }
        }
        return targets;
    }

    /**
     * 같은 식별자에서 작성일이 가장 늦은 행만 남깁니다.
     *
     * 작성일이 연월일 형식이라 문자열로 견주어도 순서가 맞습니다.
     *
     * 작성일까지 같은 경우가 한 건 있습니다.
     * 안산의 한 동물병원이 반려동물용품과 동물병원 두 분류로 등록돼 있습니다.
     * 어느 쪽이 남아도 같은 장소이고 분류는 원본에 들어 있어 그대로 둡니다.
     *
     * 순서를 지키는 지도를 씁니다.
     * 저장하는 차례가 실행마다 달라지면 무엇이 바뀌었는지 세는 값도 함께 흔들립니다.
     */
    private Map<String, Map<String, String>> keepLatestPerKey(List<Map<String, String>> rows) {
        Map<String, Map<String, String>> latest = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String key = sourceIdOf(row);
            Map<String, String> kept = latest.get(key);
            if (kept == null || writtenAtOf(row).compareTo(writtenAtOf(kept)) >= 0) {
                latest.put(key, row);
            }
        }
        return latest;
    }

    /**
     * 저장할 형태로 옮깁니다.
     *
     * 원본은 행을 열쇠 아래에 그대로 둡니다.
     * 이 소스는 응답이 하나뿐이라 평평하게 담을 수도 있으나 앞의 두 소스와 모양을 맞춥니다.
     * 그래야 원본을 읽는 쪽이 소스마다 진입점을 기억하지 않아도 됩니다.
     *
     * 값이 없을 때 쓰는 문자열도 바꾸지 않고 그대로 담습니다.
     * 어느 컬럼에서 그것이 미기입이고 어느 컬럼에서 실제 값인지는 장소 쪽이 판단합니다.
     */
    private RawDocumentDraft toDraft(Map<String, String> row, String sourceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", row);

        return new RawDocumentDraft(
                SourceType.CULTURE_CSV,
                sourceId,
                payload,
                value(row, NAME_COLUMN),
                assembler.assemble(row),
                parseWrittenAt(value(row, WRITTEN_AT_COLUMN)));
    }

    /**
     * 식별자를 만듭니다.
     *
     * 이 소스에는 식별자 컬럼이 하나도 없습니다. 서른한 개가 전부 내용입니다.
     * 그래서 이름과 주소를 이어 만듭니다. 둘 다 빠짐없이 채워져 있습니다.
     *
     * 행 번호를 쓰면 안 됩니다.
     * 파일이 갱신되면 순서가 바뀌어 같은 번호가 다른 장소를 가리키고,
     * 그러면 저장할 때 엉뚱한 행을 덮어씁니다.
     *
     * 해시로 줄이지도 않습니다.
     * 길이는 고정되지만 이 값을 사람이 읽을 수 없게 됩니다.
     * 원본은 관리자 전용이 아니라 사용자가 여는 표이고 조회 경로도 따로 만들 예정입니다.
     */
    private String sourceIdOf(Map<String, String> row) {
        return value(row, NAME_COLUMN) + KEY_SEPARATOR + value(row, ADDRESS_COLUMN);
    }

    private String categoryOf(Map<String, String> row) {
        String category = value(row, CATEGORY_COLUMN);
        return category.isEmpty() ? value(row, FALLBACK_CATEGORY_COLUMN) : category;
    }

    private String writtenAtOf(Map<String, String> row) {
        return value(row, WRITTEN_AT_COLUMN);
    }

    /**
     * 소스가 알려 준 작성일을 읽습니다.
     *
     * 날짜만 오므로 그날 시작 시각으로 둡니다.
     * 파일마다 형식이 다릅니다. 관광공사는 열네 자리이고 고캠핑은 날짜만입니다.
     */
    private LocalDateTime parseWrittenAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip(), WRITTEN_AT).atStartOfDay();
        } catch (RuntimeException e) {
            log.warn("작성일을 읽지 못했습니다. value={}", raw);
            return null;
        }
    }

    /**
     * 사전에도 제외 목록에도 없는 컬럼을 한 번만 알립니다.
     *
     * 소스가 컬럼을 늘리면 그것이 조용히 빠지는데 아무 신호가 없으면 알아챌 방법이 없습니다.
     * 행마다 확인하면 만 건이 넘게 같은 경고가 쌓이므로 첫 행으로 한 번만 봅니다.
     */
    private void reportUnknownColumns(List<Map<String, String>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<String> unknown = assembler.unknownColumns(rows.get(0));
        if (!unknown.isEmpty()) {
            log.warn("CSV 에 사전에 없는 컬럼이 있습니다. columns={}", unknown);
        }
    }

    private void flush(List<RawDocumentDraft> chunk, Consumer<List<RawDocumentDraft>> chunkSink) {
        if (chunk.isEmpty()) {
            return;
        }
        chunkSink.accept(new ArrayList<>(chunk));
        chunk.clear();
    }

    private String value(Map<String, String> row, String column) {
        String raw = row.get(column);
        return raw == null ? "" : raw.strip();
    }
}
