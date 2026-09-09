package com.pawtrail.ingest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawtrail.ingest.application.support.JsonNormalizer;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.QuotaExhaustedException;
import com.pawtrail.ingest.domain.model.RawDocument;
import com.pawtrail.ingest.domain.provider.CollectionContext;
import com.pawtrail.ingest.domain.repository.RawDocumentRepository;
import com.pawtrail.ingest.infrastructure.config.IngestProperties;
import com.pawtrail.ingest.infrastructure.provider.external.PetTourApiClient;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 이 장치가 있는 이유는 증분이 확인되지 않은 전제 위에 서 있기 때문입니다.
 *
 * 그러므로 두 가지가 지켜져야 합니다.
 * 어긋난 것이 있으면 그것이 반드시 드러나야 하고,
 * 이 장치 자체가 실행 결과를 바꾸어서는 안 됩니다.
 * 진단하려다 수집을 실패로 만들면 본말이 뒤집힙니다.
 */
@ExtendWith(MockitoExtension.class)
class PetTourSampleVerifierTest {

    @Mock
    private PetTourApiClient client;

    @Mock
    private RawDocumentRepository rawDocumentRepository;

    private final JsonNormalizer jsonNormalizer = new JsonNormalizer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("전량 수집이면 아무것도 하지 않는다")
    void skipsWhenFullRun() {
        List<String> notes = verifier(20).verify(CollectionContext.startFresh(RunType.FULL));

        // 전량을 다 받으므로 검증할 것이 없음
        assertThat(notes).isEmpty();
        verify(rawDocumentRepository, never()).findOldestFetched(any(), anyInt());
    }

    @Test
    @DisplayName("표본 크기가 0 이면 아무것도 하지 않는다")
    void skipsWhenSampleSizeIsZero() {
        List<String> notes = verifier(0).verify(context());

        // 몇 주 돌려 한 번도 안 걸리면 0 으로 두어 끌 수 있어야 함
        assertThat(notes).isEmpty();
        verify(rawDocumentRepository, never()).findOldestFetched(any(), anyInt());
    }

    @Test
    @DisplayName("상세가 그대로면 아무 문구도 남기지 않는다")
    void reportsNothingWhenUnchanged() {
        RawDocument document = document("1019041", "12");
        givenSample(document);
        Map<String, Object> petTour = Map.of("acmpyTypeCd", "전구역 동반가능");
        givenDetails(petTour);
        setHash(document, "12", petTour);

        assertThat(verifier(20).verify(context())).isEmpty();
    }

