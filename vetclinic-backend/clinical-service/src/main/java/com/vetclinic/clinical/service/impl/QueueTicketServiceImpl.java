package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.ServiceDetailAction;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.QueueTicketResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.queue.CallResult;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.clinical.queue.QueueOutcome;
import com.vetclinic.clinical.queue.QueueRedisRepository;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.QueueTicketService;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.PatientCalledEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phiếu xếp hàng nằm trên Redis (bước 6.2): "Gọi" chạy {@code call_ticket.lua} (không giao dịch MySQL, không xung đột phiên bản); "Bắt đầu" phiếu phòng
 * đổi dịch vụ sang đang thực hiện trong MỘT giao dịch MySQL rồi chạy {@code start_ticket.lua} sau commit. Không @Transactional ở mức class: tra số phòng
 * qua OpenFeign NGOÀI transaction, việc ghi chạy trong TransactionTemplate (READ_COMMITTED, cùng thứ tự khóa với các thao tác cận lâm sàng:
 * chỉ định -> đợt khám).
 */
@Service
public class QueueTicketServiceImpl implements QueueTicketService {

    private static final String ACTION_CALLED = "CALLED";

    private final QueueRedisRepository redis;
    private final QueueCoordinator queue;
    private final PetVisitRepository visitRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final VisitSupport support;
    private final AuthClient authClient;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public QueueTicketServiceImpl(QueueRedisRepository redis, QueueCoordinator queue, PetVisitRepository visitRepository,
                                  VisitServiceDetailRepository detailRepository, VisitSupport support, AuthClient authClient,
                                  ApplicationEventPublisher eventPublisher, Clock clock, PlatformTransactionManager transactionManager) {
        this.redis = redis;
        this.queue = queue;
        this.visitRepository = visitRepository;
        this.detailRepository = detailRepository;
        this.support = support;
        this.authClient = authClient;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ================================================================== Gọi (Q4, Q5)

    @Override
    public QueueTicketResponse callNext(QueueType type) {
        UUID userId = SecurityUtils.getCurrentUserId();
        assertCanServe(type);
        String room = roomOf(userId);   // Feign NGOÀI transaction
        // Q3: Bác sĩ đang khám dở thì chưa gọi thêm (CLIN_1302), xét trước khi xét hàng đợi trống (CLIN_1303)
        if (type == QueueType.DOCTOR && doctorBusy(userId)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);
        }
        List<TicketView> waiting = redis.waitingViews(LocalDate.now(clock), type, type == QueueType.DOCTOR ? userId : null);
        if (waiting.isEmpty()) {
            throw new AppException(ErrorCode.QUEUE_EMPTY);
        }
        return doCall(waiting.get(0), userId, room);
    }

    @Override
    public QueueTicketResponse call(UUID ticketId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        TicketView ticket = redis.view(ticketId).orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
        assertCanServe(ticket.queueType());
        String room = roomOf(userId);   // Feign NGOÀI transaction
        if (ticket.queueType() == QueueType.DOCTOR && !userId.equals(ticket.doctorId())) {
            throw new AppException(ErrorCode.NOT_ASSIGNED_DOCTOR);   // CLIN_1311: phiếu của hàng đợi Bác sĩ khác (hoặc chưa phân công)
        }
        return doCall(ticket, userId, room);
    }

