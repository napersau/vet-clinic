package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.request.ChangePriceRequest;
import com.vetclinic.clinical.dto.request.CreateServiceRequest;
import com.vetclinic.clinical.dto.request.LabTestItemRequest;
import com.vetclinic.clinical.dto.request.ReferenceRangeRequest;
import com.vetclinic.clinical.dto.request.ServiceCategoryRequest;
import com.vetclinic.clinical.dto.request.SpeciesRequest;
import com.vetclinic.clinical.dto.request.UpdateLabTestItemRequest;
import com.vetclinic.clinical.dto.request.UpdateServiceRequest;
import com.vetclinic.clinical.dto.response.LabTestItemResponse;
import com.vetclinic.clinical.dto.response.ServiceCategoryResponse;
import com.vetclinic.clinical.dto.response.ServicePriceResponse;
import com.vetclinic.clinical.dto.response.ServiceResponse;
import com.vetclinic.clinical.dto.response.SpeciesResponse;

import java.util.List;
import java.util.UUID;

/** Danh mục (Master Data): loài, nhóm dịch vụ, dịch vụ + bảng giá có lịch sử, chỉ số xét nghiệm kèm khoảng tham chiếu theo loài. */
public interface CatalogService {

    List<SpeciesResponse> listSpecies();

    SpeciesResponse createSpecies(SpeciesRequest request);

    SpeciesResponse updateSpecies(Short id, SpeciesRequest request);

    // ---- nhóm dịch vụ (FR-03)
    List<ServiceCategoryResponse> listCategories(boolean activeOnly);

    ServiceCategoryResponse createCategory(ServiceCategoryRequest request);

    /** Mã nhóm bất biến. Đổi {@code requiresPrepayment} / {@code queueType} áp dụng cho các lần chỉ định, thu tiền và phát phiếu tiếp theo. */
    ServiceCategoryResponse updateCategory(Short id, ServiceCategoryRequest request);

    // ---- dịch vụ (kèm giá đang áp dụng)
    /** activeOnly = true cho Bác sĩ chọn khi chỉ định; Admin xem cả dịch vụ đã ngừng dùng. categoryCode null = mọi nhóm. */
    List<ServiceResponse> listServices(boolean activeOnly, String categoryCode);

    ServiceResponse getService(UUID id);

    /** Tạo dịch vụ + dòng giá đầu tiên trong một giao dịch. */
    ServiceResponse createService(CreateServiceRequest request);

    /** Sửa thông tin (không gồm giá); ngừng cung cấp bằng active = false. */
    ServiceResponse updateService(UUID id, UpdateServiceRequest request);

    /** Lịch sử giá, mới nhất trước. */
    List<ServicePriceResponse> listPrices(UUID serviceId);

    /** Đóng dòng giá đang mở và thêm dòng mới (Quy chuẩn 7.4). Trả dòng giá mới. */
    ServicePriceResponse changePrice(UUID serviceId, ChangePriceRequest request);

    // ---- xét nghiệm
    List<LabTestItemResponse> listLabTestItems();

    /** UC-05 (+UC-06 nếu có ranges): thêm chỉ số xét nghiệm. */
    LabTestItemResponse createLabTestItem(LabTestItemRequest request);

    /** UC-05: sửa tên, đơn vị, mô tả, trạng thái (mã bất biến). */
    LabTestItemResponse updateLabTestItem(Short id, UpdateLabTestItemRequest request);

    /**
     * UC-06: đặt khoảng tham chiếu của một chỉ số cho một loài (thêm mới hoặc sửa). Kết quả đã nhập trước đó giữ nguyên
     * snapshot khoảng cũ (BR-24).
     */
    LabTestItemResponse saveReferenceRange(Short labTestItemId, Short speciesId, ReferenceRangeRequest request);

    /** Các chỉ số của một dịch vụ xét nghiệm; dịch vụ chưa cấu hình thì được coi là gồm mọi chỉ số. */
    List<LabTestItemResponse> listServiceLabTestItems(UUID serviceId);

    /** Admin đặt dịch vụ xét nghiệm gồm những chỉ số nào (thay toàn bộ; rỗng = mọi chỉ số). */
    List<LabTestItemResponse> setServiceLabTestItems(UUID serviceId, List<Short> labTestItemIds);
}
