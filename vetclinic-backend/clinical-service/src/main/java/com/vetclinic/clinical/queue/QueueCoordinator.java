package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.QueueTicketHistory;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.QueueTicketHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.common.event.QueueTicketIssuedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cầu nối giữa nghiệp vụ khám (MySQL, giao dịch) và phiếu xếp hàng trên Redis (thay {@code TicketIssuer} của v3.0; Quy chuẩn v4.0 mục 9.3). Nguyên tắc:
 * <ul>
 *   <li>MySQL là nguồn sự thật. Phiếu KHÔNG được phát trong giao dịch thu tiền: giao dịch chỉ ghi hóa đơn + outbox {@code ServiceInvoicePaidEvent},
 *       {@link QueueIssuerListener} phát phiếu sau đó (đã thu tiền thì chắc chắn có phiếu, kể cả khi Redis sập, EX-18).</li>
 *   <li>Lịch sử phiếu kết thúc ({@code queue_ticket_histories}) ghi TRONG giao dịch của người gọi; lệnh Lua đổi / xóa phiếu chạy SAU KHI commit.
 *       Lua lỗi sau commit thì chỉ ghi log (trạng thái nghiệp vụ đã đúng; {@code QueueRebuildJob} ở bước 6.3 sửa lệch).</li>
 *   <li>Đọc phiếu lỗi Redis -> CLIN_1318.</li>
 * </ul>
 */
@Slf4j
@Component
public class QueueCoordinator {

