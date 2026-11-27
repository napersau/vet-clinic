package com.vetclinic.clinical.client;

import com.vetclinic.clinical.dto.response.MedicineInfo;
import com.vetclinic.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/** Tra cứu thuốc ở billing-pharmacy-service (chỉ ĐỌC, gọi NGOÀI transaction). Endpoint nội bộ: không cần header người dùng. */
@FeignClient(name = "billing-pharmacy-service", contextId = "medicineClient", path = "/internal/medicines")
public interface MedicineClient {

    /** 404 PHARM_1405 nếu thuốc không có trong danh mục hoặc đã ngừng kinh doanh. */
    @GetMapping("/{id}")
    ApiResponse<MedicineInfo> getMedicine(@PathVariable("id") UUID id);
}
