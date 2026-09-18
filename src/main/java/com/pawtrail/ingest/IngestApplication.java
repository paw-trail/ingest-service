package com.pawtrail.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 서비스 진입점입니다.
 *
 * 공통 모듈(com.pawtrail.common)은 자동 설정으로 등록되므로 컴포넌트 스캔 대상에 넣지 않습니다.
 * 넣으면 같은 설정이 자동 설정과 스캔 양쪽에 잡혀 두 번 등록되고,
 * 조건 평가 순서가 깨져 의도와 다른 Bean이 올라갈 수 있습니다.
 * 공통 모듈은 의존성만 추가하면 조건에 맞는 Bean이 알아서 올라옵니다.
 *
 * 템플릿에 있던 @EntityScan과 @EnableJpaRepositories 두 줄은 지웠습니다.
 * 그 둘은 공통 모듈의 OutboxMessage·ProcessedEvent 엔티티와 그 레포지터리를 잡으려고 둔 것인데,
 * 이 서비스는 이벤트를 내지도 받지도 않아 Kafka 의존성까지 뺐습니다.
 * 공통 모듈의 발행 자동 설정은 JPA와 Kafka가 함께 있을 때만 켜지므로 그 엔티티를 찾는 Bean도 없습니다.
 *
 * 두 줄이 없으면 엔티티와 레포지터리는 이 클래스가 속한 패키지에서만 찾습니다.
 * 이 서비스의 엔티티(RawDocument · IngestRun)와 레포지터리가 전부 그 아래에 있습니다.
 *
 * 이벤트가 필요해지면 build.gradle의 Kafka 두 줄과 함께 두 줄을 되살립니다.
 * 원문 DB의 outbox · processed_event 표는 공통 마이그레이션이 만들어 두어 그대로 있습니다.
 */
@SpringBootApplication
public class IngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
