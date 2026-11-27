package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.VisitSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Dựng lại phiếu xếp hàng trên Redis từ MySQL (Quy chuẩn v4.0 mục 9.3, mục 6). MySQL là nguồn sự thật; Redis chỉ giữ thứ tự phục vụ nên có thể mất
 * (Redis bị xóa, mất dữ liệu quá hạn AOF) hoặc thiếu phiếu (sự kiện vào DLQ vì Redis sập quá thời gian RabbitMQ thử lại, EX-18). Việc dựng lại phát đúng các
 * phiếu còn thiếu, theo thứ tự thời gian (số phiếu tăng dần theo giờ thu tiền):
 * <ul>
 *   <li>đợt khám {@code WAITING} đã nộp phí khám (hoặc cấp cứu, hoặc phí khám không thu trước) -> phiếu DOCTOR, giờ = giờ thu phí / giờ tiếp đón;</li>
 *   <li>đợt khám {@code RESULTS_READY} -> phiếu DOCTOR priority 1, giờ = lúc đủ kết quả;</li>
 *   <li>dịch vụ XN / CĐHA {@code PENDING} của đợt khám {@code WAITING_RESULTS} đã thu tiền (hoặc cấp cứu / không thu trước), chưa nằm trong phiếu nào
 *       -> một phiếu mỗi phòng.</li>
 * </ul>
 * Idempotent: bỏ qua đợt khám / dịch vụ đã có phiếu đang mở, và dùng cùng khóa dedupe với {@link QueueIssuerListener} nên chạy lại hay chạy đồng thời với
 * sự kiện bình thường không sinh phiếu thứ hai. {@code minAge}: chỉ dựng phiếu của những khoản đã quá hạn này (đối soát định kỳ chừa thời gian cho sự kiện
 * đang trên đường tới); {@link Duration#ZERO} khi Redis vừa bị xóa. Bộ đếm số phiếu được nâng lên bằng số lớn nhất trong lịch sử của ngày trước khi phát
 * (xem {@link QueueCoordinator#ensureSequence}) nên số phiếu mới không trùng lịch sử. Không dựng lại phiếu đang phục vụ (IN_SERVICE): thao tác kết thúc
 * của chúng bỏ qua êm khi không thấy phiếu.
 */
@Slf4j
@Component
public class QueueRebuildService {

    /** Kết quả một lần dựng. {@code ran = false}: đang có lần dựng khác chạy nên bỏ qua. */
    public record Result(boolean ran, int doctorTickets, int roomTickets, int alreadyIssued) {
        static Result busy() {
            return new Result(false, 0, 0, 0);
        }

        public int total() {
            return doctorTickets + roomTickets;
        }
    }

    private record Candidate(QueueType type, PetVisit visit, UUID doctorId, int priority, Instant at, List<UUID> detailIds, String key) {
    }

    private final PetVisitRepository visitRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final PetVisitStatusHistoryRepository historyRepository;
    private final VisitSupport support;
    private final QueueCoordinator queue;
    private final QueueRedisRepository redis;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final ReentrantLock lock = new ReentrantLock();

    public QueueRebuildService(PetVisitRepository visitRepository, VisitServiceDetailRepository detailRepository,
                               PetVisitStatusHistoryRepository historyRepository, VisitSupport support, QueueCoordinator queue,
                               QueueRedisRepository redis, Clock clock, PlatformTransactionManager transactionManager) {
        this.visitRepository = visitRepository;
        this.detailRepository = detailRepository;
        this.historyRepository = historyRepository;
        this.support = support;
        this.queue = queue;
        this.redis = redis;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** Hàng đợi của hôm nay còn nguyên (khóa queue:meta)? Redis lỗi thì ném CLIN_1318. */
    public boolean metaPresent() {
        return redis.metaPresent(LocalDate.now(clock));
    }

    public Result rebuild(Duration minAge) {
        if (!lock.tryLock()) {
            return Result.busy();
        }
        try {
            Result result = readTx.execute(s -> doRebuild(minAge));
            redis.markMeta(LocalDate.now(clock), clock.instant());
            return result;
        } finally {
            lock.unlock();
        }
    }

    private Result doRebuild(Duration minAge) {
        Instant cutoff = clock.instant().minus(minAge);
        List<Candidate> candidates = new ArrayList<>();
        collectDoctorWaiting(cutoff, candidates);
        collectResultsReady(cutoff, candidates);
        collectRooms(cutoff, candidates);
        // Thứ tự thời gian trong từng loại hàng đợi: số phiếu cấp lại tăng dần theo giờ thu tiền
        candidates.sort(Comparator.comparing((Candidate c) -> c.type().name()).thenComparing(Candidate::at));
        int doctor = 0;
        int room = 0;
        int already = 0;
        for (Candidate c : candidates) {
            IssuedTicket ticket = queue.issue(c.visit(), c.type(), c.doctorId(), c.priority(), c.at(), null, c.detailIds(), c.key(), null);
            if (ticket.duplicate()) {
                already++;
            } else if (c.type() == QueueType.DOCTOR) {
                doctor++;
            } else {
                room++;
            }
        }
        if (doctor + room > 0) {
            log.warn("Dựng lại hàng đợi Redis từ MySQL: {} phiếu Bác sĩ, {} phiếu phòng ({} đã có)", doctor, room, already);
        }
        return new Result(true, doctor, room, already);
    }

    // ------------------------------------------------------------------ phiếu DOCTOR của ca chờ khám

    private void collectDoctorWaiting(Instant cutoff, List<Candidate> out) {
        List<PetVisit> waiting = visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING);
        if (waiting.isEmpty()) {
            return;
        }
        Map<UUID, List<VisitServiceDetail>> detailsByVisit = detailsOf(waiting);
        Map<UUID, ServiceInvoice> invoices = support.invoicesOf(detailsByVisit.values().stream().flatMap(List::stream).map(VisitServiceDetail::getId).toList());
        for (PetVisit visit : waiting) {
            if (queue.activeDoctorTicket(visit.getId()).isPresent()) {
                continue;
            }
            Instant at;
            int priority = 0;
            if (visit.isEmergency()) {
                priority = 2;   // cấp cứu: khám trước, thu sau
                at = visit.getCheckedInAt();
            } else {
                VisitServiceDetail exam = detailsByVisit.getOrDefault(visit.getId(), List.of()).stream()
                        .filter(d -> d.getService().getCategory().is(ServiceCategory.EXAMINATION)).findFirst().orElse(null);
                if (exam == null) {
                    continue;
                }
                ServiceInvoice invoice = invoices.get(exam.getId());
                if (!exam.getService().isRequiresPrepayment()) {
                    at = visit.getCheckedInAt();   // phí khám không thu trước theo danh mục
                } else if (invoice != null && invoice.getPaymentStatus() == PaymentStatus.PAID && invoice.getPaidAt() != null) {
                    at = invoice.getPaidAt();      // thu phí khám xong mới có phiếu
                } else {
                    continue;                      // chưa nộp phí khám: nằm ở mục "chờ nộp phí", chưa có phiếu
                }
            }
            if (at.isAfter(cutoff)) {
                continue;                          // sự kiện còn đang trên đường tới
            }
            out.add(new Candidate(QueueType.DOCTOR, visit, visit.getDoctorId(), priority, at, List.of(), QueueCoordinator.doctorKey(visit.getId(), false)));
        }
    }

    // ------------------------------------------------------------------ phiếu DOCTOR ưu tiên của ca có kết quả

    private void collectResultsReady(Instant cutoff, List<Candidate> out) {
        for (PetVisit visit : visitRepository.findByStatusOrderByIdAsc(VisitStatus.RESULTS_READY)) {
            if (queue.activeDoctorTicket(visit.getId()).isPresent()) {
                continue;
            }
            Instant at = historyRepository.findByVisitIdOrderByIdAsc(visit.getId()).stream()
                    .filter(h -> h.getToStatus() == VisitStatus.RESULTS_READY).map(PetVisitStatusHistory::getCreatedAt)
                    .reduce((first, second) -> second).orElse(visit.getCheckedInAt());
            if (at.isAfter(cutoff)) {
                continue;
            }
            out.add(new Candidate(QueueType.DOCTOR, visit, visit.getDoctorId(), 1, at, List.of(), QueueCoordinator.doctorKey(visit.getId(), true)));
        }
    }

    // ------------------------------------------------------------------ phiếu phòng XN / CĐHA

    private void collectRooms(Instant cutoff, List<Candidate> out) {
        List<PetVisit> visits = visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING_RESULTS);
        if (visits.isEmpty()) {
            return;
        }
        Map<UUID, List<VisitServiceDetail>> detailsByVisit = detailsOf(visits);
        Map<UUID, ServiceInvoice> invoices = support.invoicesOf(detailsByVisit.values().stream().flatMap(List::stream).map(VisitServiceDetail::getId).toList());
        for (PetVisit visit : visits) {
            Set<UUID> ticketed = queue.openTicketsOf(visit.getId()).stream().filter(t -> t.queueType() != QueueType.DOCTOR)
                    .flatMap(t -> t.vsdIds().stream()).collect(Collectors.toSet());
            Map<QueueType, List<VisitServiceDetail>> byRoom = new LinkedHashMap<>();
            Map<UUID, Instant> times = new LinkedHashMap<>();
            for (VisitServiceDetail d : detailsByVisit.getOrDefault(visit.getId(), List.of())) {
                ServiceCategory category = d.getService().getCategory();
                if (!category.isParaclinical() || d.getStatus() != ServiceDetailStatus.PENDING || ticketed.contains(d.getId())) {
                    continue;
                }
                ServiceInvoice invoice = invoices.get(d.getId());
                boolean paid = invoice != null && invoice.getPaymentStatus() == PaymentStatus.PAID && invoice.getPaidAt() != null;
                // Đã thu tiền thì phiếu có từ giờ thu; ca cấp cứu / dịch vụ không thu trước có phiếu từ lúc chỉ định
                if (!paid && !visit.isEmergency() && d.getService().isRequiresPrepayment()) {
                    continue;
                }
                Instant at = paid ? invoice.getPaidAt() : d.getCreatedAt();
                if (at.isAfter(cutoff)) {
                    continue;
                }
                byRoom.computeIfAbsent(category.queueTypeOrNull(), k -> new ArrayList<>()).add(d);
                times.put(d.getId(), at);
            }
            byRoom.forEach((type, group) -> {
                List<UUID> ids = group.stream().map(VisitServiceDetail::getId).sorted().toList();
                Instant at = group.stream().map(d -> times.get(d.getId())).min(Comparator.naturalOrder()).orElseThrow();
                out.add(new Candidate(type, visit, null, visit.isEmergency() ? 2 : 0, at, ids, QueueCoordinator.roomKey(visit.getId(), type, ids)));
            });
        }
    }

    private Map<UUID, List<VisitServiceDetail>> detailsOf(List<PetVisit> visits) {
        return detailRepository.findByVisitIdInOrderByIdAsc(visits.stream().map(PetVisit::getId).toList()).stream()
                .collect(Collectors.groupingBy(VisitServiceDetail::getVisitId, LinkedHashMap::new, Collectors.toList()));
    }
}
