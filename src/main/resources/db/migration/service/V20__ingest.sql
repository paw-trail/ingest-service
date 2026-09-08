-- 이 서비스의 첫 마이그레이션 스크립트입니다.
-- V1 부터 V19 는 공통 모듈이 사용하는 대역이므로 쓰지 않습니다.
--
-- 이미 적용된 스크립트는 수정하지 않습니다.
-- 내용이 바뀌면 체크섬이 달라져 다음 기동이 실패합니다.
-- 변경이 필요하면 다음 번호로 새 스크립트를 만듭니다.
--
-- raw_db 에는 이 두 테이블과 공통 대역의 outbox · processed_event 가 있습니다.
-- 그 둘은 공통 모듈 jar 의 V1__outbox.sql · V2__inbox.sql 이 만드므로
-- 여기서 다시 만들면 "이미 있는 테이블" 로 기동이 실패합니다.
-- ingest 는 이벤트를 발행하지도 소비하지도 않으므로 둘 다 비어 있는 채로 남습니다.
--
-- 날짜와 시각 컬럼을 전부 timestamp 로 통일해 date 와 time 을 쓰지 않습니다.
-- 국내 전용 서비스이므로 시간대 없는 timestamp 를 쓰고 엔티티는 LocalDateTime 으로 받습니다.
-- 모든 컨테이너에 TZ=Asia/Seoul 이 설정돼 있어야 합니다.
--
-- source · status · run_type 에 CHECK 를 걸지 않습니다.
-- 값이 늘 때마다 마이그레이션이 필요해지기 때문이며, 다른 서비스도 같은 규칙입니다.


-- =============================================================================
-- raw_document
-- =============================================================================
-- 공공 데이터 원본을 장소 하나에 한 행씩 담습니다.
--
-- 이 표가 있는 이유는 둘뿐입니다.
--   ① 재추출 재료   스키마에 칸이 늘어도 재수집 없이 prompt_version 만 올려 다시 뽑습니다.
--   ② 원문보기      장소 상세의 「근거문서 원문보기」가 이 행을 그대로 보여줍니다.
--
-- 그래서 관리자도 조회 전용입니다.
-- 고치면 '원문' 이 아니게 되어 원문보기의 신뢰가 무너집니다.
--
-- 동물병원(MOIS_VET)은 이 표를 거치지 않습니다.
-- 인허가 데이터라 동반 조건 문구가 아예 없어 extract 가 할 일이 없고,
-- 판정을 안 하니 원문보기에 보여줄 근거도 없습니다.
-- ingest 가 CSV 를 읽어 바로 POST /internal/places/bulk 로 넘깁니다.

