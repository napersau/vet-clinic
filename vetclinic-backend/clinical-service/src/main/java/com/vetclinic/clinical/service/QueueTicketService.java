package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.dto.response.QueueTicketResponse;

import java.util.UUID;

/**
 * Thao tác trên PHIẾU XẾP HÀNG (SM-07, BR-34): hàng đợi Bác sĩ, phòng Xét nghiệm, phòng CĐHA đều là danh sách phiếu đang chờ trên Redis (Sorted Set,
 * {@code score = (2 − priority) × 10^13 + giờ phát}). "Gọi" chỉ tăng calledCount và ghi lastCalledAt, không đổi trạng thái hay thứ tự.
 */
public interface QueueTicketService {

    /** "Gọi số tiếp theo": gọi phiếu đứng đầu hàng đợi của người đang thao tác (CLIN_1303 nếu trống). */
    QueueTicketResponse callNext(QueueType type);

    /**
     * "Gọi" / "Gọi lại" một phiếu bất kỳ: phiếu DOCTOR phải thuộc Bác sĩ đang thao tác (CLIN_1311), phiếu phòng chỉ KTV của phòng
     * đó gọi được; phiếu phải đang chờ (CLIN_1313); Bác sĩ đang khám dở thì chưa gọi thêm (CLIN_1302).
     */
    QueueTicketResponse call(UUID ticketId);

    /**
     * Phòng Xét nghiệm / CĐHA bấm "Bắt đầu" khi thú cưng vào phòng: phiếu -> IN_SERVICE, mọi dịch vụ chờ của phiếu
     * PENDING -> IN_PROGRESS (action START_PROCESSING). Phiếu DOCTOR bắt đầu bằng start-exam / start-phase2.
     */
    QueueTicketResponse start(UUID ticketId);
}
