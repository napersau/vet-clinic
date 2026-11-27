package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.DoctorQueueResponse;
import com.vetclinic.clinical.dto.response.QueueBoardResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.queue.QueueRedisRepository;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.service.DoctorQueueService;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import java.util.UUID;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Không @Transactional ở mức class: board() gọi OpenFeign lấy danh sách Bác sĩ NGOÀI transaction, phần đọc CSDL chạy trong một
 * transaction chỉ đọc riêng.
 */
@Service
public class DoctorQueueServiceImpl implements DoctorQueueService {

    private final PetVisitRepository visitRepository;
    private final QueueRedisRepository redis;
    private final VisitSupport support;
    private final AuthClient authClient;
    private final VisitAssembler assembler;
    private final Clock clock;
    private final TransactionTemplate readTx;

    public DoctorQueueServiceImpl(PetVisitRepository visitRepository, QueueRedisRepository redis, VisitSupport support,
                                  AuthClient authClient, VisitAssembler assembler, Clock clock, PlatformTransactionManager transactionManager) {
        this.visitRepository = visitRepository;
        this.redis = redis;
        this.support = support;
        this.authClient = authClient;
        this.assembler = assembler;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    @Override
    public QueueBoardResponse board() {
        ApiResponse<List<UserSummaryResponse>> response = authClient.getActiveDoctors();
        if (response == null || response.getData() == null) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        List<UserSummaryResponse> doctors = response.getData();
        Set<UUID> doctorIds = doctors.stream().map(UserSummaryResponse::id).collect(Collectors.toSet());

        // Hàng đợi đọc từ Redis (Sorted Set theo score) NGOÀI transaction MySQL: Redis lỗi thì CLIN_1318, không giữ kết nối CSDL
        LocalDate today = LocalDate.now(clock);
        List<TicketView> unassigned = redis.waitingViews(today, QueueType.DOCTOR, null);
        Map<UUID, List<TicketView>> byDoctor = new java.util.LinkedHashMap<>();   // giữ nguyên thứ tự phiếu bên trong mỗi Bác sĩ
        List<TicketView> assigned = new ArrayList<>();
        for (UUID doctorId : doctorIds) {
            List<TicketView> tickets = redis.waitingViews(today, QueueType.DOCTOR, doctorId);
            byDoctor.put(doctorId, tickets);
            assigned.addAll(tickets);
        }

        return readTx.execute(s -> {
            Set<UUID> busy = visitRepository.findDoctorIdsWithStatusIn(List.of(VisitStatus.IN_PROGRESS));

            // Mục "chờ thu phí khám": ca WAITING còn hóa đơn phí khám PENDING (chưa nộp phí, không phải cấp cứu); đọc từ MySQL nên đúng cả khi Redis
            // chậm / sập. Ca đã nộp phí nhưng phiếu chưa kịp phát (vài trăm ms) tạm không hiện ở đâu, rồi xuất hiện ở hàng đợi.
            List<PetVisit> waitingVisits = visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING);
            Set<UUID> unpaid = support.visitsAwaitingExamFee(waitingVisits);
            List<PetVisit> awaitingPayment = waitingVisits.stream().filter(v -> unpaid.contains(v.getId())).toList();

            Map<UUID, VisitResponse> byVisit = visitsOf(unassigned, assigned, awaitingPayment);

            List<DoctorQueueResponse> queues = doctors.stream().map(d -> {
                List<VisitResponse> visits = withTickets(byDoctor.getOrDefault(d.id(), List.of()), byVisit).stream()
                        .map(v -> v.withAssignedRoom(d.roomNumber())).toList();
                DoctorStatus status = busy.contains(d.id()) ? DoctorStatus.BUSY : DoctorStatus.AVAILABLE;
                return new DoctorQueueResponse(d.id(), d.fullName(), d.roomNumber(), status, visits.size(), visits);
            }).toList();

            return new QueueBoardResponse(withTickets(unassigned, byVisit), queues, suggest(queues),
                    awaitingPayment.stream().map(v -> byVisit.get(v.getId())).toList());
        });
    }

    /** Gợi ý Bác sĩ ÍT PHIẾU CHỜ NHẤT; hòa thì ưu tiên Bác sĩ đang rảnh, rồi theo id. Chỉ là gợi ý, Lễ tân quyết định. */
    static UUID suggest(List<DoctorQueueResponse> queues) {
        return queues.stream()
                .min(Comparator.comparingInt(DoctorQueueResponse::waitingCount)
                        .thenComparingInt(q -> q.status() == DoctorStatus.BUSY ? 1 : 0)
                        .thenComparing(DoctorQueueResponse::doctorId))
                .map(DoctorQueueResponse::doctorId).orElse(null);
    }

    @Override
    public List<VisitResponse> myQueue() {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        List<TicketView> tickets = redis.waitingViews(LocalDate.now(clock), QueueType.DOCTOR, doctorId);
        return readTx.execute(s -> withTickets(tickets, visitsOf(tickets, List.of(), List.of())));
    }

    // ================================================================== helper

    private Map<UUID, VisitResponse> visitsOf(List<TicketView> a, List<TicketView> b, List<PetVisit> extra) {
        Set<UUID> ids = new LinkedHashSet<>();
        a.forEach(t -> ids.add(t.visitId()));
        b.forEach(t -> ids.add(t.visitId()));
        List<PetVisit> visits = new ArrayList<>(ids.isEmpty() ? List.of() : visitRepository.findWithPetByIdIn(ids));
        extra.stream().filter(v -> !ids.contains(v.getId())).forEach(visits::add);
        return assembler.summaries(visits).stream().collect(Collectors.toMap(VisitResponse::id, Function.identity()));
    }

    /** Mỗi phiếu thành một dòng hàng đợi: ca khám kèm số phiếu, mức ưu tiên, giờ gọi gần nhất (giữ thứ tự phiếu). */
    private static List<VisitResponse> withTickets(List<TicketView> tickets, Map<UUID, VisitResponse> byVisit) {
        return tickets.stream()
                .filter(t -> byVisit.containsKey(t.visitId()))
                .map(t -> byVisit.get(t.visitId()).withTicket(t.info()))
                .toList();
    }
}
