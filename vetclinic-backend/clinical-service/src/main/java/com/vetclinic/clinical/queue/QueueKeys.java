package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Cấu trúc khóa Redis của hàng đợi (docs v4.0: ERD mục 3, cuối {@code schema_init.sql}). Ngày dạng {@code yyyyMMdd} theo giờ phòng khám.
 * Các script Lua dựng lại một số khóa này từ Hash phiếu (xem {@code resources/queue/lua}); đổi khuôn khóa ở đây thì phải đổi cả ở đó.
 */
public final class QueueKeys {

    /** Hàng đợi Bác sĩ của các ca chưa được phân công. */
    public static final String UNASSIGNED = "UNASSIGNED";
    /** Hàng đợi chung của phòng Xét nghiệm / CĐHA. */
    public static final String ALL = "ALL";
    /** score = (2 − priority) × 10^13 + mốc thời gian (epoch millis): 10^13 ms lớn hơn mọi mốc thực tế nên mức ưu tiên luôn thắng giờ phát. */
    static final long PRIORITY_STEP = 10_000_000_000_000L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private QueueKeys() {
    }

    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    /** Chủ của hàng đợi: Bác sĩ (hoặc UNASSIGNED) với DOCTOR, ALL với phòng XN / CĐHA. */
    public static String owner(QueueType type, UUID doctorId) {
        if (type != QueueType.DOCTOR) {
            return ALL;
        }
        return doctorId == null ? UNASSIGNED : doctorId.toString();
    }

    public static String queue(LocalDate date, QueueType type, UUID doctorId) {
        return "queue:" + day(date) + ":" + type.name() + ":" + owner(type, doctorId);
    }

    public static String seq(LocalDate date, QueueType type) {
        return "queue:seq:" + day(date) + ":" + type.name();
    }

    public static String dedupe(String dedupeKey) {
        return "queue:dedupe:" + dedupeKey;
    }

    public static String visit(UUID visitId) {
        return "queue:visit:" + visitId;
    }

    /** Dấu "hàng đợi của ngày này đã được dựng / còn nguyên": mất khóa này (Redis bị xóa) thì QueueRebuildJob / QueueReconcileJob dựng lại từ MySQL. */
    public static String meta(LocalDate date) {
        return "queue:meta:" + day(date);
    }

    public static String ticket(UUID ticketId) {
        return "ticket:" + ticketId;
    }

    public static String busy(UUID doctorId) {
        return "queue:busy:" + doctorId;
    }

    /** Điểm sắp xếp: priority 2 (cấp cứu) trước, rồi 1 (ca có kết quả CLS), rồi 0; cùng mức thì ai đến trước phục vụ trước. */
    public static long score(int priority, long epochMillis) {
        if (priority < 0 || priority > 2) {
            throw new IllegalArgumentException("priority phải thuộc 0..2: " + priority);
        }
        return (2 - priority) * PRIORITY_STEP + epochMillis;
    }
}
