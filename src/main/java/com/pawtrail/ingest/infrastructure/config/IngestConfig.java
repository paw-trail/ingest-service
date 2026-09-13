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
 *
 * 전달 설정을 따로 둔 이유는 두 가지입니다.
 * 수집 설정은 바깥을 부르는 값이고 전달 설정은 우리 표를 읽어 보내는 값이라 성격이 다릅니다.
 * 그리고 한 레코드에 값을 더하면 그것을 만드는 검사가 전부 깨져
 * 설정 하나를 늘릴 때마다 여섯 곳을 함께 고치게 됩니다.
 */
@Configuration
@EnableConfigurationProperties({IngestProperties.class, PlaceLinkProperties.class})
public class IngestConfig {
}
