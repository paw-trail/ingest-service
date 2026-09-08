package com.pawtrail.template;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 서비스 진입점입니다.
 *
 * 공통 모듈(com.pawtrail.common)은 자동 설정으로 등록되므로 컴포넌트 스캔 대상에 넣지 않습니다.
 * 넣으면 같은 설정이 자동 설정과 스캔 양쪽에 잡혀 두 번 등록되고,
 * 조건 평가 순서가 깨져 의도와 다른 Bean이 올라갈 수 있습니다.
 * 공통 모듈은 의존성만 추가하면 조건에 맞는 Bean이 알아서 올라옵니다.
 *
 * 다만 아래 @EntityScan과 @EnableJpaRepositories에는 공통 모듈을 그대로 지정합니다.
 * 공통 모듈의 OutboxMessage·ProcessedEvent 엔티티와 그 레포지터리는
 * 자동 설정이 잡아주지 않기 때문입니다.
 * 세 애노테이션이 비슷하게 생겼으나 공통 모듈이 들어가는 곳은 아래 둘뿐입니다.
 *
 * 스캔 범위를 따로 지정하지 않은 이유는 기본값이 이 클래스가 속한 패키지이기 때문입니다.
 * 복제 후 패키지명을 바꿀 때 고쳐야 할 문자열이 하나 줄어듭니다.
 *
 * DB를 사용하지 않는 서비스(verdict, congestion, route)는
 * 아래 @EntityScan과 @EnableJpaRepositories 두 줄과 해당 import를 지웁니다.
 * build.gradle의 JPA·QueryDSL블록도 함께 지워야 하며,
 * 한쪽만 고치면 컴파일이 실패합니다.
 *
 * 참고: @EntityScan의 패키지는 Spring Boot 4에서
 * org.springframework.boot.autoconfigure.domain에서 현재 위치로 옮겨졌습니다.
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.pawtrail.template", "com.pawtrail.common"})
@EnableJpaRepositories(basePackages = {"com.pawtrail.template", "com.pawtrail.common"})
public class TemplateApplication {

    public static void main(String[] args) {
        SpringApplication.run(TemplateApplication.class, args);
    }
}
