package com.vetclinic.reporting.event;

import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.ServiceInvoiceRefundedEvent;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 8 listener dựng mô hình đọc báo cáo, mỗi listener một queue + DLQ ({@link ReportingRabbitConfig}). Việc xử lý nằm ở
 * {@link ReportingEventProcessor} (idempotent: dòng {@code processed_events}, dòng fact và bảng tổng hợp cùng một giao dịch; nhận trùng
 * eventId + tên listener thì bỏ qua), dùng chung với job dựng lại báo cáo. Lỗi thì RabbitMQ thử lại 3 lần (1s, 2s, 4s) rồi chuyển vào DLQ.
 * Phương thức để {@code void}: giá trị trả về của {@code @RabbitListener} sẽ bị coi là phản hồi RPC.
 */
@Component
@RequiredArgsConstructor
public class ReportingListeners {

    public static final String VISIT_CHECKED_IN = "reporting.visit-checked-in";
    public static final String VISIT_COMPLETED = "reporting.visit-completed";
    public static final String VISIT_CANCELLED = "reporting.visit-cancelled";
    public static final String INVOICE_SERVICE_PAID = "reporting.invoice-service-paid";
    public static final String INVOICE_SERVICE_REFUNDED = "reporting.invoice-service-refunded";
    public static final String MEDICINE_INVOICE_PAID = "reporting.medicine-invoice-paid";
    public static final String USER_PROFILE_UPDATED = "reporting.user-profile-updated";
    public static final String PET_UPDATED = "reporting.pet-updated";

    private final ReportingEventProcessor processor;

    @RabbitListener(queues = ReportingRabbitConfig.VISIT_CHECKED_IN_QUEUE)
    public void onVisitCheckedIn(VisitCheckedInEvent event) {
        processor.visitCheckedIn(event);
    }

    @RabbitListener(queues = ReportingRabbitConfig.VISIT_COMPLETED_QUEUE)
    public void onVisitCompleted(VisitCompletedEvent event) {
        processor.visitCompleted(event);
    }

    @RabbitListener(queues = ReportingRabbitConfig.VISIT_CANCELLED_QUEUE)
    public void onVisitCancelled(VisitCancelledEvent event) {
        processor.visitCancelled(event);
    }

    @RabbitListener(queues = ReportingRabbitConfig.INVOICE_SERVICE_PAID_QUEUE)
    public void onServiceInvoicePaid(ServiceInvoicePaidEvent event) {
        processor.serviceInvoicePaid(event);
    }

    @RabbitListener(queues = ReportingRabbitConfig.INVOICE_SERVICE_REFUNDED_QUEUE)
    public void onServiceInvoiceRefunded(ServiceInvoiceRefundedEvent event) {
        processor.serviceInvoiceRefunded(event);
    }

    @RabbitListener(queues = ReportingRabbitConfig.MEDICINE_INVOICE_PAID_QUEUE)
    public void onMedicineInvoicePaid(MedicineInvoicePaidEvent event) {
        processor.medicineInvoicePaid(event);
    }

    @RabbitListener(queues = ReportingRabbitConfig.USER_PROFILE_UPDATED_QUEUE)
    public void onUserProfileUpdated(UserProfileUpdatedEvent event) {
        processor.userProfileUpdated(event);
    }

    @RabbitListener(queues = ReportingRabbitConfig.PET_UPDATED_QUEUE)
    public void onPetUpdated(PetUpdatedEvent event) {
        processor.petUpdated(event);
    }
}
