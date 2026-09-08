-- 이 서비스의 첫 마이그레이션 스크립트입니다.
-- 파일명을 V20__<서비스명>.sql 로 바꾸고 내용을 채웁니다.
-- V1 부터 V19 는 공통 모듈이 사용하는 대역이므로 쓰지 않습니다.
--
-- 이미 적용된 스크립트는 수정하지 않습니다.
-- 내용이 바뀌면 체크섬이 달라져 다음 기동이 실패합니다.
-- 변경이 필요하면 다음 번호로 새 스크립트를 만듭니다.

CREATE TABLE template
(
    -- PK 는 모든 테이블이 uuid 입니다.
    -- DB 함수로 만들지 않고 애플리케이션이 Hibernate 의
    -- @UuidGenerator(style = UuidGenerator.Style.VERSION_7) 로 생성해 넣으므로
    -- 여기에 기본값을 지정하지 않습니다.
    --
    -- 순차 숫자를 쓰지 않는 이유는 서비스가 여러 개라
    -- place_id=42 와 pet_id=42 가 구분되지 않고,
    -- 순차 ID 가 URL 에 노출되면 데이터 규모가 드러나기 때문입니다.
    id         uuid         PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,

    -- 아래 6개 컬럼은 모든 테이블이 공통으로 가집니다.
    -- 공통 모듈의 BaseEntity 와 짝을 이루므로 빠뜨리면 기동 검증에 실패합니다.
    -- created_* 와 updated_* 는 JPA Auditing 이 항상 채우므로 NOT NULL 이고,
    -- deleted_* 는 소프트 딜리트 시점에만 채워지므로 NULL 을 허용합니다.
    created_at TIMESTAMP    NOT NULL,
    created_by VARCHAR(45)  NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    updated_by VARCHAR(45)  NOT NULL,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(45)
);
