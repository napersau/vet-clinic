package com.vetclinic.billing.event;

import com.vetclinic.billing.service.MedicineInvoiceService;
import com.vetclinic.common.event.PrescriptionCreatedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Bác sĩ chốt ca có đơn thuốc: tạo hóa đơn thuốc NHÁP (hoặc dựng lại nháp khi chốt lại sau T14). Idempotent: {@link IdempotentConsumer} ghi
 * {@code processed_events} cùng giao dịch với việc tạo hóa đơn, nên sự kiện giao lại (EX-19) không tạo hóa đơn thứ hai; thêm lớp bảo vệ
 * {@code medicine_invoices.prescription_id UNIQUE}. Lỗi thì retry 3 lần rồi vào DLQ.
 */
@Component
@RequiredArgsConstructor
public class PrescriptionCreatedListener {

    /** Tên listener ghi vào processed_events.consumer. */
    public static final String CONSUMER = "billing.prescription-created";

    private final MedicineInvoiceService invoiceService;
    private final IdempotentConsumer idempotentConsumer;

    @RabbitListener(queues = BillingRabbitConfig.PRESCRIPTION_CREATED_QUEUE)
    public void onPrescriptionCreated(PrescriptionCreatedEvent event) {
        idempotentConsumer.run(event.eventId(), CONSUMER, () -> invoiceService.createDraft(event));
    }
}