    @Test
    @DisplayName("상세가 달라졌으면 전제가 어긋났다고 알린다")
    void reportsWhenDetailChanged() {
        RawDocument document = document("1019041", "12");
        givenSample(document);
        // 담아 둔 것은 동반 가능인데 소스가 그 뒤에 불가로 바꾼 상황
        setHash(document, "12", Map.of("acmpyTypeCd", "전구역 동반가능"));
        givenDetails(Map.of("acmpyTypeCd", "동반 불가"));

        List<String> notes = verifier(20).verify(context());

        // 목록 시각은 그대로인데 상세가 달라졌다는 것이 전제가 틀렸다는 증거임
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).contains("1019041").contains("1건");
    }

    @Test
    @DisplayName("표본을 부르는 호출도 진행 기록에 센다")
    void countsSampleCalls() {
        RawDocument document = document("1019041", "12");
        givenSample(document);
        givenDetails(Map.of());
        setHash(document, "12", Map.of());

        CollectionContext context = context();
        verifier(20).verify(context);

        // 같은 오퍼레이션을 쓰므로 안 세면 허용량 계산이 어긋남
        assertThat(context.countOf(PetTourApiClient.DETAIL_PET_TOUR_OPERATION)).isEqualTo(1);
        assertThat(context.countOf(PetTourApiClient.DETAIL_COMMON_OPERATION)).isEqualTo(1);
        assertThat(context.countOf(PetTourApiClient.DETAIL_INTRO_OPERATION)).isEqualTo(1);

        // 저장하지 않으므로 재개 지점은 옮기지 않음
        assertThat(context.cursorOf(PetTourApiClient.DETAIL_PET_TOUR_OPERATION)).isNull();
    }

    @Test
    @DisplayName("표본을 뽑는 조회가 실패해도 예외를 올려보내지 않는다")
    void swallowsSampleQueryFailure() {
        when(rawDocumentRepository.findOldestFetched(SourceType.PET_TOUR, 20))
                .thenThrow(new DataAccessResourceFailureException("연결이 끊겼습니다"));

        // 밖으로 나가면 실행기가 실패로 마감함
        // 수집은 이미 끝났는데 실패로 남고, 실패한 실행은 재개 지점을 물려주지 않아
        // 다음 실행이 처음부터 다시 훑음
        assertThat(verifier(20).verify(context())).isEmpty();
    }

    @Test
    @DisplayName("도중에 끊겨도 그때까지 알아낸 어긋남은 알린다")
    void reportsFindingsFoundBeforeFailure() {
        RawDocument changed = document("1019041", "12");
        RawDocument broken = document("2019041", "12");
        givenSample(changed, broken);
        setHash(changed, "12", Map.of("acmpyTypeCd", "전구역 동반가능"));
        setField(broken, "payload", "이것은 JSON 이 아님");
        givenDetails(Map.of("acmpyTypeCd", "동반 불가"));

        List<String> notes = verifier(20).verify(context());

        // 열 건을 보고 끊겼어도 그 안에 어긋난 것이 있었다면 알려야 함
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).contains("1019041").contains("1건 중 1건");
    }

    @Test
    @DisplayName("표본을 부르다 실패해도 예외를 올려보내지 않는다")
    void swallowsFailures() {
        RawDocument document = document("1019041", "12");
        givenSample(document);
        when(client.fetchPetTourDetail(anyString(), any()))
                .thenThrow(new QuotaExhaustedException(PetTourApiClient.DETAIL_PET_TOUR_OPERATION));

        // 진단이지 수집이 아님, 수집은 이미 끝났으므로 실행 결과를 바꾸면 안 됨
        assertThat(verifier(20).verify(context())).isEmpty();
    }

    @Test
    @DisplayName("타입이 없으면 소개 정보를 부르지 않는다")
    void skipsIntroWhenTypeIsMissing() {
        RawDocument document = document("1019041", null);
        givenSample(document);
        givenDetails(Map.of());
        setHash(document, null, Map.of());

        verifier(20).verify(context());

        // 필수 값이 빠진 요청은 반드시 거절당하므로 부르면 허용량만 버림
        verify(client, never()).fetchIntroDetail(anyString(), any(), any());
    }

    private PetTourSampleVerifier verifier(int sampleSize) {
        IngestProperties properties = new IngestProperties(
                20, 0, 0, 1000, 5,
                new IngestProperties.PetTour("http://localhost", "test-only", 100, sampleSize),
                new IngestProperties.GoCamping("http://localhost", "test-only", 100),
                new IngestProperties.Culture("build/tmp/test-culture.csv"));
        return new PetTourSampleVerifier(
                client, rawDocumentRepository, jsonNormalizer, objectMapper, properties);
    }

    private CollectionContext context() {
        return CollectionContext.startFresh(RunType.INCREMENTAL);
    }

    private void givenSample(RawDocument... documents) {
        when(rawDocumentRepository.findOldestFetched(SourceType.PET_TOUR, 20))
                .thenReturn(List.of(documents));
    }

    /**
     * 상세 셋이 같은 값을 돌려주게 합니다. 기록 통로도 실물처럼 부릅니다.
     */
    private void givenDetails(Map<String, Object> petTour) {
        // 시험마다 셋을 다 부르지는 않으므로 느슨하게 둡니다.
        // 타입이 없으면 소개 정보를 안 부르는 것이 정상 동작입니다
        lenient().when(client.fetchPetTourDetail(anyString(), any()))
                .thenAnswer(record(PetTourApiClient.DETAIL_PET_TOUR_OPERATION, petTour));
        lenient().when(client.fetchCommonDetail(anyString(), any()))
                .thenAnswer(record(PetTourApiClient.DETAIL_COMMON_OPERATION, Map.of()));
        lenient().when(client.fetchIntroDetail(anyString(), any(), any()))
                .thenAnswer(record(PetTourApiClient.DETAIL_INTRO_OPERATION, Map.of()));
    }

    private org.mockito.stubbing.Answer<Map<String, Object>> record(
            String operation, Map<String, Object> result) {

        return invocation -> {
            recordAttempt(invocation, operation);
            return result;
        };
    }

    private void recordAttempt(InvocationOnMock invocation, String operation) {
        Object last = invocation.getArgument(invocation.getArguments().length - 1);
        @SuppressWarnings("unchecked")
        Consumer<String> onAttempt = (Consumer<String>) last;
        onAttempt.accept(operation);
    }

    /**
     * 담아 둘 때 떴을 해시를 문서에 심습니다.
     *
     * 담아 둘 때와 같은 방식으로 묶고 같은 규칙으로 떠야 견주는 것이 뜻을 갖습니다.
     *
     * *모의 객체를 부르지 않고 값을 직접 받습니다.
     *  여기서 부르면 그것이 스텁을 한 번 소모하고,
     *  뒤에 다시 스텁할 때 앞 답이 인자 없이 실행되어 그 자리에서 끊깁니다.
     */
    private void setHash(RawDocument document, String contentTypeId,
                         Map<String, Object> petTour) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", contentTypeId == null
                ? Map.of()
                : Map.of("contenttypeid", contentTypeId));
        payload.put("petTour", petTour);
        payload.put("common", Map.of());
        payload.put("intro", Map.of());
        setField(document, "contentHash", jsonNormalizer.hash(jsonNormalizer.normalize(payload)));
    }

    private RawDocument document(String sourceId, String contentTypeId) {
        String payload = contentTypeId == null
                ? "{\"list\":{}}"
                : "{\"list\":{\"contenttypeid\":\"" + contentTypeId + "\"}}";
        return RawDocument.create(
                SourceType.PET_TOUR, sourceId, payload, "제목", "본문",
                "hash", null, LocalDateTime.now());
    }

    private void setField(RawDocument document, String name, Object value) {
        try {
            var field = RawDocument.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(document, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("시험용 값을 넣지 못했습니다", e);
        }
    }
}
