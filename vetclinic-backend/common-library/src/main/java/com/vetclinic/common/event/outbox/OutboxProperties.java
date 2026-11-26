package com.vetclinic.common.event.outbox;

import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình {@code vetclinic.outbox.*} (giá trị mặc định theo Kiến trúc v4.0 mục 5.3).
 * Lịch chạy của relay đọc trực tiếp {@code vetclinic.outbox.fixed-delay-ms} / {@code initial-delay-ms} trong {@code @Scheduled}.
 */
@Getter
@Setter
public class OutboxProperties {

    /** Số dòng tối đa mỗi lần quét. */
    private int batchSize = 100;

    /** Gửi lỗi đến lần thứ này thì chuyển FAILED. */
    private int maxAttempts = 10;

    /** Thời gian chờ RabbitMQ xác nhận (publisher confirm) cho mỗi sự kiện. */
    private long confirmTimeoutMs = 5000;

    /** Giữ dòng PUBLISHED bao nhiêu ngày trước khi xóa. */
    private int retentionDays = 7;

    /** Package chứa các lớp sự kiện; header {@code __TypeId__} = package + "." + event_type. */
    private String eventPackage = "com.vetclinic.common.event";
}
