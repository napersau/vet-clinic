package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.util.UuidV7;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Kho phiếu xếp hàng trên Redis (Quy chuẩn v4.0 mục 9.3). Mọi thao tác phát / chuyển / gọi / bắt đầu / kết thúc phiếu chạy bằng Lua script nên
 * nguyên tử, không khóa, không còn lỗi xung đột phiên bản. Redis không truy cập được (mất kết nối, quá {@code timeout} 1 giây) thì ném
 * {@link ErrorCode#QUEUE_UNAVAILABLE} (CLIN_1318); MySQL vẫn là nguồn sự thật của trạng thái nghiệp vụ nên các giao dịch tiền không phụ thuộc lớp này.
 * Bước 6.1: chưa nối vào nghiệp vụ.
 */
@Slf4j
@Component
public class QueueRedisRepository {

    /** Hash phiếu hết hạn sau 36 giờ. */
    static final long TICKET_TTL_SECONDS = 36L * 3600;
    /** Bộ đếm số phiếu, khóa chống trùng và khóa busy: 48 giờ. */
    static final long DEDUPE_TTL_SECONDS = 48L * 3600;

    private static final RedisScript<List> ISSUE = script("issue_ticket.lua", List.class);
    private static final RedisScript<String> MOVE = script("move_ticket.lua", String.class);
    private static final RedisScript<List> CALL = script("call_ticket.lua", List.class);
    private static final RedisScript<String> START = script("start_ticket.lua", String.class);
    private static final RedisScript<Long> FINISH = script("finish_ticket.lua", Long.class);
    private static final RedisScript<Long> ENSURE_SEQ = script("ensure_seq.lua", Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public QueueRedisRepository(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    private static <T> RedisScript<T> script(String file, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("queue/lua/" + file));
        script.setResultType(resultType);
        return script;
    }

    // ================================================================== ghi

    /** Phát phiếu. Khóa chống trùng đã có thì trả lại phiếu cũ (đánh dấu {@code duplicate}) và KHÔNG tăng số thứ tự. */
    public IssuedTicket issue(IssueTicketRequest request) {
        UUID ticketId = UuidV7.generate(clock);
        List<String> keys = issueKeys(request);
        Object[] args = issueArgs(request, ticketId).toArray();
        List<?> result = guard(() -> redis.execute(ISSUE, keys, args));
        if (result == null || result.size() < 3) {
            throw new IllegalStateException("issue_ticket.lua trả về kết quả không hợp lệ: " + result);
        }
        String no = String.valueOf(result.get(1));
        return new IssuedTicket(UUID.fromString(String.valueOf(result.get(0))), no.isEmpty() ? null : Integer.valueOf(no),
                "1".equals(String.valueOf(result.get(2))));
    }

    /** Chuyển phiếu DOCTOR đang chờ sang Bác sĩ khác ({@code null} = về danh sách chờ phân công), giữ nguyên số phiếu và thứ tự. */
    public QueueOutcome move(UUID ticketId, UUID newDoctorId) {
        String outcome = guard(() -> redis.execute(MOVE, List.of(QueueKeys.ticket(ticketId)), ticketId.toString(),
                newDoctorId == null ? "" : newDoctorId.toString()));
        return outcome(outcome);
    }

    /** "Gọi" / "Gọi lại": tăng {@code calledCount}, ghi {@code lastCalledAt}; không đổi trạng thái hay thứ tự. */
    public CallResult call(UUID ticketId) {
        List<?> result = guard(() -> redis.execute(CALL, List.of(QueueKeys.ticket(ticketId)), String.valueOf(clock.millis())));
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("call_ticket.lua trả về kết quả không hợp lệ: " + result);
        }
        return new CallResult(outcome(String.valueOf(result.get(0))), Integer.parseInt(String.valueOf(result.get(1))));
    }

    /** Bắt đầu phục vụ: đúng một yêu cầu đồng thời nhận OK (NOT_WAITING → CLIN_1313, DOCTOR_BUSY → CLIN_1302). */
    public QueueOutcome start(UUID ticketId) {
        String outcome = guard(() -> redis.execute(START, List.of(QueueKeys.ticket(ticketId)), ticketId.toString(),
                String.valueOf(clock.millis()), String.valueOf(TICKET_TTL_SECONDS)));
        return outcome(outcome);
    }

    /** Kết thúc / hủy phiếu: gỡ khỏi mọi cấu trúc và xóa Hash. @return true nếu phiếu còn tồn tại trước đó. */
    public boolean finish(UUID ticketId) {
        Long deleted = guard(() -> redis.execute(FINISH, List.of(QueueKeys.ticket(ticketId)), ticketId.toString()));
        return deleted != null && deleted == 1L;
    }

    /** Bộ đếm số phiếu của ngày / loại hàng đợi đã tồn tại chưa (mất khi Redis bị xóa). */
    public boolean seqExists(LocalDate date, QueueType type) {
        return Boolean.TRUE.equals(guard(() -> redis.hasKey(QueueKeys.seq(date, type))));
    }

    /** Nâng bộ đếm số phiếu lên ít nhất {@code minimum} (số lớn nhất đã dùng trong lịch sử): chỉ tăng, không giảm. */
    public void ensureSeqAtLeast(LocalDate date, QueueType type, int minimum) {
        guard(() -> redis.execute(ENSURE_SEQ, List.of(QueueKeys.seq(date, type)), String.valueOf(minimum), String.valueOf(DEDUPE_TTL_SECONDS)));
    }

    /** Hàng đợi của ngày còn nguyên (khóa queue:meta tồn tại)? Mất khóa = Redis đã bị xóa, cần dựng lại từ MySQL. */
    public boolean metaPresent(LocalDate date) {
        return Boolean.TRUE.equals(guard(() -> redis.hasKey(QueueKeys.meta(date))));
    }

    /** Ghi dấu hàng đợi của ngày đã được dựng (giá trị = mốc thời gian dựng, hết hạn sau 48 giờ). */
    public void markMeta(LocalDate date, Instant at) {
        guard(() -> {
            redis.opsForValue().set(QueueKeys.meta(date), String.valueOf(at.toEpochMilli()), java.time.Duration.ofSeconds(DEDUPE_TTL_SECONDS));
            return Boolean.TRUE;
        });
    }

    // ================================================================== đọc

    /** Mã các phiếu đang CHỜ của một hàng đợi, theo thứ tự phục vụ (priority cao trước, cùng mức thì đến trước). */
    public List<UUID> waiting(LocalDate date, QueueType type, UUID doctorId) {
        java.util.Set<String> ids = guard(() -> redis.opsForZSet().range(QueueKeys.queue(date, type, doctorId), 0, -1));
        return ids == null ? List.of() : ids.stream().map(UUID::fromString).toList();
    }

    public long waitingCount(LocalDate date, QueueType type, UUID doctorId) {
        Long size = guard(() -> redis.opsForZSet().zCard(QueueKeys.queue(date, type, doctorId)));
        return size == null ? 0 : size;
    }

    /** Hash của phiếu (rỗng nếu không còn). */
    public Optional<Map<String, String>> ticket(UUID ticketId) {
        Map<Object, Object> raw = guard(() -> redis.opsForHash().entries(QueueKeys.ticket(ticketId)));
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        return Optional.of(fields);
    }

    public Optional<TicketView> view(UUID ticketId) {
        return ticket(ticketId).map(fields -> TicketView.from(ticketId, fields));
    }

    /** Phiếu CHỜ của một hàng đợi (đọc Hash từng phiếu), theo thứ tự phục vụ. */
    public List<TicketView> waitingViews(LocalDate date, QueueType type, UUID doctorId) {
        return waiting(date, type, doctorId).stream().map(this::view).flatMap(Optional::stream).toList();
    }

    /** Các phiếu đang mở (chờ hoặc đang phục vụ) của một đợt khám, đọc Hash từng phiếu; phiếu đã hết hạn / xóa bị bỏ qua. */
    public List<TicketView> ticketsOf(UUID visitId) {
        return openTicketsOf(visitId).stream().map(this::view).flatMap(Optional::stream).toList();
    }

    /** Các phiếu đang mở của một đợt khám. */
    public List<UUID> openTicketsOf(UUID visitId) {
        java.util.Set<String> ids = guard(() -> redis.opsForSet().members(QueueKeys.visit(visitId)));
        return ids == null ? List.of() : ids.stream().map(UUID::fromString).toList();
    }

    /** Bác sĩ có đang phục vụ một phiếu không (khóa queue:busy). */
    public boolean isDoctorBusy(UUID doctorId) {
        return Boolean.TRUE.equals(guard(() -> redis.hasKey(QueueKeys.busy(doctorId))));
    }

    // ================================================================== dựng tham số (tách riêng để kiểm tra không cần Redis)

    static List<String> issueKeys(IssueTicketRequest r) {
        return List.of(QueueKeys.queue(r.serviceDate(), r.queueType(), r.doctorId()), QueueKeys.seq(r.serviceDate(), r.queueType()),
                QueueKeys.dedupe(r.dedupeKey()), QueueKeys.visit(r.visitId()));
    }

    static List<String> issueArgs(IssueTicketRequest r, UUID ticketId) {
        List<String> args = new ArrayList<>();
        args.add(ticketId.toString());
        args.add(String.valueOf(QueueKeys.score(r.priority(), r.issuedAt().toEpochMilli())));
        args.add(String.valueOf(TICKET_TTL_SECONDS));
        args.add(String.valueOf(DEDUPE_TTL_SECONDS));
        field(args, "queueType", r.queueType().name());
        field(args, "serviceDate", QueueKeys.day(r.serviceDate()));
        field(args, "visitId", r.visitId().toString());
        field(args, "doctorId", r.doctorId() == null ? "" : r.doctorId().toString());
        field(args, "priority", String.valueOf(r.priority()));
        field(args, "issuedAt", String.valueOf(r.issuedAt().toEpochMilli()));
        field(args, "issuedBy", r.issuedBy() == null ? "" : r.issuedBy().toString());
        field(args, "vsdIds", join(r.vsdIds()));
        field(args, "petName", r.petName());
        field(args, "ownerName", r.ownerName());
        field(args, "roomNumber", r.roomNumber());
        return args;
    }

    private static void field(List<String> args, String name, String value) {
        args.add(name);
        args.add(value == null ? "" : value);
    }

    private static String join(Collection<UUID> ids) {
        return ids == null ? "" : String.join(",", ids.stream().map(UUID::toString).toList());
    }

    private static QueueOutcome outcome(String raw) {
        if (raw == null) {
            throw new IllegalStateException("Script Lua không trả về kết quả");
        }
        return QueueOutcome.valueOf(raw);
    }

    /** Lỗi hạ tầng Redis (mất kết nối, quá thời gian chờ) -> CLIN_1318; lỗi lập trình giữ nguyên. */
    private <T> T guard(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            log.warn("Redis hàng đợi không truy cập được: {}", e.getMessage());
            throw new AppException(ErrorCode.QUEUE_UNAVAILABLE);
        }
    }
}
