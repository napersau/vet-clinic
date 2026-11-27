package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.event.ClinicalRabbitConfig;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.common.event.ParaclinicalResultsReadyEvent;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phát phiếu xếp hàng trên Redis từ các sự kiện của chính clinical-service (Quy chuẩn v4.0 mục 9.3, mục 4):
 * <ul>
 *   <li>{@link ServiceInvoicePaidEvent}: phí khám -> phiếu DOCTOR; tiền XN / CĐHA -> phiếu phòng (dịch vụ cùng phòng của lần thu gộp một phiếu);</li>
 *   <li>{@link VisitCheckedInEvent} của ca cấp cứu -> phiếu DOCTOR priority 2 (khám trước, thu sau);</li>
 *   <li>{@link ParaclinicalResultsReadyEvent} (T06) -> phiếu DOCTOR priority 1 cho Bác sĩ khám chính, không thu tiền.</li>
 * </ul>
 * Không cần {@code processed_events}: chống phát trùng bằng khóa {@code queue:dedupe:visit:{visitId}:...} (chung với việc dựng lại / đối soát, xem
 * {@link QueueCoordinator#doctorKey}) ngay trong {@code issue_ticket.lua}. Lỗi (Redis không
 * truy cập được -> CLIN_1318) thì ném lên để RabbitMQ thử lại 3 lần rồi đưa vào DLQ; vì sự kiện nằm trong outbox, đã thu tiền thì chắc chắn tới được đây.
 * Trạng thái ca đọc lại từ MySQL lúc xử lý (không tin payload): ca đã hủy / đã rời bước chờ tương ứng thì không phát phiếu. Dòng đợt khám được KHÓA (FOR UPDATE)
 * suốt lúc kiểm tra + phát phiếu, cùng khóa với mọi chuyển trạng thái (VisitSupport.lock): hủy / đóng ca xảy ra trước thì listener thấy trạng thái mới và bỏ qua,
 * xảy ra sau thì thấy phiếu vừa phát và hủy nó - không còn phiếu mồ côi của ca đã đóng.
 */
@Slf4j
@Component
public class QueueIssuerListener {

    private final QueueCoordinator queue;
    private final PetVisitRepository visitRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final TransactionTemplate tx;

    public QueueIssuerListener(QueueCoordinator queue, PetVisitRepository visitRepository, VisitServiceDetailRepository detailRepository,
                               PlatformTransactionManager transactionManager) {
        this.queue = queue;
        this.visitRepository = visitRepository;
        this.detailRepository = detailRepository;
        this.tx = new TransactionTemplate(transactionManager);   // giao dịch ghi (không readOnly): khóa dòng đợt khám FOR UPDATE trong lúc phát phiếu
    }

    @RabbitListener(queues = ClinicalRabbitConfig.INVOICE_PAID_QUEUE)
    public void onInvoicePaid(ServiceInvoicePaidEvent event) {
        tx.executeWithoutResult(s -> issueForPaidInvoices(event));
    }

    private void issueForPaidInvoices(ServiceInvoicePaidEvent e) {
        PetVisit visit = visitRepository.findByIdForUpdate(e.visitId()).orElse(null);
        if (visit == null || visit.getStatus() == VisitStatus.CANCELLED) {
            return;
        }
        int priority = visit.isEmergency() ? 2 : 0;
        Map<String, List<ServiceInvoicePaidEvent.Line>> byQueue = new LinkedHashMap<>();
        for (ServiceInvoicePaidEvent.Line line : e.lines()) {
            if (line.queueType() != null) {   // dòng không có queueType (dịch vụ tại phòng) bỏ qua
                byQueue.computeIfAbsent(line.queueType(), k -> new ArrayList<>()).add(line);
            }
        }
        List<TicketView> open = queue.openTicketsOf(visit.getId());
        for (Map.Entry<String, List<ServiceInvoicePaidEvent.Line>> entry : byQueue.entrySet()) {
            QueueType type = QueueType.valueOf(entry.getKey());
            List<UUID> detailIds = entry.getValue().stream().map(ServiceInvoicePaidEvent.Line::visitServiceDetailId).toList();
            if (type == QueueType.DOCTOR) {
                // Phí khám thu SAU khi đã khám (ca cấp cứu) thì không phát phiếu nữa; tối đa 1 phiếu DOCTOR còn hiệu lực
                boolean hasTicket = open.stream().anyMatch(t -> t.queueType() == QueueType.DOCTOR);
                if (visit.getStatus() == VisitStatus.WAITING && !hasTicket) {
                    queue.issue(visit, QueueType.DOCTOR, visit.getDoctorId(), priority, e.paidAt(), e.cashierId(), detailIds,
                            QueueCoordinator.doctorKey(visit.getId(), false), null);
                }
            } else {
                Set<UUID> alreadyTicketed = open.stream().filter(t -> t.queueType() != QueueType.DOCTOR)
                        .flatMap(t -> t.vsdIds().stream()).collect(Collectors.toSet());
                Map<UUID, VisitServiceDetail> details = detailRepository.findAllById(detailIds).stream()
                        .collect(Collectors.toMap(VisitServiceDetail::getId, d -> d));
                // Bỏ qua dịch vụ đã làm / hủy, đã có phiếu (ca cấp cứu phát lúc chỉ định), hoặc ca đã rời bước chờ kết quả
                List<UUID> ticketable = visit.getStatus() != VisitStatus.WAITING_RESULTS ? List.of() : detailIds.stream()
                        .filter(id -> details.containsKey(id) && details.get(id).getStatus() == ServiceDetailStatus.PENDING
                                && !alreadyTicketed.contains(id)).toList();
                if (!ticketable.isEmpty()) {
                    queue.issue(visit, type, null, priority, e.paidAt(), e.cashierId(), ticketable, QueueCoordinator.roomKey(visit.getId(), type, ticketable), null);
                }
            }
        }
    }

    @RabbitListener(queues = ClinicalRabbitConfig.VISIT_CHECKED_IN_QUEUE)
    public void onVisitCheckedIn(VisitCheckedInEvent event) {
        tx.executeWithoutResult(s -> {
            PetVisit visit = visitRepository.findByIdForUpdate(event.visitId()).orElse(null);
            if (visit == null || visit.getStatus() != VisitStatus.WAITING || queue.activeDoctorTicket(visit.getId()).isPresent()) {
                return;
            }
            // Ca thường chờ nộp phí khám mới có phiếu; chỉ ca cấp cứu (khám trước, thu sau) hoặc phí khám không thu trước theo danh mục được phát ngay
            boolean noPrepayment = detailRepository.findByVisitIdOrderByIdAsc(visit.getId()).stream()
                    .anyMatch(d -> d.getService().getCategory().is(ServiceCategory.EXAMINATION) && !d.getService().isRequiresPrepayment());
            if (!event.emergency() && !noPrepayment) {
                return;
            }
            queue.issue(visit, QueueType.DOCTOR, visit.getDoctorId(), event.emergency() ? 2 : 0, event.occurredAt(), null, List.of(),
                    QueueCoordinator.doctorKey(visit.getId(), false), null);
        });
    }

    @RabbitListener(queues = ClinicalRabbitConfig.RESULTS_READY_QUEUE)
    public void onResultsReady(ParaclinicalResultsReadyEvent event) {
        tx.executeWithoutResult(s -> {
            PetVisit visit = visitRepository.findByIdForUpdate(event.visitId()).orElse(null);
            if (visit == null || visit.getStatus() != VisitStatus.RESULTS_READY || queue.activeDoctorTicket(visit.getId()).isPresent()) {
                return;
            }
            // T06: ca có đủ kết quả CLS -> phiếu ưu tiên 1 cho Bác sĩ khám chính, không thu tiền (đứng đầu hàng đợi, chỉ sau ca cấp cứu)
            queue.issue(visit, QueueType.DOCTOR, visit.getDoctorId(), 1, event.occurredAt(), null, List.of(), QueueCoordinator.doctorKey(visit.getId(), true), null);
        });
    }
}
