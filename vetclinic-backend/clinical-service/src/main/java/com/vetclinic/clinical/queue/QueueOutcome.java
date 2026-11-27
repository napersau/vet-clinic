package com.vetclinic.clinical.queue;

/** Kết quả trả về của các script Lua ({@code move / start / call}). */
public enum QueueOutcome {
    OK,
    /** Phiếu không còn trên Redis (đã xong, đã hết hạn hoặc chưa từng có). */
    NOT_FOUND,
    /** Phiếu không còn ở trạng thái chờ gọi (đã có người bắt đầu phục vụ): CLIN_1313. */
    NOT_WAITING,
    /** Bác sĩ đang phục vụ một phiếu khác: CLIN_1302. */
    DOCTOR_BUSY,
    /** Phiếu DOCTOR chưa gán Bác sĩ nên chưa bắt đầu được. */
    UNASSIGNED,
    /** Chỉ phiếu DOCTOR mới chuyển sang Bác sĩ khác được. */
    NOT_DOCTOR_QUEUE
}
