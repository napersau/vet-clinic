package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.dto.response.DoctorStatusResponse;

import java.util.UUID;
import java.util.List;

/**
 * Trạng thái phục vụ tức thời của Bác sĩ, TÍNH ĐỘNG từ các ca khám, không lưu cột trạng thái (state machine SM-02, FR-22):
 * <ul>
 *   <li>BUSY: đang có ca IN_PROGRESS (khám GĐ 1 hoặc GĐ 2).</li>
 *   <li>AVAILABLE: ngược lại. Ca WAITING đã gán KHÔNG làm Bác sĩ bận; không còn "Khóa ưu tiên".</li>
 * </ul>
 * Trạng thái chỉ để hiển thị và chặn Bác sĩ gọi thêm người khi đang khám (BR-12); KHÔNG dùng để chặn Lễ tân phân công.
 */
public interface DoctorStatusService {

    DoctorStatus statusOf(UUID doctorId);

    /** Bảng trạng thái Bác sĩ: mọi Bác sĩ đang ACTIVE kèm số phòng, trạng thái và số ca đang chờ. */
    List<DoctorStatusResponse> board();
}
