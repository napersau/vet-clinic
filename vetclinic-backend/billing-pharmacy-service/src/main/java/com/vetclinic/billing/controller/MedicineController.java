package com.vetclinic.billing.controller;

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
import com.vetclinic.billing.service.MedicineService;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Danh mục thuốc (v4.0): thông tin + quy cách + thành phần; giá bán ở lịch sử giá ({@code /{id}/prices}); tồn kho ở {@code medicine_stocks}
 * ({@code /{id}/import}, {@code /{id}/adjust}). Thuốc đã phát sinh giao dịch không xóa cứng: ngừng kinh doanh bằng active = false.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
@RequiredArgsConstructor
public class MedicineController {

    private final MedicineService medicineService;

    /** Tra cứu khi kê đơn / bán: kèm quy cách, thành phần, giá hiện hành và tồn kho khả dụng. */
    @GetMapping("/medicines")
    @PreAuthorize("hasAnyRole('VETERINARIAN','RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<MedicineResponse>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "false") boolean lowStock,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(medicineService.search(keyword, categoryCode, active, lowStock, page, size)));
    }

    @GetMapping("/medicines/{id}")
    @PreAuthorize("hasAnyRole('VETERINARIAN','RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(medicineService.getById(id)));
    }

    @PostMapping("/medicines")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicineResponse>> create(@Valid @RequestBody MedicineRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Đã thêm thuốc vào danh mục", medicineService.create(request)));
    }

    @PutMapping("/medicines/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicineResponse>> update(@PathVariable UUID id, @Valid @RequestBody UpdateMedicineRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật thuốc", medicineService.update(id, request)));
    }

    /** Lịch sử giá bán, mới nhất trước (BR-38). */
    @GetMapping("/medicines/{id}/prices")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<MedicinePriceResponse>>> prices(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(medicineService.prices(id)));
    }

    /** Đặt giá mới (đóng dòng giá đang mở + thêm dòng mới); hóa đơn đã lập giữ giá cũ (EX-20). */
    @PostMapping("/medicines/{id}/prices")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicinePriceResponse>> changePrice(@PathVariable UUID id, @Valid @RequestBody ChangePriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created("Đã đặt giá mới", medicineService.changePrice(id, request)));
    }

    /** UC-37 / FR-66: nhập kho (Admin). */
    @PostMapping("/medicines/{id}/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicineResponse>> importStock(@PathVariable UUID id, @Valid @RequestBody InventoryImportRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã nhập kho", medicineService.importStock(id, request)));
    }

    /** UC-37 / FR-66: cân chỉnh kho sau kiểm kê (Admin). */
    @PostMapping("/medicines/{id}/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicineResponse>> adjustStock(@PathVariable UUID id, @Valid @RequestBody InventoryAdjustRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cân chỉnh kho", medicineService.adjustStock(id, request)));
    }

    /** Nhật ký biến động kho (audit), mới nhất trước. */
    @GetMapping("/medicines/{id}/transactions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<InventoryTransactionResponse>>> transactions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(medicineService.transactions(id, page, size)));
    }

    // ------------------------------------------------------------------ nhóm thuốc

    @GetMapping("/medicine-categories")
    @PreAuthorize("hasAnyRole('VETERINARIAN','RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<List<MedicineCategoryResponse>>> categories(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(ApiResponse.success(medicineService.categories(includeInactive)));
    }

    @PostMapping("/medicine-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicineCategoryResponse>> createCategory(@Valid @RequestBody MedicineCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created("Đã thêm nhóm thuốc", medicineService.createCategory(request)));
    }

    @PutMapping("/medicine-categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicineCategoryResponse>> updateCategory(@PathVariable Short id, @Valid @RequestBody MedicineCategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật nhóm thuốc", medicineService.updateCategory(id, request)));
    }
}