    private final QueueRedisRepository redis;
    private final QueueTicketHistoryRepository histories;
    private final VisitServiceDetailRepository detailRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public QueueCoordinator(QueueRedisRepository redis, QueueTicketHistoryRepository histories, VisitServiceDetailRepository detailRepository,
                            ApplicationEventPublisher eventPublisher, Clock clock) {
        this.redis = redis;
        this.histories = histories;
        this.detailRepository = detailRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // ================================================================== khóa chống phát trùng (dùng chung cho listener, ca cấp cứu và dựng lại)

    /**
     * Một đợt khám có tối đa một phiếu DOCTOR cho mỗi giai đoạn: lần đầu (đã nộp phí / cấp cứu) và lần quay lại với kết quả CLS ({@code results = true}).
     * Cùng một khóa cho sự kiện, ca cấp cứu và việc dựng lại / đối soát, nên một phiếu không bao giờ bị phát hai lần dù được nhiều đường cùng kích hoạt.
     */
    public static String doctorKey(UUID visitId, boolean results) {
        return "visit:" + visitId + ":DOCTOR:" + (results ? "RESULTS" : "FIRST");
    }

    /** Phiếu phòng XN / CĐHA: khóa theo đợt khám + phòng + dịch vụ có id nhỏ nhất của nhóm (nhóm khác nhau của cùng phòng có khóa khác nhau). */
    public static String roomKey(UUID visitId, QueueType type, java.util.Collection<UUID> detailIds) {
        UUID first = detailIds.stream().min(UUID::compareTo).orElseThrow();
        return "visit:" + visitId + ":" + type.name() + ":" + first;
    }

    // ================================================================== đọc

    /** Phiếu đang mở (chờ hoặc đang phục vụ) của một đợt khám. */
    public List<TicketView> openTicketsOf(UUID visitId) {
        return redis.ticketsOf(visitId);
    }

    /** Phiếu DOCTOR còn hiệu lực của đợt khám (chờ hoặc đang phục vụ). */
    public Optional<TicketView> activeDoctorTicket(UUID visitId) {
        return openTicketsOf(visitId).stream().filter(t -> t.queueType() == QueueType.DOCTOR).findFirst();
    }

    /** Phiếu phòng XN / CĐHA đang mở chứa dịch vụ này (rỗng nếu dịch vụ chưa có phiếu: khách chưa nộp tiền, hoặc dịch vụ không cần phiếu). */
    public Optional<TicketView> roomTicketOf(UUID visitId, UUID detailId) {
        return openTicketsOf(visitId).stream().filter(t -> t.queueType() != QueueType.DOCTOR && t.vsdIds().contains(detailId)).findFirst();
    }

    // ================================================================== phát phiếu (gọi từ QueueIssuerListener hoặc ca cấp cứu)

    /** Phát phiếu ngay (không cần giao dịch): gửi QueueTicketIssuedEvent nếu là phiếu mới. */
    public IssuedTicket issue(PetVisit visit, QueueType type, UUID doctorId, int priority, Instant at, UUID issuedBy, List<UUID> vsdIds,
                              String dedupeKey, String roomNumber) {
        return doIssue(requestOf(visit, type, doctorId, priority, at, issuedBy, vsdIds, dedupeKey, roomNumber), visit.getVisitCode(), visit.getPet().getId());
    }

    /**
     * Ca cấp cứu chỉ định xét nghiệm / CĐHA (khám trước, thu sau): phiếu phòng phát ngay SAU KHI giao dịch chỉ định commit. Tên thú cưng / chủ nuôi
     * được chụp NGAY (trong giao dịch) vì sau commit không còn tải lười được. Lỗi Redis chỉ ghi log (6.3 dựng lại).
     */
    public void issueAfterCommit(PetVisit visit, QueueType type, int priority, Instant at, List<UUID> vsdIds, String dedupeKey) {
        IssueTicketRequest request = requestOf(visit, type, null, priority, at, null, vsdIds, dedupeKey, null);
        String visitCode = visit.getVisitCode();
        UUID petId = visit.getPet().getId();
        afterCommit(() -> doIssue(request, visitCode, petId));
    }

    private IssueTicketRequest requestOf(PetVisit visit, QueueType type, UUID doctorId, int priority, Instant at, UUID issuedBy,
                                         List<UUID> vsdIds, String dedupeKey, String roomNumber) {
        return new IssueTicketRequest(dedupeKey, type, LocalDate.now(clock), type == QueueType.DOCTOR ? doctorId : null, visit.getId(), priority, at,
                issuedBy, vsdIds, visit.getPet().getName(), visit.getPet().getOwnerNameSnapshot(), roomNumber);
    }

    /**
     * Bộ đếm số phiếu bị mất (Redis bị xóa) thì khởi tạo lại từ số lớn nhất đã dùng trong lịch sử của ngày: đếm lại từ 1 sẽ trùng UNIQUE
     * (queue_type, service_date, ticket_no) của queue_ticket_histories. Bộ đếm còn thì chỉ tốn một lệnh EXISTS.
     */
    void ensureSequence(LocalDate date, QueueType type) {
        if (!redis.seqExists(date, type)) {
            Integer max = histories.findMaxTicketNo(type, date);
            if (max != null && max > 0) {
                redis.ensureSeqAtLeast(date, type, max);
            }
        }
    }

    private IssuedTicket doIssue(IssueTicketRequest request, String visitCode, UUID petId) {
        ensureSequence(request.serviceDate(), request.queueType());
        IssuedTicket ticket = redis.issue(request);
        if (!ticket.duplicate()) {
            // Gửi thẳng sau khi phát (D9): làm mới hàng đợi, báo số phiếu cho chủ nuôi; mất sự kiện này thì màn hình vẫn đúng ở lần tải lại
            eventPublisher.publishEvent(new QueueTicketIssuedEvent(UUID.randomUUID(), clock.instant(), request.issuedBy(), ticket.ticketId(),
                    request.queueType().name(), ticket.ticketNo() == null ? 0 : ticket.ticketNo(), request.visitId(), visitCode, petId,
                    request.doctorId(), request.priority()));
        }
        return ticket;
    }

    // ================================================================== vòng đời phiếu DOCTOR (T02 / T04 / T05 / T07 / T09 / T11)

    /**
     * T02 / T07: Bác sĩ bấm "Bắt đầu". Ca phải có phiếu DOCTOR đang chờ (đã nộp phí khám, ca cấp cứu, hoặc phiếu ưu tiên của ca có kết quả):
     * thanh toán tách khỏi khám (BR-35), thiếu phiếu thì CLIN_1314. Phiếu -> IN_SERVICE bằng {@code start_ticket.lua} SAU KHI giao dịch commit.
     */
    public TicketView startDoctorTicket(UUID visitId, UUID doctorId) {
        TicketView ticket = openTicketsOf(visitId).stream()
                .filter(t -> t.queueType() == QueueType.DOCTOR && t.status() == TicketStatus.WAITING).findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.NO_QUEUE_TICKET));
        afterCommit(() -> {
            QueueOutcome outcome = redis.start(ticket.id());
            if (outcome == QueueOutcome.UNASSIGNED) {   // phiếu còn nằm ở danh sách chờ phân công (phân công vừa commit): chuyển rồi bắt đầu lại
                redis.move(ticket.id(), doctorId);
                outcome = redis.start(ticket.id());
            }
            if (outcome != QueueOutcome.OK) {
                log.warn("start_ticket.lua phiếu {} của đợt khám {}: {} (MySQL đã chuyển ca sang đang khám)", ticket.id(), visitId, outcome);
            }
        });
        return ticket;
    }

    /** T04 / T05 / T09: Bác sĩ rời ca -> phiếu DOCTOR đang phục vụ DONE (ghi lịch sử trong giao dịch). Không có phiếu (mở lại hồ sơ, T14) thì bỏ qua. */
    public void finishDoctorTicket(UUID visitId) {
        openTicketsOf(visitId).stream()
                .filter(t -> t.queueType() == QueueType.DOCTOR && t.status() == TicketStatus.IN_SERVICE)
                .forEach(t -> end(t, TicketStatus.DONE, null));
    }

    /** Lễ tân đổi / gỡ Bác sĩ (Q3, EX-17): phiếu DOCTOR đang chờ đi theo bằng {@code move_ticket.lua} sau commit, GIỮ NGUYÊN số phiếu và score. */
    public void reassignDoctorTicket(UUID visitId, UUID doctorId) {
        afterCommit(() -> openTicketsOf(visitId).stream()
                .filter(t -> t.queueType() == QueueType.DOCTOR && t.status() == TicketStatus.WAITING)
                .forEach(t -> redis.move(t.id(), doctorId)));
    }

    /** Khách bỏ về / hủy ca (T03, T08, T11, T12): mọi phiếu còn hiệu lực của ca -> CANCELLED (lịch sử trong giao dịch, xóa khỏi Redis sau commit). */
    public void cancelActiveTickets(UUID visitId, String reason) {
        openTicketsOf(visitId).forEach(t -> end(t, TicketStatus.CANCELLED, reason));
    }

    // ================================================================== vòng đời phiếu phòng XN / CĐHA

    /**
     * Sau khi một dịch vụ của phiếu phòng hoàn tất / bị hủy (SM-07): phiếu đang chờ mà không còn dịch vụ chờ -> CANCELLED; phiếu đang làm
     * (IN_SERVICE) mà mọi dịch vụ đã xong / hủy -> DONE (kể cả khi dịch vụ bị hủy giữa chừng).
     */
    public void refreshRoomTicketOf(UUID visitId, UUID detailId) {
        roomTicketOf(visitId, detailId).ifPresent(ticket -> {
            List<VisitServiceDetail> details = detailRepository.findAllById(ticket.vsdIds());
            boolean anyPending = details.stream().anyMatch(d -> d.getStatus() == ServiceDetailStatus.PENDING);
            boolean anyOpen = details.stream().anyMatch(d -> ServiceDetailStatus.OPEN.contains(d.getStatus()));
            if (ticket.status() == TicketStatus.WAITING && !anyPending) {
                end(ticket, TicketStatus.CANCELLED, "Mọi dịch vụ của phiếu đã bị hủy");
            } else if (ticket.status() == TicketStatus.IN_SERVICE && !anyOpen) {
                end(ticket, TicketStatus.DONE, null);
            }
        });
    }

    /** Dịch vụ XN / CĐHA của phiếu vừa chuyển sang đang thực hiện: sau commit chạy {@code start_ticket.lua} (phiếu IN_SERVICE, rời hàng đợi chờ). */
    public void startRoomTicketAfterCommit(UUID ticketId) {
        afterCommit(() -> {
            QueueOutcome outcome = redis.start(ticketId);
            if (outcome != QueueOutcome.OK) {
                log.warn("start_ticket.lua phiếu phòng {}: {} (MySQL đã chuyển dịch vụ sang đang thực hiện)", ticketId, outcome);
            }
        });
    }

    // ================================================================== kết thúc phiếu: lịch sử (trong giao dịch) + xóa khỏi Redis (sau commit)

    private void end(TicketView t, TicketStatus finalStatus, String reason) {
        recordHistory(t, finalStatus, reason);
        afterCommit(() -> redis.finish(t.id()));
    }

    /** Ghi một dòng lịch sử cho phiếu kết thúc; {@code ticket_id UNIQUE} + kiểm tra tồn tại chặn ghi trùng khi thử lại. */
    void recordHistory(TicketView t, TicketStatus finalStatus, String reason) {
        if (histories.existsByTicketId(t.id())) {
            return;
        }
        if (histories.existsByQueueTypeAndServiceDateAndTicketNo(t.queueType(), t.serviceDate(), t.ticketNo())) {
            // Redis mất bộ đếm số phiếu rồi cấp lại từ 1 (UNIQUE queue_type + service_date + ticket_no của lịch sử): lịch sử chỉ để tra cứu / thống kê, KHÔNG được
            // làm hỏng thao tác nghiệp vụ đang chạy (chốt ca, hủy ca...) nên bỏ qua dòng này và ghi log để QueueRebuildJob / quản trị xử lý (6.3)
            log.error("Lịch sử phiếu {} #{} ngày {} đã có số này (bộ đếm Redis bị cấp lại?): bỏ qua dòng lịch sử của phiếu {}",
                    t.queueType(), t.ticketNo(), t.serviceDate(), t.id());
            return;
        }
        histories.save(QueueTicketHistory.builder()
                .ticketId(t.id())
                .queueType(t.queueType())
                .serviceDate(t.serviceDate())
                .ticketNo(t.ticketNo())
                .visitId(t.visitId())
                .doctorId(t.doctorId())
                .priority(t.priority())
                .finalStatus(finalStatus)
                .issuedAt(t.issuedAt() == null ? clock.instant() : t.issuedAt())
                .issuedBy(t.issuedBy())
                .calledCount(t.calledCount())
                .startedAt(t.startedAt())
                .endedAt(clock.instant())
                .cancelReason(reason == null ? null : reason.length() > 255 ? reason.substring(0, 255) : reason)
                .detailIds(List.copyOf(t.vsdIds()))
                .build());
    }

    // ================================================================== sau commit

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safely(action);
                }
            });
        } else {
            safely(action);
        }
    }

    /** Sau commit không được ném lỗi ngược lên người gọi (giao dịch đã xong): chỉ ghi log. */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Thao tác Redis hàng đợi sau commit lỗi (MySQL đã đúng, cần dựng lại hàng đợi): {}", e.getMessage());
        }
    }
}