CREATE TABLE raw_document
(
  id              uuid         PRIMARY KEY,

  -- 이 표에 담기는 소스는 3종입니다.
  --   PET_TOUR · GOCAMPING · CULTURE_CSV
  -- 아래 ingest_run.source 는 4종이라 값이 다릅니다. MOIS_VET 은 적재만 안 하고 실행은 합니다.
  -- place_source_link.source 는 4종, pet_policy_source.source 는 5종입니다.
  source          varchar(20)  NOT NULL,

  -- 소스가 부여한 식별자입니다.
  -- 공사는 contentid, 고캠핑은 contentId, 문화정보원은 CSV 행의 식별 컬럼입니다.
  -- place_source_link.source_id 와 같은 값이어야 병합이 성립하므로
  -- 접미사를 붙이거나 가공하지 않습니다.
  source_id       varchar(100) NOT NULL,

  -- place_db 의 값이라 외래 키를 걸지 않습니다.
  -- 매칭 전에는 NULL 이고 place bulk 가 끝난 뒤 채워집니다.
  place_id        uuid,

  -- 소스 응답 원본입니다.
  --
  -- PET_TOUR 만 한 장소에 응답이 넷이라 키로 나눠 담습니다.
  --   { "list": {…petTourSyncList2…}, "petTour": {…detailPetTour2…},
  --     "common": {…detailCommon2…},  "intro":   {…detailIntro2…} }
  -- 고캠핑(basedList 81필드)과 문화정보원(31컬럼)은 1:1 이라 그대로 담습니다.
  --
  -- 평평하게 병합하지 않는 이유는 어느 응답에서 온 값인지가 사라지기 때문입니다.
  -- tel 이 그 예로, 목록과 detailCommon2 는 빈 값이고 detailIntro2 의 infocenter 에만
  -- 번호가 있습니다. 병합하면 tel 이 비어 있는 것이 소스 성질인지 적재 버그인지
  -- 나중에 구분할 수 없습니다.
  --
  -- 나눠 담으면 modifiedtime 이 바뀌었을 때 상세 셋만 다시 받아 그 키만 갈아끼울 수
  -- 있습니다. 상세 호출은 일일 쿼터가 걸린 자원이라 이것이 실질 비용 차이입니다.
  payload         jsonb        NOT NULL,

  -- 원문보기에 쓰는 표시용 형태입니다.
  -- 좌표 · 지역코드 · 분류코드 · 타임스탬프 · 이미지 URL 같은 기계용 필드를 빼고
  -- 사람이 읽는 자연어만 담습니다.
  -- 판정과 무관해 보이는 문장도 잘라내지 않습니다. 잘라내지 않았다는 것이 원문의 존재 이유입니다.
  display_title   varchar(200),
  display_body    text,

  -- SHA-256 이라 64자입니다. extract 를 다시 돌릴지 판단하는 값입니다.
  --
  -- payload 전체를 정규화해 해시합니다. 조건 필드만 고르지 않는 이유는
  -- 그 목록을 잘못 잡으면 조건이 바뀌었는데 못 잡아 판정이 낡은 채로 남기 때문입니다.
  -- 반대 방향의 실패(무관한 변경에도 재추출)는 LLM 을 한 번 더 쓰는 것에 그칩니다.
  --
  -- ★직렬화 규칙을 지켜야 합니다. 어긋나면 아무것도 안 바뀌었는데 전량이 PENDING 이 되고
  --   extract 가 전부 다시 돕니다.
  --     ① 키를 사전순으로 정렬  ② 공백 없이 직렬화
  --     ③ 빈 문자열을 null 로 바꾼 뒤에 뜰 것
  --     ④ DB 에 넣은 뒤 다시 읽어서 뜨지 말 것 (jsonb 가 키 순서를 바꿔 저장합니다)
  content_hash    varchar(64)  NOT NULL,

  -- 소스가 알려주는 마지막 수정 시각입니다.
  -- 공사는 modifiedtime(14자리 문자열), 고캠핑은 날짜만, 문화정보원은 최종작성일입니다.
  --
  -- content_hash 와 역할이 다릅니다.
  --   source_modified  목록에서 옵니다. 상세를 *부를지* 판단합니다 (쿼터를 아낌)
  --   content_hash     상세를 다 받은 뒤에 뜹니다. extract 를 *다시 돌릴지* 판단합니다
  -- 상세를 받기 전에는 해시를 뜰 수 없으므로 쿼터 판단은 이 컬럼만 할 수 있습니다.
  source_modified timestamp,

  -- 마지막으로 받아온 시각입니다. 화면의 "데이터 기준일" 근거입니다.
  -- updated_at 과 함께 갱신되지만 그쪽은 감사 컬럼이라 뜻이 다릅니다.
  fetched_at      timestamp    NOT NULL,

  -- extract 처리 상태입니다. PENDING · DONE · FAILED
  status          varchar(12)  NOT NULL,

  -- 아래 6개 컬럼은 공통 모듈의 BaseEntity 와 짝을 이룹니다.
  -- 빠뜨리면 ddl-auto: validate 가 기동을 막습니다.
  -- 배치가 만드는 행이라 created_by · updated_by 에는 ingest-batch 가 들어갑니다.
  created_at      timestamp    NOT NULL,
  created_by      varchar(45)  NOT NULL,
  updated_at      timestamp    NOT NULL,
  updated_by      varchar(45)  NOT NULL,
  deleted_at      timestamp,
  deleted_by      varchar(45)
);

-- 같은 소스의 같은 문서는 하나입니다. 재수집은 INSERT 가 아니라 UPDATE 입니다.
CREATE UNIQUE INDEX uq_raw_document_source_source_id
  ON raw_document (source, source_id);

