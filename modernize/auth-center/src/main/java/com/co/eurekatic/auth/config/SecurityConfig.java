package com.co.eurekatic.auth.config;

import com.co.eurekatic.auth.security.JsonAuthHandlers;
import com.co.eurekatic.auth.security.JsonLoginFilter;
import com.co.eurekatic.auth.security.JwtAuthenticationFilter;
import com.co.eurekatic.common.security.CorsProperties;
import com.co.eurekatic.common.security.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 6 configuration. The {@link SecurityFilterChain} bean
 * replaces the legacy {@code WebSecurityConfigurerAdapter}.
 *
 * <p>Public endpoints (no auth required):
 * <ul>
 *   <li>{@code POST /login}</li>
 *   <li>{@code GET /getToken}, {@code GET /getApiToken}, {@code GET /getInfoUser}, {@code GET /getUsersSSO}</li>
 *   <li>{@code POST /googleLogin} (stub returning 501)</li>
 *   <li>{@code /actuator/health} and {@code /actuator/info}</li>
 *   <li>{@code OPTIONS /**} (CORS preflight)</li>
 * </ul>
 *
 * <p>Everything else requires a valid Bearer token. CORS is permissive
 * for the MVP because the gateway is the trust boundary; in production
 * this should be tightened to a specific allowlist.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager,
            JwtTokenService jwt,
            ObjectMapper objectMapper,
            com.co.eurekatic.common.security.JwtProperties jwtProperties,
            CorsProperties corsProperties,
            JsonAuthHandlers handlers) throws Exception {

        JsonLoginFilter loginFilter = new JsonLoginFilter(
                authenticationManager, jwt, objectMapper, jwtProperties);
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwt, jwtProperties);

        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(corsProperties)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(handlers.unauthorizedHandler())
                        .accessDeniedHandler(handlers.forbiddenHandler()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        // /auth/refresh and /auth/logout are part of the cookie
                        // auth flow. The refresh endpoint requires the cookie
                        // but does NOT require a Bearer token in the header;
                        // the cookie IS the credential. Logout is the same.
                        .requestMatchers("/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/login").permitAll()
                        .requestMatchers("/getToken", "/getApiToken",
                                "/getInfoUser", "/getUsersSSO", "/googleLogin").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * CORS allowlist driven by {@link CorsProperties}. We use
     * {@code setAllowedOrigins} (not {@code setAllowedOriginPatterns}) so
     * the wildcard {@code "*"} is rejected at the CORS layer when
     * {@code setAllowCredentials(true)} — exactly the spec behavior we
     * want. Dev defaults are in {@link CorsProperties#defaults()};
     * production sets a real allowlist via env var.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        cfg.setExposedHeaders(List.of("Authorization", "Set-Cookie"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
