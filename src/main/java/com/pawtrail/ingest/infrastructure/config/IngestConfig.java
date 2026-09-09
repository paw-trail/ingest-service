package com.pawtrail.ingest.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 수집 설정을 빈으로 올립니다.
 *
 * ConfigurationProperties 만 붙여 둔 클래스는 저절로 빈이 되지 않습니다.
 * 어딘가에서 켜 주어야 하며 이 클래스가 그 일을 합니다.
 *
 * 진입점에 스캔을 켜지 않는 이유가 있습니다.
 * 그 파일은 복제할 때 고칠 문자열을 줄이려고 스캔 범위를 일부러 비워 둔 자리입니다.
 */
@Configuration
@EnableConfigurationProperties(IngestProperties.class)
public class IngestConfig {
}
