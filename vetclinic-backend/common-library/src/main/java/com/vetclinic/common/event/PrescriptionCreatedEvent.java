package com.vetclinic.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * clinical-service -> billing-pharmacy-service: Bác sĩ chốt ca (T05 / T09) và đợt khám có đơn thuốc. billing tạo (hoặc dựng lại, nếu chốt lại sau
 * khi mở lại hồ sơ T14) hóa đơn thuốc NHÁP {@code PENDING}; Thu ngân sửa số lượng theo khách rồi thu, hoặc hủy nếu khách mua ngoài. Ghi vào outbox
 * cùng giao dịch chốt ca. Mang đủ thông tin khách / thú cưng / Bác sĩ để in hóa đơn mà không gọi ngược clinical. Routing key {@code prescription.created}.
 */
public record PrescriptionCreatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // Bác sĩ chốt ca
        UUID prescriptionId,
        UUID visitId,
        String customerName,
        String customerPhone,
        String petName,
        String doctorName,
        List<Item> items
) implements DomainEvent {

    /** Một dòng thuốc trong đơn: số lượng Bác sĩ kê (billing lấy làm số lượng mặc định và để đối chiếu). */
    public record Item(UUID medicineId, int quantity) {
    }

    @Override
    public String routingKey() {
        return EventRouting.PRESCRIPTION_CREATED;
    }
}
