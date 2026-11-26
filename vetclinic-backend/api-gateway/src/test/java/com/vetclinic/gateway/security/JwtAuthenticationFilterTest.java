package com.vetclinic.gateway.security;

import com.vetclinic.gateway.config.GatewayAuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** JWT v4.0: userId là UUID dạng chuỗi, roles là danh sách; Gateway đưa vào X-User-Id / X-User-Roles (phân tách dấu phẩy). */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final String USER = "019a9442-d810-7000-8000-000000000002";

    private final GatewayAuthProperties properties = new GatewayAuthProperties(SECRET, List.of("/api/v1/auth/login"));
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(new JwtValidator(properties), new JsonMapper(), properties);
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private String token(Object userId, Object roles) {
        var builder = Jwts.builder().expiration(Date.from(Instant.now().plusSeconds(600)));
        if (userId != null) {
            builder.claim("userId", userId);
        }
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    /** Chạy filter và trả về request mà service phía sau nhận được (null nếu bị chặn ở Gateway). */
    private ServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request, AtomicReference<ServerWebExchange> seen) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.empty();
        });
        filter.filter(exchange, chain).block();
        return exchange;
    }

    @Test
    void validTokenGivesUuidHeaderAndCommaSeparatedRoles() {
        var seen = new AtomicReference<ServerWebExchange>();
        run(MockServerHttpRequest.get("/api/v1/pets").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + token(USER, List.of("VETERINARIAN", "ADMIN"))), seen);

        HttpHeaders headers = seen.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo(USER);
        assertThat(headers.getFirst("X-User-Roles")).isEqualTo("VETERINARIAN,ADMIN");
    }

    @Test
    void headersSentByTheClientAreAlwaysStripped_evenOnPublicPaths() {
        var seen = new AtomicReference<ServerWebExchange>();
        run(MockServerHttpRequest.post("/api/v1/auth/login").header("X-User-Id", USER).header("X-User-Roles", "ADMIN"), seen);

        HttpHeaders headers = seen.get().getRequest().getHeaders();
        assertThat(headers.containsHeader("X-User-Id")).isFalse();
        assertThat(headers.containsHeader("X-User-Roles")).isFalse();
    }

    @Test
    void forgedHeadersDoNotSurviveWhenTheTokenIsValid() {
        var seen = new AtomicReference<ServerWebExchange>();
        run(MockServerHttpRequest.get("/api/v1/pets").header("X-User-Roles", "ADMIN")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER, List.of("PET_OWNER"))), seen);

        assertThat(seen.get().getRequest().getHeaders().get("X-User-Roles")).containsExactly("PET_OWNER");
    }

    @Test
    void tokenWithoutRolesOrWithAnOldSingleRoleClaimIsRejectedWith401() {
        for (String bad : new String[]{token(USER, null), token(USER, List.of()), token(null, List.of("ADMIN"))}) {
            var seen = new AtomicReference<ServerWebExchange>();
            ServerWebExchange exchange = run(MockServerHttpRequest.get("/api/v1/pets").header(HttpHeaders.AUTHORIZATION, "Bearer " + bad), seen);
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(seen.get()).isNull();
        }
    }

    @Test
    void missingOrGarbageTokenIsRejectedWith401() {
        var seen = new AtomicReference<ServerWebExchange>();
        assertThat(run(MockServerHttpRequest.get("/api/v1/pets"), seen).getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(MockServerHttpRequest.get("/api/v1/pets").header(HttpHeaders.AUTHORIZATION, "Bearer abc.def.ghi"), seen)
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
