package com.pawtrail.ingest.infrastructure.provider.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.provider.dto.RawDocumentDraft;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 거르는 단계가 셋이고 셋 다 조용히 어긋납니다.
 *
 * 완전 중복을 안 지우면 같은 장소가 수십 번 들어갑니다.
 * 대상을 잘못 고르면 약국 팔천 곳이 검색 색인에 들어갑니다.
 * 같은 키에서 옛 판을 남기면 어느 판이 남을지가 파일에 적힌 순서에 달립니다.
 *
 * 식별자를 만드는 규칙도 여기서 못 박습니다.
 * 이 소스에는 식별자 컬럼이 없어 우리가 이름과 주소로 만드는데,
 * 그 규칙이 바뀌면 이미 담긴 것이 통째로 고아가 됩니다.
 */
@ExtendWith(MockitoExtension.class)
class CultureCsvCollectorTest {

    @Mock
    private CsvReader reader;

    private final CultureDisplayBodyAssembler assembler = new CultureDisplayBodyAssembler();

    private List<List<RawDocumentDraft>> chunks;

    @BeforeEach
    void setUp() {
        chunks = new ArrayList<>();
    }

    @Test
    @DisplayName("맡은 소스는 CULTURE_CSV 다")
    void handlesCultureCsv() {
        assertThat(collector(100).source()).isEqualTo(SourceType.CULTURE_CSV);
    }

    @Test
    @DisplayName("전 컬럼이 똑같은 행을 걷어낸다")
    void removesExactDuplicates() {
        Map<String, String> row = row("행복동물병원", "서울특별시 강남구 역삼동 1-1", "동물병원", "2025-03-24");
        given(List.of(copy(row), copy(row), copy(row)));

        collector(100).collect(freshContext(), chunks::add);

        // 좌표까지 같은 행이 반복됨, 안 지우면 같은 장소가 수십 번 들어감
        assertThat(chunks.get(0)).hasSize(1);
    }

    @Test
    @DisplayName("담기로 한 분류만 남긴다")
    void keepsOnlyTargetCategories() {
        given(List.of(
                row("행복동물병원", "서울특별시 강남구 역삼동 1-1", "동물병원", "2025-03-24"),
                row("행복약국", "서울특별시 강남구 역삼동 2-2", "동물약국", "2025-03-24"),
                row("행복미용실", "서울특별시 강남구 역삼동 3-3", "미용", "2025-03-24"),
                row("한강공원", "서울특별시 영등포구 여의도동 4-4", "여행지", "2025-03-24")));

        collector(100).collect(freshContext(), chunks::add);

        // 약국과 미용실은 화면에 그 카테고리를 보여줄 자리가 없음
        assertThat(chunks.get(0))
                .extracting(RawDocumentDraft::displayTitle)
                .containsExactly("행복동물병원", "한강공원");
    }

    @Test
    @DisplayName("세부 분류가 비면 상위 분류를 본다")
    void fallsBackToParentCategory() {
        Map<String, String> row = row("반려문화관", "서울특별시 종로구 사직동 1-1", "", "2025-03-24");
        row.put("카테고리2", "반려문화시설");
        given(List.of(row));

        collector(100).collect(freshContext(), chunks::add);

        assertThat(chunks.get(0)).hasSize(1);
    }

    @Test
    @DisplayName("같은 키가 여럿이면 작성일이 늦은 것만 남긴다")
    void keepsLatestPerKey() {
        Map<String, String> old = row("개인공간", "충청남도 공주시 반포면 상신리 594-5", "카페", "2022-11-30");
        old.put("전화번호", "041-000-0000");
        Map<String, String> recent = row("개인공간", "충청남도 공주시 반포면 상신리 594-5", "카페", "2025-03-24");
        recent.put("전화번호", "0507-1303-3484");

        // 파일에 옛 판이 뒤에 오는 경우
        given(List.of(recent, old));

        collector(100).collect(freshContext(), chunks::add);

        assertThat(chunks.get(0)).hasSize(1);
        // 안 지우면 뒤에 오는 행이 앞을 덮어써 어느 판이 남을지가 파일 순서에 달림
        assertThat(chunks.get(0).get(0).sourceModified())
                .isEqualTo(LocalDateTime.of(2025, 3, 24, 0, 0));
    }