-- GET /internal/raw/{placeId}/documents — 원문보기가 장소로 찾습니다.
CREATE INDEX idx_raw_document_place
  ON raw_document (place_id);

-- GET /internal/raw?status=PENDING — extract 가 처리 대상을 가져갑니다.
--
-- 부분 인덱스입니다. 수집이 끝나면 대부분 DONE 이라 PENDING 이 소수이므로
-- 처리 완료 행이 쌓여도 인덱스가 커지지 않습니다.
-- 공통 대역의 outbox 가 WHERE published_at IS NULL 을 쓴 것과 같은 판단입니다.
CREATE INDEX idx_raw_document_pending
  ON raw_document (id) WHERE status = 'PENDING';

COMMENT ON TABLE raw_document IS '공공 데이터 원본. 재추출 재료이자 상세의 원문보기 대상입니다.';


-- =============================================================================
-- ingest_run
-- =============================================================================
-- 수집 실행 기록입니다. 사람이 승인을 판단하는 재료이자 중단 후 재개의 근거입니다.
--
-- BaseEntity 를 상속하지 않습니다.
-- created_at 이 started_at 과 사실상 같은 값이고 배치가 만드는 로그성 표입니다.
-- refresh_token_log · policy_correction_log · outbox · processed_event 와 같은 부류입니다.
--
-- 인덱스를 만들지 않습니다.
-- 소스 넷을 하루에 몇 번 도는 표라 전체 행이 수백 건 규모입니다.

CREATE TABLE ingest_run
(
  id             uuid        PRIMARY KEY,

  -- 이쪽은 4종입니다. PET_TOUR · GOCAMPING · CULTURE_CSV · MOIS_VET
  -- 위 raw_document.source(3종)와 다릅니다.
  -- MOIS_VET 은 raw_document 를 거치지 않을 뿐 실행은 하므로 기록이 남습니다.
  source         varchar(20) NOT NULL,

  -- FULL · INCREMENTAL
  -- FULL 은 전량, INCREMENTAL 은 source_modified 가 바뀐 것만 상세를 부릅니다.
  run_type       varchar(12) NOT NULL,

  started_at     timestamp   NOT NULL,

  -- 실행 중이면 NULL 입니다.
  finished_at    timestamp,

  -- RUNNING · DONE · FAILED · QUOTA_STOPPED
  -- QUOTA_STOPPED 는 일일 호출 허용량에 걸려 멈춘 것이며 실패가 아닙니다.
  -- 다음 날 progress 의 cursor 에서 이어받습니다.
  status         varchar(16) NOT NULL,

  fetched_count  int         NOT NULL DEFAULT 0,

  -- content_hash 가 달라진 건수입니다.
  -- "감지는 스케줄, 실행은 사람 승인" 방침에서 사람이 보고 판단하는 숫자입니다.
  changed_count  int         NOT NULL DEFAULT 0,

  -- 오퍼레이션별 호출 수와 재개 지점입니다.
  --   { "detailPetTour2": { "count": 1000, "cursor": "1080" },
  --     "detailCommon2":  { "count":  743, "cursor":  "743" } }
  --
  -- 단일 컬럼으로 못 세는 이유는 쿼터가 오퍼레이션마다 따로 걸리기 때문입니다.
  -- 공사 상세 셋은 각각 일 1,000건이고 대상이 1,080건이라
  -- 한 실행에서 세 오퍼레이션이 각자 한도에 닿습니다.
  -- 합계만 남기면 어느 것이 걸렸는지 알 수 없어 재개 지점을 정할 수 없습니다.
  --
  -- 소스마다 부르는 오퍼레이션이 다르고 CSV 소스는 API 호출이 0이라
  -- 컬럼으로 두면 대부분이 NULL 이 됩니다. notification.payload 를 jsonb 로 둔 것과 같습니다.
  progress       jsonb       NOT NULL DEFAULT '{}',

  error_message  text
);

COMMENT ON TABLE ingest_run IS '수집 실행 기록. 승인 판단 재료이자 쿼터 중단 후 재개의 근거입니다.';
