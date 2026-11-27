package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * billing-pharmacy-service -> notification-service: tồn kho chạm ngưỡng ({@code stock_quantity <= min_stock_alert}),
 * cảnh báo Admin (v3.0, thay LowStockEvent). Routing key {@code pharmacy.low_stock}.
 */
public record MedicineLowStockEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,
        UUID medicineId,
        String medicineCode,
        String medicineName,
        int stockQuantity,
        int minStockAlert
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.PHARMACY_LOW_STOCK;
    }
}