    @Test
    @DisplayName("식별자는 시설명과 지번주소를 이어 만든다")
    void buildsSourceIdFromNameAndAddress() {
        given(List.of(row("행복동물병원", "서울특별시 강남구 역삼동 1-1", "동물병원", "2025-03-24")));

        collector(100).collect(freshContext(), chunks::add);

        // 이 소스에는 식별자 컬럼이 하나도 없음
        // 행 번호를 쓰면 파일이 갱신될 때 같은 번호가 다른 장소를 가리킴
        assertThat(chunks.get(0).get(0).sourceId())
                .isEqualTo("행복동물병원|서울특별시 강남구 역삼동 1-1");
    }

    @Test
    @DisplayName("시설명 가운데 공백이 달라도 같은 식별자로 본다")
    void ignoresInnerSpacesInName() {
        Map<String, String> spaced = row("박영재 동물병원", "전라북도 전주시 완산구 서서학동 219-1",
                "동물병원", "2025-03-24");
        Map<String, String> tight = row("박영재동물병원", "전라북도 전주시 완산구 서서학동 219-1",
                "동물병원", "2025-03-24");
        given(List.of(spaced, tight));

        collector(100).collect(freshContext(), chunks::add);

        // 걷어내지 않으면 키가 갈려 유일 제약에 안 걸리고 문서가 둘 들어감
        assertThat(chunks.get(0)).hasSize(1);
        assertThat(chunks.get(0).get(0).sourceId())
                .isEqualTo("박영재동물병원|전라북도 전주시 완산구 서서학동 219-1");
    }

    @Test
    @DisplayName("식별자에서 공백을 걷어내도 표시 이름은 원본 표기를 그대로 둔다")
    void keepsRawNameInDisplayTitle() {
        given(List.of(row("도그 앤 피플 동물병원", "울산광역시 남구 무거동 855-7",
                "동물병원", "2025-03-24")));

        collector(100).collect(freshContext(), chunks::add);

        RawDocumentDraft draft = chunks.get(0).get(0);
        assertThat(draft.sourceId()).isEqualTo("도그앤피플동물병원|울산광역시 남구 무거동 855-7");
        // 이 표는 우리가 잘라내지 않았다는 것을 보여주는 자리임
        assertThat(draft.displayTitle()).isEqualTo("도그 앤 피플 동물병원");
        assertThat(draft.payload().get("list")).isEqualTo(
                row("도그 앤 피플 동물병원", "울산광역시 남구 무거동 855-7", "동물병원", "2025-03-24"));
    }

    @Test
    @DisplayName("지번주소의 공백은 걷어내지 않는다")
    void keepsSpacesInAddress() {
        given(List.of(row("행복동물병원", "서울특별시 강남구 역삼동 1-1", "동물병원", "2025-03-24")));

        collector(100).collect(freshContext(), chunks::add);

        // 주소 띄어쓰기가 달라 갈린 쌍이 실측에서 한 건도 없었음
        // 번지를 붙이면 서로 다른 주소가 겹칠 여지만 생김
        assertThat(chunks.get(0).get(0).sourceId())
                .isEqualTo("행복동물병원|서울특별시 강남구 역삼동 1-1");
    }

    @Test
    @DisplayName("띄어쓰기만 다른 두 판 가운데 작성일이 늦은 것이 남는다")
    void keepsLatestAmongSpacingVariants() {
        Map<String, String> old = row("상아 동물메디컬", "경상남도 창원시 의창구 도계동 404-2",
                "동물병원", "2022-11-30");
        Map<String, String> recent = row("상아동물메디컬", "경상남도 창원시 의창구 도계동 404-2",
                "동물병원", "2025-03-24");
        given(List.of(recent, old));

        collector(100).collect(freshContext(), chunks::add);

        assertThat(chunks.get(0)).hasSize(1);
        // 최신만 남기는 판정이 그대로 걸림. 표시 이름은 살아남은 행의 원본 표기가 됨
        assertThat(chunks.get(0).get(0).sourceModified())
                .isEqualTo(LocalDateTime.of(2025, 3, 24, 0, 0));
        assertThat(chunks.get(0).get(0).displayTitle()).isEqualTo("상아동물메디컬");
    }

    @Test
    @DisplayName("원본을 list 열쇠 아래에 그대로 담는다")
    void keepsRawRowUnderListKey() {
        Map<String, String> row = row("한강공원", "서울특별시 영등포구 여의도동 4-4", "여행지", "2025-03-24");
        row.put("홈페이지", "정보없음");
        given(List.of(row));

        collector(100).collect(freshContext(), chunks::add);

        RawDocumentDraft draft = chunks.get(0).get(0);
        assertThat(draft.payload()).containsOnlyKeys("list");
        // 값이 없을 때 쓰는 문자열도 바꾸지 않고 그대로 담음
        // "없음" 이 진짜 값인 컬럼이 있어 여기서 판단하지 않음
        assertThat(draft.payload().get("list")).isEqualTo(row);
    }

