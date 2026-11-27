package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.dto.request.ChangePriceRequest;
import com.vetclinic.clinical.dto.request.CreateServiceRequest;
import com.vetclinic.clinical.dto.request.LabTestItemRequest;
import com.vetclinic.clinical.dto.request.ReferenceRangeRequest;
import com.vetclinic.clinical.dto.request.ServiceCategoryRequest;
import com.vetclinic.clinical.dto.request.ServiceLabItemsRequest;
import com.vetclinic.clinical.dto.request.SpeciesRequest;
import com.vetclinic.clinical.dto.request.UpdateLabTestItemRequest;
import com.vetclinic.clinical.dto.request.UpdateServiceRequest;
import com.vetclinic.clinical.dto.response.LabTestItemResponse;
import com.vetclinic.clinical.dto.response.ServiceCategoryResponse;
import com.vetclinic.clinical.dto.response.ServicePriceResponse;
import com.vetclinic.clinical.dto.response.ServiceResponse;
import com.vetclinic.clinical.dto.response.SpeciesResponse;
import com.vetclinic.clinical.service.CatalogService;
import com.vetclinic.common.dto.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Danh mục: loài (UC-07), nhóm dịch vụ và dịch vụ kèm bảng giá có lịch sử (UC-03, {@code /api/v1/catalog/**}), chỉ số xét nghiệm và
 * khoảng tham chiếu (UC-05, UC-06).
 */
@RestController
@RequiredArgsConstructor
public class CatalogController {

    private static final String ALL_STAFF = "hasAnyRole('RECEPTIONIST','VETERINARIAN','LAB_TECHNICIAN','IMAGING_TECHNICIAN','ADMIN')";

    private final CatalogService catalogService;

    // ------------------------------------------------------------------ loài

    @GetMapping("/api/v1/species")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<SpeciesResponse>>> listSpecies() {
        return ResponseEntity.ok(ApiResponse.success(catalogService.listSpecies()));
    }

    @PostMapping("/api/v1/species")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SpeciesResponse>> createSpecies(@Valid @RequestBody SpeciesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Thêm loài thành công", catalogService.createSpecies(request)));
    }

    @PutMapping("/api/v1/species/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SpeciesResponse>> updateSpecies(@PathVariable Short id,
                                                                      @Valid @RequestBody SpeciesRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật loài thành công", catalogService.updateSpecies(id, request)));
    }

    // ------------------------------------------------------------------ nhóm dịch vụ

    @GetMapping("/api/v1/catalog/service-categories")
    @PreAuthorize(ALL_STAFF)
    public ResponseEntity<ApiResponse<List<ServiceCategoryResponse>>> listCategories(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(ApiResponse.success(catalogService.listCategories(!includeInactive)));
    }

    @PostMapping("/api/v1/catalog/service-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServiceCategoryResponse>> createCategory(@Valid @RequestBody ServiceCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Thêm nhóm dịch vụ thành công", catalogService.createCategory(request)));
    }

    @PutMapping("/api/v1/catalog/service-categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServiceCategoryResponse>> updateCategory(@PathVariable Short id,
                                                                               @Valid @RequestBody ServiceCategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật nhóm dịch vụ thành công", catalogService.updateCategory(id, request)));
    }

    // ------------------------------------------------------------------ dịch vụ + bảng giá

    /** Bác sĩ chọn dịch vụ để chỉ định (chỉ dịch vụ đang dùng, kèm giá đang áp dụng); Admin xem cả dịch vụ đã ngừng (includeInactive=true). */
    @GetMapping("/api/v1/catalog/services")
    @PreAuthorize(ALL_STAFF)
    public ResponseEntity<ApiResponse<List<ServiceResponse>>> listServices(
            @RequestParam(required = false) String categoryCode,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(ApiResponse.success(catalogService.listServices(!includeInactive, categoryCode)));
    }

    @GetMapping("/api/v1/catalog/services/{id}")
    @PreAuthorize(ALL_STAFF)
    public ResponseEntity<ApiResponse<ServiceResponse>> getService(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(catalogService.getService(id)));
    }

    @PostMapping("/api/v1/catalog/services")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServiceResponse>> createService(@Valid @RequestBody CreateServiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Thêm dịch vụ thành công", catalogService.createService(request)));
    }

    @PutMapping("/api/v1/catalog/services/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServiceResponse>> updateService(@PathVariable UUID id,
                                                                      @Valid @RequestBody UpdateServiceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật dịch vụ thành công", catalogService.updateService(id, request)));
    }

    /** Lịch sử giá của dịch vụ, mới nhất trước. */
    @GetMapping("/api/v1/catalog/services/{id}/prices")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ServicePriceResponse>>> listPrices(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(catalogService.listPrices(id)));
    }

    /** Đổi giá = đóng dòng giá đang mở + thêm dòng mới. Hóa đơn đã lập giữ giá cũ (EX-20). */
    @PostMapping("/api/v1/catalog/services/{id}/prices")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServicePriceResponse>> changePrice(@PathVariable UUID id,
                                                                         @Valid @RequestBody ChangePriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Đã đặt giá mới", catalogService.changePrice(id, request)));
    }

    // ------------------------------------------------------------------ chỉ số xét nghiệm

    /** UC-05 (+UC-06): thêm chỉ số xét nghiệm kèm khoảng tham chiếu theo loài. */
    @PostMapping("/api/v1/clinical/lab-test-items")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<LabTestItemResponse>> createLabTestItem(@Valid @RequestBody LabTestItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Thêm chỉ số xét nghiệm thành công", catalogService.createLabTestItem(request)));
    }

    @PutMapping("/api/v1/clinical/lab-test-items/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<LabTestItemResponse>> updateLabTestItem(@PathVariable Short id,
                                                                              @Valid @RequestBody UpdateLabTestItemRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật chỉ số thành công", catalogService.updateLabTestItem(id, request)));
    }

    /** UC-06: đặt (thêm hoặc sửa) khoảng tham chiếu của chỉ số cho một loài. Kết quả cũ giữ snapshot khoảng cũ. */
    @PutMapping("/api/v1/clinical/lab-test-items/{id}/ranges/{speciesId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<LabTestItemResponse>> saveReferenceRange(@PathVariable Short id, @PathVariable Short speciesId,
                                                                               @Valid @RequestBody ReferenceRangeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã lưu khoảng tham chiếu", catalogService.saveReferenceRange(id, speciesId, request)));
    }

    /** Các chỉ số của một dịch vụ xét nghiệm (chưa cấu hình thì trả mọi chỉ số). */
    @GetMapping("/api/v1/clinical/services/{id}/lab-test-items")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN','LAB_TECHNICIAN','ADMIN')")
    public ResponseEntity<ApiResponse<List<LabTestItemResponse>>> serviceLabTestItems(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(catalogService.listServiceLabTestItems(id)));
    }

    @PutMapping("/api/v1/clinical/services/{id}/lab-test-items")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<LabTestItemResponse>>> setServiceLabTestItems(
            @PathVariable UUID id, @Valid @RequestBody ServiceLabItemsRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật chỉ số của dịch vụ",
                catalogService.setServiceLabTestItems(id, request.labTestItemIds())));
    }

    @GetMapping("/api/v1/clinical/lab-test-items")
    @PreAuthorize(ALL_STAFF)
    public ResponseEntity<ApiResponse<List<LabTestItemResponse>>> listLabTestItems() {
        return ResponseEntity.ok(ApiResponse.success(catalogService.listLabTestItems()));
    }
}
