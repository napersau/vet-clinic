package com.vetclinic.common.event.outbox;

/** {@code outbox_events.status} (enum Java lưu VARCHAR, DANH_SACH_KIEU_ENUM_HE_THONG.md v4.0 #16). */
public enum OutboxStatus {
    /** Chưa gửi được (hoặc chờ gửi lại tới {@code next_attempt_at}). */
    PENDING,
    /** RabbitMQ đã xác nhận nhận sự kiện. */
    PUBLISHED,
    /** Gửi lỗi quá số lần cho phép; Quản trị viên xem và gửi lại. */
    FAILED
}
