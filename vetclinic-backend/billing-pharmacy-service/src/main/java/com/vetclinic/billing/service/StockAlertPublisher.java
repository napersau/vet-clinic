package com.vetclinic.billing.service;

import java.util.UUID;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.common.event.MedicineLowStockEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.util.UuidV7;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Cảnh báo tồn kho chạm ngưỡng ({@code pharmacy.low_stock}). Ghi sự kiện vào outbox CÙNG giao dịch với việc trừ / điều chỉnh
 * kho ({@code OutboxWriter} bắt buộc phải gọi trong giao dịch); OutboxRelay gửi sang RabbitMQ sau khi commit, nên giao dịch bị
 * rollback thì không có cảnh báo giả, còn RabbitMQ tạm tắt thì cảnh báo vẫn không mất.
 */
@Component
@RequiredArgsConstructor
public class StockAlertPublisher {

    /** aggregate_type của dòng outbox cảnh báo tồn kho (aggregate_id = id thuốc). */
    public static final String AGGREGATE = "MEDICINE";

    private final OutboxWriter outboxWriter;
    private final Clock clock;

    /** medicine phải là bản vừa refresh (tồn kho thật sau khi trừ). Phải gọi trong một giao dịch đang chạy. */
    public void publishIfLow(Medicine medicine, UUID actorUserId) {
        if (medicine.getStockQuantity() <= medicine.getMinStockAlert()) {
            outboxWriter.write(AGGREGATE, medicine.getId(), new MedicineLowStockEvent(UuidV7.generate(clock), clock.instant(),
                    actorUserId, medicine.getId(), medicine.getCode(), medicine.getName(), medicine.getStockQuantity(),
                    medicine.getMinStockAlert()));
        }
    }
}
