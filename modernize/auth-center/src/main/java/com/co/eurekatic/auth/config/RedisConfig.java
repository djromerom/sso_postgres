package com.co.eurekatic.auth.config;

import com.co.eurekatic.auth.security.RedisRefreshTokenStore;
import com.co.eurekatic.auth.security.RefreshTokenProperties;
import com.co.eurekatic.common.security.RefreshTokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the refresh-token store backed by Redis.
 *
 * <p>The {@link StringRedisTemplate} and the underlying Lettuce
 * {@code RedisConnectionFactory} are auto-configured by Spring Boot 4
 * via {@code spring-boot-starter-data-redis} (no manual factory bean
 * needed). Host/port/password come from {@code spring.data.redis.*}
 * — wired from {@code REDIS_HOST} / {@code REDIS_PORT} /
 * {@code REDIS_PASSWORD} in {@code docker-compose.yml}.
 *
 * <p>The store bean is exposed as the {@link RefreshTokenStore}
 * interface from {@code common} so callers (filter chain, refresh
 * controller) can be unit-tested against a fake without dragging
 * Lettuce into the test classpath.
 */
@Configuration
@EnableConfigurationProperties(RefreshTokenProperties.class)
public class RedisConfig {

    @Bean
    public RefreshTokenStore refreshTokenStore(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            RefreshTokenProperties refreshTokenProperties) {
        return new RedisRefreshTokenStore(stringRedisTemplate, objectMapper, refreshTokenProperties);
    }
}