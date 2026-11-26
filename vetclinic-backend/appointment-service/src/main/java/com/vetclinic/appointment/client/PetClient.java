package com.vetclinic.appointment.client;

import com.vetclinic.appointment.dto.response.PetSummaryResponse;
import com.vetclinic.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/**
 * HỢP ĐỒNG mà clinical-service cung cấp (Kiến trúc v4.0 §5.1), CHỈ ĐỌC:
 * <pre>GET /internal/pets/{id}  ->  ApiResponse&lt;{ id, ownerId, name, ownerName, ownerPhone }&gt;, 404 + PET_NOT_FOUND nếu không có / đã xóa mềm</pre>
 * Dùng lúc đặt lịch: kiểm tra thú cưng thuộc đúng chủ nuôi và chụp snapshot. Danh sách lịch hẹn không còn gọi sang đây (đã có
 * {@code owner_id} và snapshot trên appointments).
 */
@FeignClient(name = "clinical-service", path = "/internal/pets")
public interface PetClient {

    @GetMapping("/{id}")
    ApiResponse<PetSummaryResponse> getPet(@PathVariable("id") UUID id);
}
