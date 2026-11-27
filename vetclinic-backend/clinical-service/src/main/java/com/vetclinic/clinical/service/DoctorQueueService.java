package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.response.QueueBoardResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;

import java.util.List;

/**
 * Hàng đợi riêng của từng Bác sĩ (cập nhật 17/11/2026): đọc từ PHIẾU XẾP HÀNG DOCTOR đang chờ ({@code queue_tickets},
 * thứ tự {@code priority DESC, issued_at, id}), không đọc từ pet_visits. Gọi số / bắt đầu phiếu ở {@link QueueTicketService}.
 */
public interface DoctorQueueService {

    /**
     * UC-20, FR-21: bảng điều phối của Lễ tân: mục chờ thu phí khám (ca chưa có phiếu), danh sách chờ phân công (phiếu chưa có
     * Bác sĩ), hàng đợi phiếu của từng Bác sĩ và Bác sĩ được gợi ý (ít phiếu chờ nhất).
     */
    QueueBoardResponse board();

    /** Hàng đợi của chính Bác sĩ đang đăng nhập: các ca có phiếu DOCTOR đang chờ, kèm số phiếu và giờ gọi gần nhất. */
    List<VisitResponse> myQueue();
}
