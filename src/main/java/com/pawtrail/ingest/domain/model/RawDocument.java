package com.pawtrail.ingest.domain.model;

import com.pawtrail.common.entity.BaseEntity;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.enums.DocumentStatus;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 공공 데이터 원본입니다. 장소 하나가 한 행입니다.
 *
 * 이 표가 있는 이유는 둘뿐입니다.
 * 스키마에 칸이 늘어도 재수집 없이 다시 뽑을 수 있는 추출 재료이고,
 * 장소 상세의 근거문서 원문보기가 그대로 보여주는 대상입니다.
 * 그래서 관리자도 조회 전용입니다. 고치면 원문이 아니게 되어 원문보기의 신뢰가 무너집니다.
 */
@Entity
@Table(name = "raw_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawDocument extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // 이 표에 담기는 소스는 셋임. MOIS_VET 은 아래 팩터리가 막음
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false, updatable = false)
    private SourceType source;

    // 소스가 부여한 식별자임
    // place_source_link.source_id 와 같은 값이어야 병합이 성립하므로
    // 접미사를 붙이거나 가공하지 않음
    @Column(name = "source_id", length = 100, nullable = false, updatable = false)
    private String sourceId;

    // place_db 의 값이라 외래 키를 걸지 않음
    // 매칭 전에는 null 이고 place 로 넘긴 뒤에 채워짐
    @Column(name = "place_id")
    private UUID placeId;

    // 소스 응답 원본임
    //
    // * 타입이 String 인 이유
    //   우리가 정규화한 문자열이 그대로 저장되고 해시가 *그 문자열에서* 나옴
    //   객체로 들고 있으면 저장 직전에 다시 직렬화해야 하고
    //   그 직렬화가 해시를 뜬 것과 한 글자라도 다르면 해시가 원본을 가리키지 않게 됨
    //
    // * 우리가 읽지 않는 값임
    //   extract 가 API 로 받아 파싱하고 ingest 는 담아 두기만 함
    //   소스마다 구조가 달라 공통 타입도 나오지 않음
    //
    // * PET_TOUR 만 응답이 넷이라 키로 나눠 담음
    //   { "list": …, "petTour": …, "common": …, "intro": … }
    //   고캠핑과 문화정보원은 응답이 하나라 그대로 담음
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    // 원문보기에 쓰는 표시용 형태임
    // 좌표·지역코드·분류코드·타임스탬프·이미지 주소 같은 기계용 필드를 빼고
    // 사람이 읽는 자연어만 담음
    // 판정과 무관해 보이는 문장도 잘라내지 않음. 잘라내지 않았다는 것이 원문의 존재 이유임
    @Column(name = "display_title", length = 200)
    private String displayTitle;

    @Column(name = "display_body", columnDefinition = "text")
    private String displayBody;

    // payload 를 정규화해 뜬 SHA-256 임. 64자
    // 계산은 JsonNormalizer 한 곳에서만 함
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    // 소스가 알려주는 마지막 수정 시각임
    //
    // contentHash 와 역할이 다름
    //   sourceModified  목록에서 옴. 상세를 *부를지* 판단함 (쿼터를 아낌)
    //   contentHash     상세를 다 받은 뒤에 뜸. 다시 추출할지 판단함 (모델 호출을 아낌)
    // 상세를 받기 전에는 해시를 뜰 수 없으므로 쿼터 판단은 이 값만 할 수 있음
    @Column(name = "source_modified")
    private LocalDateTime sourceModified;

    // 마지막으로 받아온 시각임. 화면의 데이터 기준일 근거임
    // updatedAt 과 함께 갱신되지만 그쪽은 감사 컬럼이라 뜻이 다름
    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 12, nullable = false)
    private DocumentStatus status;

    private RawDocument(
            SourceType source,
            String sourceId,
            String payload,
            String displayTitle,
            String displayBody,
            String contentHash,
            LocalDateTime sourceModified,
            LocalDateTime fetchedAt) {

        this.source = source;
        this.sourceId = sourceId;
        this.payload = payload;
        this.displayTitle = displayTitle;
        this.displayBody = displayBody;
        this.contentHash = contentHash;
        this.sourceModified = sourceModified;
        this.fetchedAt = fetchedAt;
        this.status = DocumentStatus.PENDING;
    }

    /**
     * 원본 문서를 처음 담습니다.
     *
     * 같은 소스의 같은 문서를 두 번 담는 것은
     * uq_raw_document_source_source_id 가 막습니다.
     * 애플리케이션에서 먼저 조회해 거르지 않는 이유는
     * 조회와 저장 사이에 다른 요청이 끼어들 수 있기 때문입니다.
     */
    public static RawDocument create(
            SourceType source,
            String sourceId,
            String payload,
            String displayTitle,
            String displayBody,
            String contentHash,
            LocalDateTime sourceModified,
            LocalDateTime fetchedAt) {

        if (source == null || sourceId == null || payload == null || contentHash == null) {
            throw new IllegalArgumentException("source, sourceId, payload, contentHash 는 필수입니다.");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt 은 필수입니다.");
        }

        // MOIS_VET 은 이 표를 거치지 않음
        // 코드 실수일 때만 닿는 자리지만 조용히 들어가면 원문보기에 빈 문서가 생김
        if (!source.isStoredAsRawDocument()) {
            throw new CustomException(IngestErrorCode.RAW_DOCUMENT_NOT_ALLOWED);
        }

        return new RawDocument(
                source, sourceId, payload, displayTitle, displayBody,
                contentHash, sourceModified, fetchedAt);
    }

    /**
     * 다시 받아온 내용으로 갱신하고, 실제로 달라졌는지 알려줍니다.
     *
     * 해시가 같으면 받아온 시각만 새로 찍고 나머지는 건드리지 않습니다.
     * 특히 상태를 PENDING 으로 되돌리지 않습니다.
     * 되돌리면 아무것도 안 바뀐 문서를 추출이 다시 처리하게 되고,
     * 그 비용은 모델 호출이라 작지 않습니다.
     *
     * 해시가 다르면 전부 갈아끼우고 상태를 PENDING 으로 돌립니다.
     * 이미 추출한 결과가 낡았다는 뜻이기 때문입니다.
     *
     * @return 내용이 실제로 달라졌으면 true. 실행 기록의 changedCount 가 이 값을 셈
     */
    public boolean applyIfChanged(
            String newPayload,
            String newDisplayTitle,
            String newDisplayBody,
            String newContentHash,
            LocalDateTime newSourceModified,
            LocalDateTime newFetchedAt) {

        this.fetchedAt = newFetchedAt;

        if (this.contentHash.equals(newContentHash)) {
            return false;
        }

        this.payload = newPayload;
        this.displayTitle = newDisplayTitle;
        this.displayBody = newDisplayBody;
        this.contentHash = newContentHash;
        this.sourceModified = newSourceModified;
        this.status = DocumentStatus.PENDING;
        return true;
    }

    /**
     * 추출이 끝난 것으로 표시합니다.
     */
    public void markDone() {
        this.status = DocumentStatus.DONE;
    }

    /**
     * 추출에 실패한 것으로 표시합니다.
     */
    public void markFailed() {
        this.status = DocumentStatus.FAILED;
    }

    /**
     * 병합된 장소를 이어 붙입니다. place 로 넘긴 뒤에 호출됩니다.
     */
    public void linkPlace(UUID linkedPlaceId) {
        this.placeId = linkedPlaceId;
    }
}
