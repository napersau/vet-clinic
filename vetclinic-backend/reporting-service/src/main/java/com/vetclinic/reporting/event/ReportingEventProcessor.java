package com.vetclinic.reporting.event;

import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.ServiceInvoiceRefundedEvent;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import com.vetclinic.reporting.service.ReportingProjectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MỘT cách xử lý sự kiện cho cả hai đường vào: listener RabbitMQ ({@link ReportingListeners}) và job dựng lại báo cáo (phát lại từ outbox).
 * Mỗi sự kiện chạy trong {@link IdempotentConsumer} (dòng {@code processed_events} + dòng fact + bảng tổng hợp cùng một giao dịch) và dưới
 * khóa đọc của {@link ReportingGate}. Trả {@code true} nếu đã áp dụng, {@code false} nếu listener này đã xử lý sự kiện trước đó.
 */
@Component
@RequiredArgsConstructor
public class ReportingEventProcessor {

    private final ReportingProjectionService projection;
    private final IdempotentConsumer idempotentConsumer;
    private final ReportingGate gate;

    public boolean visitCheckedIn(VisitCheckedInEvent e) {
        return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.VISIT_CHECKED_IN, () -> projection.onVisitCheckedIn(e)));
    }

    public boolean visitCompleted(VisitCompletedEvent e) {
        return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.VISIT_COMPLETED, () -> projection.onVisitCompleted(e)));
    }

    public boolean visitCancelled(VisitCancelledEvent e) {
        return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.VISIT_CANCELLED, () -> projection.onVisitCancelled(e)));
    }

    public boolean serviceInvoicePaid(ServiceInvoicePaidEvent e) {
        return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.INVOICE_SERVICE_PAID, () -> projection.onServiceInvoicePaid(e)));
    }

    public boolean serviceInvoiceRefunded(ServiceInvoiceRefundedEvent e) {
        return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.INVOICE_SERVICE_REFUNDED, () -> projection.onServiceInvoiceRefunded(e)));
    }

    public boolean medicineInvoicePaid(MedicineInvoicePaidEvent e) {
        return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.MEDICINE_INVOICE_PAID, () -> projection.onMedicineInvoicePaid(e)));
    }

    public boolean userProfileUpdated(UserProfileUpdatedEvent e) {
        return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.USER_PROFILE_UPDATED, () -> projection.onUserProfileUpdated(e)));
    }

    public boolean petUpdated(PetUpdatedEvent e) {
        return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.PET_UPDATED, () -> projection.onPetUpdated(e)));
    }

    /** Phát lại một sự kiện đọc từ outbox: chọn đúng hàm theo kiểu sự kiện. */
    public boolean apply(DomainEvent event) {
        if (event instanceof VisitCheckedInEvent e) {
            return visitCheckedIn(e);
        } else if (event instanceof VisitCompletedEvent e) {
            return visitCompleted(e);
        } else if (event instanceof VisitCancelledEvent e) {
            return visitCancelled(e);
        } else if (event instanceof ServiceInvoicePaidEvent e) {
            return serviceInvoicePaid(e);
        } else if (event instanceof ServiceInvoiceRefundedEvent e) {
            return serviceInvoiceRefunded(e);
        } else if (event instanceof MedicineInvoicePaidEvent e) {
            return medicineInvoicePaid(e);
        } else if (event instanceof UserProfileUpdatedEvent e) {
            return userProfileUpdated(e);
        } else if (event instanceof PetUpdatedEvent e) {
            return petUpdated(e);
        }
        throw new IllegalArgumentException("reporting-service không xử lý sự kiện " + event.getClass().getSimpleName());
    }
}
