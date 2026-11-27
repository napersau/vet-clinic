package com.vetclinic.clinical.constant;

/**
 * Loại dịch vụ theo cách thực hiện, suy ra từ {@code service_categories.queue_type} (không còn ENUM cứng):
 * {@code PARACLINICAL} = queue_type LABORATORY / IMAGING (qua hàng đợi phòng Xét nghiệm / CĐHA);
 * {@code IN_ROOM} = queue_type NULL (tiêm, tẩy giun, thủ thuật... làm ngay tại phòng Bác sĩ).
 * Phí khám (queue_type DOCTOR) không thuộc loại nào.
 */
public enum ServiceKind {
    IN_ROOM,
    PARACLINICAL
}
