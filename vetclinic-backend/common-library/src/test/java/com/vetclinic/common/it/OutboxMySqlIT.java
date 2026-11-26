package com.vetclinic.common.it;

import com.vetclinic.common.event.EventRouting;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.event.outbox.EnableOutbox;
import com.vetclinic.common.event.outbox.OutboxEvent;
import com.vetclinic.common.event.outbox.OutboxEventRepository;
import com.vetclinic.common.event.outbox.OutboxRelay;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.event.processed.EnableIdempotentConsumer;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import com.vetclinic.common.util.UuidV7;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.ConnectException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Kiểm thử tích hợp outbox + processed_events trên MySQL 8 THẬT (Lộ trình v4 bước 1.1). Không chạy trong
 * {@code mvn install} thường ngày; chạy tay:
 * <pre>
 * docker run -d --name outbox-it -e MYSQL_ROOT_PASSWORD=test123 -e MYSQL_DATABASE=outbox_it -p 3317:3306 mysql:8.0
 * OUTBOX_IT_JDBC_URL="jdbc:mysql://localhost:3317/outbox_it" OUTBOX_IT_PASSWORD=test123 mvn -o -pl common-library test -Dtest=OutboxMySqlIT
 * </pre>
 * Bảng tạo đúng DDL của docs/schema_init.sql v4.0 (aggregate_id BINARY(16)) và kiểm tra bằng
 * {@code ddl-auto: validate}. Lớp ứng dụng thử đặt ở package khác nên entity chỉ được nạp nhờ {@code @EnableOutbox} /
 * {@code @EnableIdempotentConsumer}.
 */
