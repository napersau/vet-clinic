package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chạy Lua script THẬT trên Redis thật. Chỉ chạy khi có biến môi trường {@code REDIS_TEST_PORT} (cổng Redis trên máy):
 * {@code python scripts/redis_queue_check.py} dựng container {@code redis:7-alpine} rồi gọi test này. Bình thường (mvn install) bị bỏ qua.
 * Test nào cũng dùng ngày / bác sĩ / đợt khám ngẫu nhiên nên không cần dọn Redis.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_PORT", matches = ".+")
class QueueRedisRepositoryLiveTest {

    private static final Clock CLOCK = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static QueueRedisRepository repo;

    private LocalDate day;
    private UUID doctor;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("localhost", Integer.parseInt(System.getenv("REDIS_TEST_PORT")));
        LettuceClientConfiguration client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1))
                .clientOptions(ClientOptions.builder().timeoutOptions(TimeoutOptions.enabled()).build()).build();
        factory = new LettuceConnectionFactory(config, client);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        repo = new QueueRedisRepository(redis, CLOCK);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void freshDayAndDoctor() {
        day = LocalDate.of(2100, 1, 1).plusDays(Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000));   // ngày xa, tránh đụng dữ liệu thật
        doctor = UUID.randomUUID();
    }

    private IssueTicketRequest request(String dedupe, QueueType type, UUID doctorId, UUID visit, int priority, Instant at) {
        return new IssueTicketRequest(dedupe, type, day, doctorId, visit, priority, at, UUID.randomUUID(), List.of(UUID.randomUUID()), "Lu", "Lan", "P101");
    }

    private IssuedTicket issue(QueueType type, UUID doctorId, int priority, Instant at) {
        return repo.issue(request(UUID.randomUUID().toString(), type, doctorId, UUID.randomUUID(), priority, at));
    }

    @Test
    void issue_numbersPerDayAndPerQueueType_andOrdersByPriorityThenArrival() {
        Instant t0 = Instant.parse("2026-11-21T01:00:00Z");
        IssuedTicket normalEarly = issue(QueueType.DOCTOR, doctor, 0, t0);
        IssuedTicket normalLate = issue(QueueType.DOCTOR, doctor, 0, t0.plusSeconds(60));
        IssuedTicket results = issue(QueueType.DOCTOR, doctor, 1, t0.plusSeconds(120));
        IssuedTicket emergency = issue(QueueType.DOCTOR, doctor, 2, t0.plusSeconds(180));
        IssuedTicket lab = issue(QueueType.LABORATORY, null, 0, t0);

        assertThat(List.of(normalEarly.ticketNo(), normalLate.ticketNo(), results.ticketNo(), emergency.ticketNo())).containsExactly(1, 2, 3, 4);
        assertThat(lab.ticketNo()).isEqualTo(1);   // bộ đếm riêng từng loại hàng đợi
        assertThat(repo.waiting(day, QueueType.DOCTOR, doctor)).containsExactly(emergency.ticketId(), results.ticketId(), normalEarly.ticketId(), normalLate.ticketId());
        assertThat(repo.waitingCount(day, QueueType.DOCTOR, doctor)).isEqualTo(4);
        assertThat(repo.waiting(day, QueueType.LABORATORY, null)).containsExactly(lab.ticketId());
        assertThat(redis.getExpire("ticket:" + normalEarly.ticketId())).isBetween(1L, 36L * 3600);   // TTL 36 giờ
        assertThat(repo.ticket(normalEarly.ticketId()).orElseThrow()).containsEntry("status", "WAITING").containsEntry("calledCount", "0")
                .containsEntry("ticketNo", "1").containsEntry("petName", "Lu");
    }

    @Test
    void issue_sameDedupeKeyReturnsTheFirstTicket_andDoesNotAdvanceTheCounter_EX19() {
        UUID visit = UUID.randomUUID();
        IssuedTicket first = repo.issue(request("evt:DOCTOR", QueueType.DOCTOR, doctor, visit, 0, Instant.now()));
        IssuedTicket again = repo.issue(request("evt:DOCTOR", QueueType.DOCTOR, doctor, visit, 0, Instant.now()));
        IssuedTicket next = repo.issue(request("other:DOCTOR", QueueType.DOCTOR, doctor, UUID.randomUUID(), 0, Instant.now()));

        assertThat(first.duplicate()).isFalse();
        assertThat(again.duplicate()).isTrue();
        assertThat(again.ticketId()).isEqualTo(first.ticketId());
        assertThat(again.ticketNo()).isEqualTo(first.ticketNo());
        assertThat(next.ticketNo()).isEqualTo(first.ticketNo() + 1);   // nhận lại sự kiện không tốn số
        assertThat(repo.waitingCount(day, QueueType.DOCTOR, doctor)).isEqualTo(2);
        assertThat(repo.openTicketsOf(visit)).containsExactly(first.ticketId());
    }

    @Test
    void issue_fiftyConcurrentIssuersGetDistinctConsecutiveNumbers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<IssuedTicket>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(pool.submit(() -> issue(QueueType.IMAGING, null, 0, Instant.now())));
            }
            List<Integer> numbers = new ArrayList<>();
            for (Future<IssuedTicket> f : futures) {
                numbers.add(f.get().ticketNo());
            }
            assertThat(numbers.stream().sorted().toList()).isEqualTo(java.util.stream.IntStream.rangeClosed(1, 50).boxed().toList());
            assertThat(repo.waitingCount(day, QueueType.IMAGING, null)).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void call_incrementsCalledCountWithoutChangingOrderOrStatus() {
        Instant t0 = Instant.parse("2026-11-21T01:00:00Z");
        IssuedTicket a = issue(QueueType.DOCTOR, doctor, 0, t0);
        IssuedTicket b = issue(QueueType.DOCTOR, doctor, 0, t0.plusSeconds(10));

        assertThat(repo.call(b.ticketId())).isEqualTo(new CallResult(QueueOutcome.OK, 1));
        assertThat(repo.call(b.ticketId())).isEqualTo(new CallResult(QueueOutcome.OK, 2));   // "Gọi lại"
        assertThat(repo.waiting(day, QueueType.DOCTOR, doctor)).containsExactly(a.ticketId(), b.ticketId());   // thứ tự không đổi
        assertThat(repo.ticket(b.ticketId()).orElseThrow()).containsEntry("status", "WAITING").containsEntry("calledCount", "2").containsKey("lastCalledAt");
        assertThat(repo.call(UUID.randomUUID())).isEqualTo(new CallResult(QueueOutcome.NOT_FOUND, 0));
    }

    @Test
    void start_tenConcurrentStartsOnOneTicket_exactlyOneSucceeds_CLIN_1313() throws Exception {
        IssuedTicket ticket = issue(QueueType.DOCTOR, doctor, 0, Instant.now());
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<QueueOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tasks.add(() -> repo.start(ticket.ticketId()));
            }
            List<QueueOutcome> outcomes = new ArrayList<>();
            for (Future<QueueOutcome> f : pool.invokeAll(tasks)) {
                outcomes.add(f.get());
            }
            assertThat(outcomes.stream().filter(o -> o == QueueOutcome.OK)).hasSize(1);
            assertThat(outcomes.stream().filter(o -> o == QueueOutcome.NOT_WAITING)).hasSize(9);
        } finally {
            pool.shutdownNow();
        }
        assertThat(repo.waiting(day, QueueType.DOCTOR, doctor)).isEmpty();   // đang phục vụ thì rời danh sách chờ
        assertThat(repo.ticket(ticket.ticketId()).orElseThrow()).containsEntry("status", "IN_SERVICE").containsKey("startedAt");
        assertThat(repo.call(ticket.ticketId()).outcome()).isEqualTo(QueueOutcome.NOT_WAITING);   // không gọi được phiếu đang phục vụ
    }

    @Test
    void start_aDoctorServesOneTicketAtATime_CLIN_1302_andIsReleasedByFinish() {
        IssuedTicket first = issue(QueueType.DOCTOR, doctor, 0, Instant.now());
        IssuedTicket second = issue(QueueType.DOCTOR, doctor, 0, Instant.now().plusSeconds(1));

        assertThat(repo.start(first.ticketId())).isEqualTo(QueueOutcome.OK);
        assertThat(repo.isDoctorBusy(doctor)).isTrue();
        assertThat(repo.start(second.ticketId())).isEqualTo(QueueOutcome.DOCTOR_BUSY);
        assertThat(repo.ticket(second.ticketId()).orElseThrow()).containsEntry("status", "WAITING");   // bị từ chối thì phiếu không đổi
        assertThat(repo.waiting(day, QueueType.DOCTOR, doctor)).containsExactly(second.ticketId());

        assertThat(repo.finish(first.ticketId())).isTrue();
        assertThat(repo.isDoctorBusy(doctor)).isFalse();
        assertThat(repo.start(second.ticketId())).isEqualTo(QueueOutcome.OK);
    }

    @Test
    void start_unassignedDoctorTicketCannotStart_butLabTicketsHaveNoBusyLock() {
        assertThat(repo.start(issue(QueueType.DOCTOR, null, 0, Instant.now()).ticketId())).isEqualTo(QueueOutcome.UNASSIGNED);
        IssuedTicket lab1 = issue(QueueType.LABORATORY, null, 0, Instant.now());
        IssuedTicket lab2 = issue(QueueType.LABORATORY, null, 0, Instant.now().plusSeconds(1));
        assertThat(repo.start(lab1.ticketId())).isEqualTo(QueueOutcome.OK);
        assertThat(repo.start(lab2.ticketId())).isEqualTo(QueueOutcome.OK);   // phòng XN phục vụ nhiều ca (nhiều KTV)
        assertThat(repo.start(UUID.randomUUID())).isEqualTo(QueueOutcome.NOT_FOUND);
    }

    @Test
    void move_keepsNumberAndOrder_betweenDoctorsAndUnassigned_EX17() {
        Instant t0 = Instant.parse("2026-11-21T01:00:00Z");
        UUID other = UUID.randomUUID();
        IssuedTicket a = issue(QueueType.DOCTOR, doctor, 0, t0);
        IssuedTicket b = issue(QueueType.DOCTOR, doctor, 0, t0.plusSeconds(10));
        IssuedTicket early = issue(QueueType.DOCTOR, other, 0, t0.plusSeconds(5));
        IssuedTicket late = issue(QueueType.DOCTOR, other, 0, t0.plusSeconds(50));

        assertThat(repo.move(b.ticketId(), other)).isEqualTo(QueueOutcome.OK);
        assertThat(repo.waiting(day, QueueType.DOCTOR, doctor)).containsExactly(a.ticketId());
        assertThat(repo.waiting(day, QueueType.DOCTOR, other)).containsExactly(early.ticketId(), b.ticketId(), late.ticketId());   // chèn đúng theo giờ phát
        assertThat(repo.ticket(b.ticketId()).orElseThrow()).containsEntry("ticketNo", String.valueOf(b.ticketNo())).containsEntry("doctorId", other.toString());

        assertThat(repo.move(b.ticketId(), null)).isEqualTo(QueueOutcome.OK);   // về danh sách chờ phân công
        assertThat(repo.waiting(day, QueueType.DOCTOR, null)).containsExactly(b.ticketId());
        assertThat(repo.waiting(day, QueueType.DOCTOR, other)).containsExactly(early.ticketId(), late.ticketId());
        assertThat(repo.move(b.ticketId(), doctor)).isEqualTo(QueueOutcome.OK);   // phân công lại
        assertThat(repo.waiting(day, QueueType.DOCTOR, doctor)).containsExactly(a.ticketId(), b.ticketId());
    }

    @Test
    void move_refusesStartedTicketsLabTicketsAndUnknownOnes() {
        IssuedTicket started = issue(QueueType.DOCTOR, doctor, 0, Instant.now());
        repo.start(started.ticketId());
        assertThat(repo.move(started.ticketId(), UUID.randomUUID())).isEqualTo(QueueOutcome.NOT_WAITING);
        assertThat(repo.move(issue(QueueType.LABORATORY, null, 0, Instant.now()).ticketId(), doctor)).isEqualTo(QueueOutcome.NOT_DOCTOR_QUEUE);
        assertThat(repo.move(UUID.randomUUID(), doctor)).isEqualTo(QueueOutcome.NOT_FOUND);
    }

    @Test
    void finish_removesTheTicketEverywhere_andIsSafeToRepeat() {
        UUID visit = UUID.randomUUID();
        IssuedTicket waiting = repo.issue(request(UUID.randomUUID().toString(), QueueType.DOCTOR, doctor, visit, 0, Instant.now()));
        IssuedTicket lab = repo.issue(request(UUID.randomUUID().toString(), QueueType.LABORATORY, null, visit, 0, Instant.now()));
        assertThat(repo.openTicketsOf(visit)).containsExactlyInAnyOrder(waiting.ticketId(), lab.ticketId());

        assertThat(repo.finish(waiting.ticketId())).isTrue();   // hủy khi còn chờ cũng gỡ khỏi hàng đợi
        assertThat(repo.waiting(day, QueueType.DOCTOR, doctor)).isEmpty();
        assertThat(repo.ticket(waiting.ticketId())).isEmpty();
        assertThat(repo.openTicketsOf(visit)).containsExactly(lab.ticketId());
        assertThat(repo.finish(waiting.ticketId())).isFalse();   // chạy lại: không lỗi
        assertThat(repo.finish(lab.ticketId())).isTrue();
        assertThat(redis.hasKey("queue:visit:" + visit)).isFalse();   // Set rỗng tự biến mất
    }

    @Test
    void finishingAnotherTicketDoesNotReleaseTheDoctorsBusyLock() {
        IssuedTicket serving = issue(QueueType.DOCTOR, doctor, 0, Instant.now());
        IssuedTicket waiting = issue(QueueType.DOCTOR, doctor, 0, Instant.now().plusSeconds(1));
        repo.start(serving.ticketId());

        repo.finish(waiting.ticketId());   // hủy phiếu đang chờ khác của cùng Bác sĩ

        assertThat(repo.isDoctorBusy(doctor)).isTrue();
    }

    @Test
    void ensureSeqAtLeast_raisesTheCounterToTheHistoryMaximum_neverLowersIt_soNewNumbersDoNotCollideWithTheHistory() {
        assertThat(repo.seqExists(day, QueueType.DOCTOR)).isFalse();
        repo.ensureSeqAtLeast(day, QueueType.DOCTOR, 7);   // Redis mất bộ đếm; lịch sử đã dùng tới số 7
        assertThat(repo.seqExists(day, QueueType.DOCTOR)).isTrue();
        assertThat(redis.getExpire("queue:seq:" + QueueKeys.day(day) + ":DOCTOR")).isBetween(1L, 48L * 3600);
        assertThat(issue(QueueType.DOCTOR, doctor, 0, Instant.now()).ticketNo()).isEqualTo(8);   // số kế tiếp là 8
        repo.ensureSeqAtLeast(day, QueueType.DOCTOR, 3);   // số nhỏ hơn bộ đếm hiện tại: không hạ
        assertThat(issue(QueueType.DOCTOR, doctor, 0, Instant.now()).ticketNo()).isEqualTo(9);
        repo.ensureSeqAtLeast(day, QueueType.DOCTOR, 20);
        assertThat(issue(QueueType.DOCTOR, doctor, 0, Instant.now()).ticketNo()).isEqualTo(21);
    }

    @Test
    void metaKey_marksTheDayAsBuilt_andDisappearsWhenRedisIsWiped() {
        assertThat(repo.metaPresent(day)).isFalse();
        repo.markMeta(day, Instant.now());
        assertThat(repo.metaPresent(day)).isTrue();
        assertThat(redis.getExpire(QueueKeys.meta(day))).isBetween(1L, 48L * 3600);
        redis.delete(QueueKeys.meta(day));   // mô phỏng Redis bị xóa
        assertThat(repo.metaPresent(day)).isFalse();
    }

    @Test
    void unreachableRedisFailsFastWithQueueUnavailable_CLIN_1318() {
        RedisStandaloneConfiguration dead = new RedisStandaloneConfiguration("localhost", 1);   // không có gì nghe ở cổng 1
        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(dead, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
        deadFactory.afterPropertiesSet();
        try {
            StringRedisTemplate downRedis = new StringRedisTemplate(deadFactory);
            downRedis.afterPropertiesSet();
            QueueRedisRepository down = new QueueRedisRepository(downRedis, CLOCK);
            long started = System.currentTimeMillis();
            assertThatThrownBy(() -> down.issue(request("x", QueueType.DOCTOR, doctor, UUID.randomUUID(), 0, Instant.now())))
                    .isInstanceOf(AppException.class).extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.QUEUE_UNAVAILABLE);
            assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
        } finally {
            deadFactory.destroy();
        }
    }

    @Test
    void collectionsOfTicketIdsAreReadableAsStrings() {
        IssuedTicket t = issue(QueueType.DOCTOR, doctor, 0, Instant.now());
        assertThat(repo.waiting(day, QueueType.DOCTOR, doctor).stream().map(UUID::toString).collect(Collectors.toList())).containsExactly(t.ticketId().toString());
    }
}
