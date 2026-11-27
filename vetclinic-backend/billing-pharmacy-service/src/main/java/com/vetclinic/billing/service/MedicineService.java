package com.vetclinic.billing.service;

import com.vetclinic.billing.dto.request.ChangePriceRequest;
import com.vetclinic.billing.dto.request.InventoryAdjustRequest;
import com.vetclinic.billing.dto.request.InventoryImportRequest;
import com.vetclinic.billing.dto.request.MedicineCategoryRequest;
import com.vetclinic.billing.dto.request.MedicineRequest;
import com.vetclinic.billing.dto.request.UpdateMedicineRequest;
import com.vetclinic.billing.dto.response.InventoryTransactionResponse;
import com.vetclinic.billing.dto.response.MedicineCategoryResponse;
import com.vetclinic.billing.dto.response.MedicinePriceResponse;
import com.vetclinic.billing.dto.response.MedicineResponse;
import com.vetclinic.common.dto.PageResponse;

import java.util.List;
import java.util.UUID;

/** Danh mục thuốc, bảng giá có lịch sử và tồn kho (UC-37, docs v4.0: thuốc tách giá / tồn kho khỏi thông tin). */
public interface MedicineService {

    /** Admin thấy cả thuốc đã ngừng kinh doanh; các vai trò khác chỉ thấy thuốc đang kinh doanh. */
    PageResponse<MedicineResponse> search(String keyword, String categoryCode, Boolean active, boolean lowStockOnly, int page, int size);

    MedicineResponse getById(UUID id);

    MedicineResponse create(MedicineRequest request);

    MedicineResponse update(UUID id, UpdateMedicineRequest request);

    /** Lịch sử giá, mới nhất trước (Admin). */
    List<MedicinePriceResponse> prices(UUID id);

    /** Đặt giá bán mới: đóng dòng giá đang mở + thêm dòng mới (BR-38). Hóa đơn đã lập giữ giá cũ. */
    MedicinePriceResponse changePrice(UUID id, ChangePriceRequest request);

    /** FR-66: nhập kho, tăng tồn kho và ghi IMPORT (quantity dương, kèm giá nhập nếu có) vào nhật ký. */
    MedicineResponse importStock(UUID id, InventoryImportRequest request);

    /** FR-66: cân chỉnh sau kiểm kê (tăng hoặc giảm), ghi ADJUSTMENT (quantity có dấu) vào nhật ký. Không cho tồn kho âm. */
    MedicineResponse adjustStock(UUID id, InventoryAdjustRequest request);

    PageResponse<InventoryTransactionResponse> transactions(UUID id, int page, int size);

    List<MedicineCategoryResponse> categories(boolean includeInactive);

    MedicineCategoryResponse createCategory(MedicineCategoryRequest request);

    MedicineCategoryResponse updateCategory(Short id, MedicineCategoryRequest request);
}