@EnabledIfEnvironmentVariable(named = "OUTBOX_IT_JDBC_URL", matches = ".+")
@SpringBootTest(classes = OutboxMySqlIT.App.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "vetclinic.outbox.initial-delay-ms=3600000",   // tắt lịch tự chạy, test gọi relay bằng tay
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class OutboxMySqlIT {

    private static final java.util.UUID AGGREGATE = java.util.UUID.fromString("019a9442-d810-7000-8000-00000000002a");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableOutbox
    @EnableIdempotentConsumer
    static class App {
    }

    private static String url() {
        String base = System.getenv("OUTBOX_IT_JDBC_URL");
        return base + (base.contains("?") ? "&" : "?") + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }

    private static String password() {
        String pw = System.getenv("OUTBOX_IT_PASSWORD");
        return pw == null ? "" : pw;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OutboxMySqlIT::url);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("OUTBOX_IT_USER", "root"));
        registry.add("spring.datasource.password", OutboxMySqlIT::password);
    }

    /** DDL giống docs/schema_init.sql v4.0 mục 9 và 13 (aggregate_id BINARY(16)). Chạy TRƯỚC khi Spring validate. */
    @BeforeAll
    static void createTables() throws Exception {
        try (Connection c = DriverManager.getConnection(url(), System.getenv().getOrDefault("OUTBOX_IT_USER", "root"), password());
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS outbox_events");
            s.execute("DROP TABLE IF EXISTS processed_events");
            s.execute("""
                    CREATE TABLE outbox_events (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_id BINARY(16) NOT NULL UNIQUE,
                        aggregate_type VARCHAR(50) NOT NULL,
                        aggregate_id BINARY(16) NOT NULL,
                        event_type VARCHAR(100) NOT NULL,
                        routing_key VARCHAR(100) NOT NULL,
                        payload JSON NOT NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                        attempt_count INT NOT NULL DEFAULT 0,
                        next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_error VARCHAR(500) NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        published_at TIMESTAMP NULL
                    )""");
            s.execute("CREATE INDEX idx_outbox_pending ON outbox_events(status, next_attempt_at, id)");
            s.execute("""
                    CREATE TABLE processed_events (
                        event_id BINARY(16) NOT NULL,
                        consumer VARCHAR(100) NOT NULL,
                        processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (event_id, consumer)
                    )""");
        }
    }

    @Autowired
    OutboxWriter writer;
    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxEventRepository repository;
    @Autowired
    IdempotentConsumer idempotent;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;
    @MockitoBean
    RabbitTemplate rabbitTemplate;

    private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM processed_events");
        tx = new TransactionTemplate(transactionManager);
        reset(rabbitTemplate);
    }

    private VisitCompletedEvent event() {
        return new VisitCompletedEvent(UuidV7.generate(), Instant.now(), UuidV7.generate(), UuidV7.generate(), "VIS-2026-00042", com.vetclinic.common.util.UuidV7.generate(), false);
    }

    @Test
    void writerStoresTheEventInTheSameTransaction_asValidJson_withTheEventIdAsBinary16() {
        VisitCompletedEvent e = event();
        tx.executeWithoutResult(s -> writer.write("VISIT", AGGREGATE, e));

        assertThat(jdbc.queryForObject("SELECT BIN_TO_UUID(event_id) FROM outbox_events", String.class)).isEqualTo(e.eventId().toString());
        assertThat(jdbc.queryForObject("SELECT JSON_VALID(payload) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.visitCode')) FROM outbox_events", String.class))
                .isEqualTo("VIS-2026-00042");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PENDING");
    }

    @Test
    void aRolledBackBusinessTransactionLeavesNoEvent() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            writer.write("VISIT", AGGREGATE, event());
            throw new IllegalStateException("nghiệp vụ lỗi");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void writingOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> writer.write("VISIT", AGGREGATE, event())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void relayPublishesPendingRowsAndMarksThemPublished() {
        VisitCompletedEvent e = event();
        tx.executeWithoutResult(s -> writer.write("VISIT", AGGREGATE, e));

        int sent = relay.publishBatch();

        assertThat(sent).isEqualTo(1);
        verify(rabbitTemplate).send(eq(EventRouting.EXCHANGE), eq(EventRouting.VISIT_COMPLETED), any(Message.class), any(CorrelationData.class));
        OutboxEvent row = repository.findByEventId(e.eventId()).orElseThrow();
        assertThat(row.getStatus().name()).isEqualTo("PUBLISHED");
        assertThat(row.getPublishedAt()).isNotNull();
        assertThat(relay.publishBatch()).isZero();   // không gửi lại dòng đã PUBLISHED
    }

    /**
     * Hồi quy (bước 8.3, EX-18): relay gửi RabbitMQ TRONG giao dịch đang giữ khóa dòng outbox; khi RabbitMQ treo kết nối, các giao dịch nghiệp vụ khác
     * (thu tiền, chốt ca...) muốn GHI dòng outbox mới bị chặn bởi khóa khoảng (gap lock) của REPEATABLE READ cho tới lock wait timeout (50 giây) rồi lỗi
     * 409 SYS_1005. Relay phải chạy READ_COMMITTED (chỉ khóa đúng các dòng đã chọn) để việc ghi dòng mới không bao giờ phải chờ lần gửi.
     */
    @Test
    void aStalledSendDoesNotBlockOtherTransactionsFromWritingNewOutboxRows() throws Exception {
        tx.executeWithoutResult(s -> writer.write("VISIT", AGGREGATE, event()));
        java.util.concurrent.CountDownLatch sending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            sending.countDown();
            release.await(30, java.util.concurrent.TimeUnit.SECONDS);   // RabbitMQ treo kết nối
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Integer> relayRun = pool.submit(relay::publishBatch);
            assertThat(sending.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("relay đã vào bước gửi").isTrue();

            java.util.concurrent.Future<?> otherTransaction = pool.submit(() -> tx.executeWithoutResult(s -> writer.write("VISIT", AGGREGATE, event())));
            otherTransaction.get(8, java.util.concurrent.TimeUnit.SECONDS);   // TimeoutException nếu bị chặn bởi khóa của relay

            release.countDown();
            assertThat(relayRun.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void relayFailureIsPersisted_andTheRowIsRetriedOnlyAfterTheBackoff() {
        tx.executeWithoutResult(s -> writer.write("VISIT", AGGREGATE, event()));
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.publishBatch();

        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events", String.class)).contains("Connection refused");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > UTC_TIMESTAMP() FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(relay.publishBatch()).isZero();   // chưa tới hạn gửi lại
    }

    @Test
    void lockedRowsAreSkippedByAConcurrentRelay() throws Exception {
        tx.executeWithoutResult(s -> writer.write("VISIT", AGGREGATE, event()));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> tx.executeWithoutResult(s -> {
            assertThat(repository.lockNextBatch(Instant.now().plusSeconds(1), 10)).hasSize(1);
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        List<OutboxEvent> seenByOther = tx.execute(s -> repository.lockNextBatch(Instant.now().plusSeconds(1), 10));

        release.countDown();
        holder.join(10_000);
        assertThat(seenByOther).isEmpty();   // SKIP LOCKED: không chờ, không gửi trùng
    }

    @Test
    void idempotentConsumerRunsOncePerEventAndListener() {
        UUID eventId = UuidV7.generate();
        AtomicInteger runs = new AtomicInteger();

        assertThat(idempotent.run(eventId, "appointment.visit-checked-in", runs::incrementAndGet)).isTrue();
        assertThat(idempotent.run(eventId, "appointment.visit-checked-in", runs::incrementAndGet)).isFalse();
        assertThat(idempotent.run(eventId, "notification.visit-checked-in", runs::incrementAndGet)).isTrue();

        assertThat(runs).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = UUID_TO_BIN(?)", Integer.class,
                eventId.toString())).isEqualTo(2);
    }

    @Test
    void aFailingConsumerActionLeavesNoProcessedRow() {
        UUID eventId = UuidV7.generate();
        assertThatThrownBy(() -> idempotent.run(eventId, "billing.prescription-created", () -> {
            throw new IllegalStateException("lỗi");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class)).isZero();
    }
}