    private QueueTicketResponse doCall(TicketView ticket, UUID userId, String room) {
        if (ticket.status() != TicketStatus.WAITING) {
            throw new AppException(ErrorCode.TICKET_NOT_WAITING);   // CLIN_1313
        }
        if (ticket.queueType() == QueueType.DOCTOR && doctorBusy(userId)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);   // BR-12: đang khám dở thì chưa gọi thêm
        }
        CallResult result = redis.call(ticket.id());   // call_ticket.lua: chỉ tăng calledCount + ghi lastCalledAt, thứ tự và trạng thái không đổi
        if (result.outcome() == QueueOutcome.NOT_FOUND) {
            throw new AppException(ErrorCode.TICKET_NOT_FOUND);
        }
        if (result.outcome() != QueueOutcome.OK) {
            throw new AppException(ErrorCode.TICKET_NOT_WAITING);
        }
        PetVisit visit = readTx.execute(s -> visitRepository.findWithPetById(ticket.visitId())
                .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND)));
        Instant now = clock.instant();
        eventPublisher.publishEvent(new PatientCalledEvent(UUID.randomUUID(), now, userId, ACTION_CALLED,
                station(ticket.queueType()), visit.getId(), visit.getVisitCode(), visit.getPet().getId(),
                visit.getDoctorId(), ticket.id(), ticket.ticketNo(), room));   // gửi thẳng (D9): chỉ để làm mới màn hình / báo khách
        return toResponse(ticket, now, visit, room);
    }

    private boolean doctorBusy(UUID doctorId) {
        return Boolean.TRUE.equals(readTx.execute(s -> visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)));
    }

    // ================================================================== Bắt đầu phiếu phòng XN / CĐHA

    @Override
    public QueueTicketResponse start(UUID ticketId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        TicketView peek = redis.view(ticketId).orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
        if (peek.queueType() == QueueType.DOCTOR) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Phiếu phòng Bác sĩ bắt đầu bằng \"Bắt đầu khám\" / \"Bắt đầu Giai đoạn 2\" trên hồ sơ ca");
        }
        assertCanServe(peek.queueType());
        if (peek.status() != TicketStatus.WAITING) {
            throw new AppException(ErrorCode.TICKET_NOT_WAITING);
        }
        return tx.execute(s -> {
            // Khóa theo cùng thứ tự với các thao tác cận lâm sàng khác: các chỉ định -> đợt khám. Nhiều người cùng bấm "Bắt đầu" một phiếu:
            // người đầu đổi dịch vụ sang đang thực hiện, những người sau chờ khóa rồi thấy không còn dịch vụ chờ -> CLIN_1313
            List<UUID> detailIds = peek.vsdIds().stream().sorted().toList();
            List<VisitServiceDetail> details = detailIds.stream()
                    .map(id -> detailRepository.findByIdForUpdate(id)
                            .orElseThrow(() -> new AppException(ErrorCode.SERVICE_DETAIL_NOT_FOUND)))
                    .toList();
            PetVisit visit = support.lock(peek.visitId());
            if (visit.getStatus() != VisitStatus.WAITING_RESULTS) {
                throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION, "Đợt khám không còn ở trạng thái chờ kết quả cận lâm sàng");
            }
            boolean started = false;
            for (VisitServiceDetail d : details) {
                if (d.getStatus() == ServiceDetailStatus.PENDING) {
                    d.setStatus(ServiceDetailStatus.IN_PROGRESS);
                    detailRepository.save(d);
                    support.recordDetail(d.getId(), ServiceDetailStatus.PENDING, ServiceDetailStatus.IN_PROGRESS,
                            ServiceDetailAction.START_PROCESSING, userId, null);
                    started = true;
                }
            }
            if (!started) {
                throw new AppException(ErrorCode.TICKET_NOT_WAITING, "Phiếu không còn dịch vụ nào chờ thực hiện");
            }
            queue.startRoomTicketAfterCommit(ticketId);   // start_ticket.lua sau commit: phiếu IN_SERVICE, rời hàng đợi chờ
            PetVisit withPet = visitRepository.findWithPetById(visit.getId()).orElse(visit);
            return toResponse(peek, null, withPet, null, TicketStatus.IN_SERVICE);
        });
    }

    // ================================================================== helper

    /** Bác sĩ chỉ thao tác hàng đợi Bác sĩ; KTV chỉ thao tác hàng đợi phòng mình. */
    private static void assertCanServe(QueueType type) {
        RoleCode role = SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        boolean ok = switch (type) {
            case DOCTOR -> role == RoleCode.VETERINARIAN;
            case LABORATORY -> role == RoleCode.LAB_TECHNICIAN;
            case IMAGING -> role == RoleCode.IMAGING_TECHNICIAN;
        };
        if (!ok) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static String station(QueueType type) {
        return switch (type) {
            case DOCTOR -> "DOCTOR_ROOM";
            case LABORATORY -> "LAB";
            case IMAGING -> "IMAGING";
        };
    }

    private static QueueTicketResponse toResponse(TicketView t, Instant calledAt, PetVisit v, String room) {
        return toResponse(t, calledAt, v, room, t.status());
    }

    private static QueueTicketResponse toResponse(TicketView t, Instant calledAt, PetVisit v, String room, TicketStatus status) {
        return new QueueTicketResponse(t.id(), t.queueType(), t.ticketNo(), t.priority(), status, t.issuedAt(),
                calledAt != null ? calledAt : t.lastCalledAt(), v.getId(), v.getVisitCode(),
                v.getPet() == null ? null : v.getPet().getName(), t.doctorId(), room);
    }

    /** Số phòng của người gọi để hiển thị; không tra được thì bỏ trống, không làm hỏng việc gọi số. */
    private String roomOf(UUID userId) {
        try {
            ApiResponse<UserSummaryResponse> response = authClient.getUser(userId);
            return response == null || response.getData() == null ? null : response.getData().roomNumber();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
