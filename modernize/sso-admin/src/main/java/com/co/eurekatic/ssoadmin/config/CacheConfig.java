package com.co.eurekatic.ssoadmin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis-backed {@link org.springframework.cache.CacheManager} for
 * the {@code app-access} cache used by
 * {@link com.co.eurekatic.ssoadmin.service.AppAccessService}. Same
 * Redis instance auth-center already uses for refresh tokens — the
 * cache name ("app-access") is Spring's default key prefix, so
 * there's no collision with auth-center's own {@code <prefix>:hash:*}
 * / {@code <prefix>:family:*} keys.
 *
 * <p>Values are cached with {@link RedisCacheConfiguration}'s own
 * default serializer (JDK serialization) — deliberately NOT
 * {@code GenericJackson2JsonRedisSerializer}, which Spring Data
 * Redis 4 deprecated in favor of a Jackson-3-based replacement that
 * needs its own {@code tools.jackson.databind.ObjectMapper} bean.
 * Not worth the extra dependency for a single cached {@code boolean}.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(AdminAccessProperties.class)
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          AdminAccessProperties props) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(props.appAccessCacheTtl())
                .disableCachingNullValues();
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