    @Test
    @DisplayName("진행 기록을 남기지 않는다")
    void leavesProgressEmpty() {
        given(List.of(row("한강공원", "서울특별시 영등포구 여의도동 4-4", "여행지", "2025-03-24")));

        CollectionContext context = freshContext();
        collector(100).collect(context, chunks::add);

        // 바깥을 부르지 않아 호출 허용량을 셀 것이 없음
        // 지어낸 이름으로 파일 읽기를 세면 그 값의 뜻이 깨짐
        assertThat(context.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("청크가 차면 나누어 넘긴다")
    void splitsIntoChunks() {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(row("공원" + i, "서울특별시 강남구 역삼동 " + i, "여행지", "2025-03-24"));
        }
        given(rows);

        collector(2).collect(freshContext(), chunks::add);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(2)).hasSize(1);
    }

    @Test
    @DisplayName("작성일을 읽지 못하면 비워 두고 나머지는 담는다")
    void keepsGoingWhenWrittenAtIsBroken() {
        Map<String, String> row = row("한강공원", "서울특별시 영등포구 여의도동 4-4", "여행지", "20250324");
        given(List.of(row));

        collector(100).collect(freshContext(), chunks::add);

        assertThat(chunks.get(0).get(0).sourceModified()).isNull();
        assertThat(chunks.get(0).get(0).displayTitle()).isEqualTo("한강공원");
    }

    @Test
    @DisplayName("담을 것이 하나도 없으면 청크를 넘기지 않는다")
    void sendsNothingWhenAllFiltered() {
        given(List.of(
                row("행복약국", "서울특별시 강남구 역삼동 2-2", "동물약국", "2025-03-24"),
                row("행복미용실", "서울특별시 강남구 역삼동 3-3", "미용", "2025-03-24")));

        collector(100).collect(freshContext(), chunks::add);

        assertThat(chunks).isEmpty();
    }

    /**
     * 읽는 쪽이 행을 하나씩 넘기는 것을 흉내 냅니다.
     *
     * 실물 리더가 목록을 만들지 않고 행마다 콜백을 부르므로 시늉도 같아야 합니다.
     * 그래야 수집기가 읽는 도중에 거르는 것을 그대로 확인합니다.
     *
     * 리더가 인코딩과 컬럼 수와 필수 컬럼을 함께 받게 되어 콜백이 다섯 번째 인자입니다.
     * 소스가 둘이 되면서 읽는 쪽을 하나로 합쳤고 그 넷만 값으로 갈립니다.
     */
    private void given(List<Map<String, String>> rows) {
        when(reader.read(any(), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            Consumer<Map<String, String>> rowSink = invocation.getArgument(4);
            rows.forEach(rowSink);
            return rows.size();
        });
    }

    private CultureCsvCollector collector(int chunkSize) {
        IngestProperties properties = new IngestProperties(
                chunkSize, 0, 0, 1000, 5,
                new IngestProperties.PetTour("http://localhost", "test-only", 100, 0),
                new IngestProperties.GoCamping("http://localhost", "test-only", 100),
                new IngestProperties.Culture("build/tmp/test-culture.csv"),
                new IngestProperties.MoisVet("build/tmp/test-mois-vet.csv", "CP949"));
        return new CultureCsvCollector(reader, assembler, properties);
    }

    private CollectionContext freshContext() {
        return CollectionContext.startFresh(RunType.FULL);
    }

    private Map<String, String> copy(Map<String, String> row) {
        return new LinkedHashMap<>(row);
    }

    /**
     * 실제 파일의 서른한 컬럼 가운데 이 시험이 보는 것만 채웁니다.
     */
    private Map<String, String> row(String name, String address, String category, String writtenAt) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("시설명", name);
        row.put("카테고리1", "반려동물업");
        row.put("카테고리2", "반려동반여행");
        row.put("카테고리3", category);
        row.put("지번주소", address);
        row.put("전화번호", "02-000-0000");
        row.put("반려동물 동반 가능정보", "Y");
        row.put("최종작성일", writtenAt);
        return row;
    }
}
