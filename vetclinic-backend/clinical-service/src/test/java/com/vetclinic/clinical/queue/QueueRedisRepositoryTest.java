package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phần dựng khóa / tham số và ánh xạ lỗi, không cần Redis. Lua script thật được kiểm tra bằng QueueRedisRepositoryLiveTest (scripts/redis_queue_check.py). */
class QueueRedisRepositoryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final UUID DOCTOR = UUID.fromString("019a9442-d810-7000-8000-000000000002");
    private static final UUID VISIT = UUID.fromString("019a9442-d840-7000-8000-000000000100");
    private static final UUID VSD = UUID.fromString("019a9442-d840-7000-8000-000000000200");
    private static final UUID CASHIER = UUID.fromString("019a9442-d810-7000-8000-000000000005");
    private static final Instant PAID_AT = Instant.parse("2026-11-21T02:30:00Z");

    private static IssueTicketRequest request(int priority, UUID doctorId) {
        return new IssueTicketRequest("evt-1:DOCTOR", QueueType.DOCTOR, LocalDate.of(2026, 11, 21), doctorId, VISIT, priority, PAID_AT, CASHIER,
                List.of(VSD), "Lu", "Hoàng Thị Lan", "P101");
    }

    @Test
    void issueKeys_areQueueSeqDedupeAndVisitSet() {
        assertThat(QueueRedisRepository.issueKeys(request(0, DOCTOR))).containsExactly(
                "queue:20261121:DOCTOR:" + DOCTOR, "queue:seq:20261121:DOCTOR", "queue:dedupe:evt-1:DOCTOR", "queue:visit:" + VISIT);
        assertThat(QueueRedisRepository.issueKeys(request(0, null)).get(0)).isEqualTo("queue:20261121:DOCTOR:UNASSIGNED");
    }

    @Test
    void issueArgs_carryIdScoreTtlsAndEveryHashField() {
        UUID ticketId = UUID.fromString("019a9442-d850-7000-8000-000000000300");
        List<String> args = QueueRedisRepository.issueArgs(request(1, DOCTOR), ticketId);

        assertThat(args.subList(0, 4)).containsExactly(ticketId.toString(), String.valueOf(10_000_000_000_000L + PAID_AT.toEpochMilli()),
                String.valueOf(36 * 3600), String.valueOf(48 * 3600));
        // sau 4 tham số đầu là các cặp field / value của Hash phiếu
        assertThat(args.size() % 2).isZero();
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (int i = 4; i < args.size(); i += 2) {
            fields.put(args.get(i), args.get(i + 1));
        }
        assertThat(fields).containsEntry("queueType", "DOCTOR").containsEntry("serviceDate", "20261121").containsEntry("visitId", VISIT.toString())
                .containsEntry("doctorId", DOCTOR.toString()).containsEntry("priority", "1").containsEntry("issuedAt", String.valueOf(PAID_AT.toEpochMilli()))
                .containsEntry("issuedBy", CASHIER.toString()).containsEntry("vsdIds", VSD.toString()).containsEntry("petName", "Lu")
                .containsEntry("ownerName", "Hoàng Thị Lan").containsEntry("roomNumber", "P101");
    }

    @Test
    void issueArgs_useEmptyStringsForMissingValues() {
        IssueTicketRequest r = new IssueTicketRequest("k", QueueType.LABORATORY, LocalDate.of(2026, 11, 21), null, VISIT, 0, PAID_AT, null, null, null, null, null);
        List<String> args = QueueRedisRepository.issueArgs(r, UUID.randomUUID());
        int doctor = args.indexOf("doctorId");
        assertThat(args.get(doctor + 1)).isEmpty();
        assertThat(args.get(args.indexOf("issuedBy") + 1)).isEmpty();
        assertThat(args.get(args.indexOf("vsdIds") + 1)).isEmpty();
        assertThat(args.get(args.indexOf("roomNumber") + 1)).isEmpty();
        assertThat(args.get(1)).isEqualTo(String.valueOf(2 * 10_000_000_000_000L + PAID_AT.toEpochMilli()));   // priority 0
    }

    /** Mô phỏng Redis: mọi lệnh đi qua {@code answer}; ghi lại tham số để kiểm tra khóa / đối số đã truyền cho script. */
    private static StringRedisTemplate redisAnswering(java.util.function.Function<List<Object>, Object> answer, List<List<Object>> calls) {
        return Mockito.mock(StringRedisTemplate.class, invocation -> {
            if (!invocation.getMethod().getName().equals("execute")) {
                return null;
            }
            List<Object> arguments = new ArrayList<>(List.of(invocation.getArguments()));
            calls.add(arguments);
            return answer.apply(arguments);
        });
    }

    @Test
    void issue_decodesTheScriptReply_andSendsKeysAndArgs() {
        UUID id = UUID.fromString("019a9442-d850-7000-8000-000000000300");
        List<List<Object>> calls = new ArrayList<>();
        QueueRedisRepository repo = new QueueRedisRepository(redisAnswering(a -> List.of(id.toString(), "7", "0"), calls), CLOCK);

        IssuedTicket ticket = repo.issue(request(0, DOCTOR));

        assertThat(ticket.ticketId()).isEqualTo(id);
        assertThat(ticket.ticketNo()).isEqualTo(7);
        assertThat(ticket.duplicate()).isFalse();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).get(1)).isEqualTo(QueueRedisRepository.issueKeys(request(0, DOCTOR)));
    }

    @Test
    void issue_flagsADuplicateDeliveryAndToleratesAMissingTicketNumber() {
        UUID id = UUID.fromString("019a9442-d850-7000-8000-000000000300");
        QueueRedisRepository repo = new QueueRedisRepository(redisAnswering(a -> List.of(id.toString(), "", "1"), new ArrayList<>()), CLOCK);

        IssuedTicket ticket = repo.issue(request(0, DOCTOR));

        assertThat(ticket.duplicate()).isTrue();
        assertThat(ticket.ticketNo()).isNull();   // phiếu cũ đã xong và Hash đã bị xóa
    }

    @Test
    void startMoveCallAndFinish_mapScriptRepliesToOutcomes() {
        UUID ticket = UUID.fromString("019a9442-d850-7000-8000-000000000300");
        List<List<Object>> calls = new ArrayList<>();
        QueueRedisRepository repo = new QueueRedisRepository(redisAnswering(a -> {
            String script = ((org.springframework.data.redis.core.script.RedisScript<?>) a.get(0)).getScriptAsString();
            if (script.contains("Bắt đầu phục vụ phiếu")) return "NOT_WAITING";
            if (script.contains("Chuyển phiếu DOCTOR")) return "OK";
            if (script.contains("\"Gọi\" / \"Gọi lại\"")) return List.of("OK", "3");
            return 1L;
        }, calls), CLOCK);

        assertThat(repo.start(ticket)).isEqualTo(QueueOutcome.NOT_WAITING);
        assertThat(repo.move(ticket, DOCTOR)).isEqualTo(QueueOutcome.OK);
        assertThat(repo.call(ticket)).isEqualTo(new CallResult(QueueOutcome.OK, 3));
        assertThat(repo.finish(ticket)).isTrue();
        // khóa duy nhất truyền vào các script là Hash phiếu; thời điểm lấy từ Clock của phòng khám
        assertThat(calls).allSatisfy(call -> assertThat(call.get(1)).isEqualTo(List.of("ticket:" + ticket)));
        assertThat(calls.get(0).get(3)).isEqualTo(String.valueOf(CLOCK.millis()));   // startedAt
    }

    @Test
    void move_toNullDoctorSendsAnEmptyOwner() {
        UUID ticket = UUID.fromString("019a9442-d850-7000-8000-000000000300");
        List<List<Object>> calls = new ArrayList<>();
        QueueRedisRepository repo = new QueueRedisRepository(redisAnswering(a -> "OK", calls), CLOCK);

        repo.move(ticket, null);

        assertThat(calls.get(0).get(3)).isEqualTo("");
    }

    @Test
    void anyRedisInfrastructureFailureBecomesQueueUnavailable_CLIN_1318() {
        StringRedisTemplate down = Mockito.mock(StringRedisTemplate.class, invocation -> {
            throw new RedisConnectionFailureException("Redis down");
        });
        QueueRedisRepository repo = new QueueRedisRepository(down, CLOCK);
        UUID ticket = UUID.randomUUID();

        for (Runnable call : List.<Runnable>of(() -> repo.issue(request(0, DOCTOR)), () -> repo.start(ticket), () -> repo.move(ticket, DOCTOR),
                () -> repo.call(ticket), () -> repo.finish(ticket), () -> repo.waiting(LocalDate.of(2026, 11, 21), QueueType.DOCTOR, DOCTOR),
                () -> repo.waitingCount(LocalDate.of(2026, 11, 21), QueueType.DOCTOR, DOCTOR), () -> repo.ticket(ticket),
                () -> repo.openTicketsOf(VISIT), () -> repo.isDoctorBusy(DOCTOR),
                () -> repo.seqExists(LocalDate.of(2026, 11, 21), QueueType.DOCTOR), () -> repo.ensureSeqAtLeast(LocalDate.of(2026, 11, 21), QueueType.DOCTOR, 5),
                () -> repo.metaPresent(LocalDate.of(2026, 11, 21)), () -> repo.markMeta(LocalDate.of(2026, 11, 21), PAID_AT))) {
            assertThatThrownBy(call::run).isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.QUEUE_UNAVAILABLE);
        }
    }
}
