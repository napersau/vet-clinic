package com.vetclinic.notification.security;

import com.vetclinic.notification.config.NotificationProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import com.vetclinic.common.security.RoleCode;
import java.util.UUID;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StompSecurityTest {

    private static final String U1 = "019a9442-d810-7000-8000-000000000001";
    private static final String U5 = "019a9442-d810-7000-8000-000000000005";
    private static final String U7 = "019a9442-d810-7000-8000-000000000007";

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtTokenVerifier verifier = new JwtTokenVerifier(
            new NotificationProperties(SECRET, "no-reply@vetclinic.local", 50, "Asia/Ho_Chi_Minh"));
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(verifier);
    private final MessageChannel channel = mock(MessageChannel.class);

    /** roles là danh sách phân tách dấu phẩy (JWT v4.0 mang claim "roles" dạng danh sách). */
    private static String token(String userId, String roles, Date expiry, SecretKey key) {
        return Jwts.builder().claim("userId", userId).claim("roles", List.of(roles.split(","))).expiration(expiry).signWith(key).compact();
    }

    private static String validToken(String userId, String roles) {
        return token(userId, roles, Date.from(Instant.now().plusSeconds(600)), KEY);
    }

    private static StompHeaderAccessor accessor(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private Message<?> send(StompHeaderAccessor accessor) {
        return interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), channel);
    }

    private StompHeaderAccessor connected(String userId, String roles) {
        StompHeaderAccessor connect = accessor(StompCommand.CONNECT);
        connect.setNativeHeader("Authorization", "Bearer " + validToken(userId, roles));
        send(connect);
        return connect;
    }

    private StompHeaderAccessor subscribe(StompHeaderAccessor session, String destination) {
        StompHeaderAccessor subscribe = accessor(StompCommand.SUBSCRIBE);
        subscribe.setDestination(destination);
        subscribe.setSubscriptionId("sub-1");
        subscribe.setUser(session.getUser());
        return subscribe;
    }

    // ================================================================== JwtTokenVerifier

    @Test
    void verifier_acceptsATokenSignedWithTheSharedSecret() {
        var user = verifier.verify(validToken(U5, "VETERINARIAN"));

        assertThat(user).hasValueSatisfying(u -> {
            assertThat(u.userId()).isEqualTo(UUID.fromString(U5));
            assertThat(u.roles()).containsExactly(RoleCode.VETERINARIAN);
        });
    }

    @Test
    void verifier_rejectsExpiredForgedAndMalformedTokens() {
        SecretKey other = Keys.hmacShaKeyFor("ANOTHER-0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThat(verifier.verify(token(U5, "ADMIN", Date.from(Instant.now().minusSeconds(60)), KEY))).isEmpty();
        assertThat(verifier.verify(token(U5, "ADMIN", Date.from(Instant.now().plusSeconds(600)), other))).isEmpty();
        assertThat(verifier.verify("khong.phai.jwt")).isEmpty();
        assertThat(verifier.verify(validToken("abc", "ADMIN"))).isEmpty();          // userId không phải UUID
        assertThat(verifier.verify(validToken(U5, "SUPERUSER"))).isEmpty();        // vai trò không tồn tại
    }

    @Test
    void verifier_readsEveryRoleOfTheToken_andRefusesATokenWithoutAnyValidRole() {
        assertThat(verifier.verify(validToken(U7, "VETERINARIAN,ADMIN"))).hasValueSatisfying(u ->
                assertThat(u.roles()).containsExactlyInAnyOrder(RoleCode.VETERINARIAN, RoleCode.ADMIN));
        assertThat(verifier.verify(validToken(U7, "ADMIN,SOMETHING_NEW"))).hasValueSatisfying(u ->
                assertThat(u.roles()).containsExactly(RoleCode.ADMIN));   // vai trò lạ bị bỏ qua
        // token v3 chỉ có claim đơn "role" (không có "roles") không còn được chấp nhận
        String legacy = Jwts.builder().claim("userId", U7).claim("role", "ADMIN")
                .expiration(Date.from(Instant.now().plusSeconds(600))).signWith(KEY).compact();
        assertThat(verifier.verify(legacy)).isEmpty();
    }

    @Test
    void properties_rejectAShortSecret() {
        assertThatThrownBy(() -> new NotificationProperties("short", "a@b", 50, "Asia/Ho_Chi_Minh"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ================================================================== CONNECT

    @Test
    void connect_withBearerTokenAttachesTheUser() {
        StompHeaderAccessor session = connected(U7, "RECEPTIONIST");

        assertThat(session.getUser()).isNotNull();
        assertThat(session.getUser().getName()).isEqualTo(U7);   // Principal name = userId, khớp convertAndSendToUser
    }

    @Test
    void connect_acceptsTheTokenHeaderToo() {
        StompHeaderAccessor connect = accessor(StompCommand.CONNECT);
        connect.setNativeHeader("token", validToken(U7, "ADMIN"));

        send(connect);

        assertThat(connect.getUser().getName()).isEqualTo(U7);
    }

    @Test
    void connect_withoutOrWithBadTokenIsRejected() {
        StompHeaderAccessor none = accessor(StompCommand.CONNECT);
        StompHeaderAccessor bad = accessor(StompCommand.CONNECT);
        bad.setNativeHeader("Authorization", "Bearer " + validToken(U7, "ADMIN") + "x");
        StompHeaderAccessor notBearer = accessor(StompCommand.CONNECT);
        notBearer.setNativeHeader("Authorization", "Basic abc");

        assertThatThrownBy(() -> send(none)).isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> send(bad)).isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> send(notBearer)).isInstanceOf(MessagingException.class);
    }

    // ================================================================== SUBSCRIBE / SEND

    @Test
    void subscribe_toOwnPrivateQueueAndOwnRoleTopicIsAllowed() {
        StompHeaderAccessor session = connected(U7, "RECEPTIONIST");

        send(subscribe(session, "/user/queue/notifications"));
        send(subscribe(session, "/topic/role/RECEPTIONIST"));
    }

    @Test
    void subscribe_aUserWithSeveralRolesMayListenToEachOfTheirRoleTopics() {
        StompHeaderAccessor session = connected(U7, "RECEPTIONIST,ADMIN");

        send(subscribe(session, "/topic/role/RECEPTIONIST"));
        send(subscribe(session, "/topic/role/ADMIN"));
        assertThatThrownBy(() -> send(subscribe(session, "/topic/role/VETERINARIAN"))).isInstanceOf(MessagingException.class);
    }

    @Test
    void subscribe_toAnotherRolesTopicOrAnythingElseIsRejected() {
        StompHeaderAccessor session = connected(U7, "RECEPTIONIST");

        assertThatThrownBy(() -> send(subscribe(session, "/topic/role/ADMIN"))).isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> send(subscribe(session, "/topic/role/NOPE"))).isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> send(subscribe(session, "/topic/role/"))).isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> send(subscribe(session, "/topic/anything"))).isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> send(subscribe(session, "/user/queue/other"))).isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> send(subscribe(session, null))).isInstanceOf(MessagingException.class);
    }

    @Test
    void subscribe_withoutHavingConnectedIsRejected() {
        StompHeaderAccessor anonymous = accessor(StompCommand.SUBSCRIBE);
        anonymous.setDestination("/user/queue/notifications");
        anonymous.setSubscriptionId("sub-1");

        assertThatThrownBy(() -> send(anonymous)).isInstanceOf(MessagingException.class);
    }

    @Test
    void send_isAlwaysRejectedEvenForAuthenticatedUsers() {
        StompHeaderAccessor session = connected(U1, "ADMIN");
        StompHeaderAccessor message = accessor(StompCommand.SEND);
        message.setDestination("/topic/role/ADMIN");
        message.setUser(session.getUser());

        assertThatThrownBy(() -> send(message)).isInstanceOf(MessagingException.class);
    }

    @Test
    void otherFrames_requireAnAuthenticatedSession_andHeartbeatsPass() {
        StompHeaderAccessor unsubscribeAnonymous = accessor(StompCommand.UNSUBSCRIBE);
        StompHeaderAccessor unsubscribe = accessor(StompCommand.UNSUBSCRIBE);
        unsubscribe.setUser(new UsernamePasswordAuthenticationToken(U7, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        heartbeat.setLeaveMutable(true);

        assertThatThrownBy(() -> send(unsubscribeAnonymous)).isInstanceOf(MessagingException.class);
        send(unsubscribe);
        send(heartbeat);
    }
}
