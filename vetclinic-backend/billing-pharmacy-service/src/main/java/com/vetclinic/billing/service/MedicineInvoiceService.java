package com.vetclinic.billing.service;

import com.vetclinic.billing.dto.request.MedicineInvoiceRequest;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;
import com.vetclinic.billing.dto.response.MedicineInvoiceStatusResponse;
import com.vetclinic.common.event.PrescriptionCreatedEvent;

import java.util.List;
import java.util.UUID;

/**
 * Hóa đơn thuốc (UC-36, SM-06). Không có API lập hóa đơn: NHÁP tự tạo từ {@code PrescriptionCreatedEvent} khi Bác sĩ chốt ca; khách mua thì Thu ngân
 * sửa số lượng rồi thu, mua ngoài thì hủy. Không bao giờ đổi trạng thái đợt khám.
 */
public interface MedicineInvoiceService {

    /**
     * Tạo (hoặc dựng lại) hóa đơn nháp từ sự kiện chốt ca. Gọi trong giao dịch của IdempotentConsumer. Đơn đã có hóa đơn PENDING (Bác sĩ mở lại hồ sơ
     * rồi chốt lại, T14) thì dựng lại các dòng theo đơn mới; đã PAID / CANCELLED thì chỉ ghi log PHARM_1407, không tạo hóa đơn thứ hai.
     */
    void createDraft(PrescriptionCreatedEvent event);

    /** Hóa đơn thuốc của một đợt khám (màn hình Thu ngân), mới nhất trước; thường chỉ có 0 hoặc 1 hóa đơn. */
    List<MedicineInvoiceResponse> findByVisit(UUID visitId);

    MedicineInvoiceResponse getById(UUID id);

    /**
     * Sửa hóa đơn khi còn PENDING (BILL_1403 nếu đã thu, BILL_1408 nếu đã hủy): quantity = 0 bỏ dòng, quantity ≤ tồn kho
     * (PHARM_1406), còn ít nhất một dòng, kiểm tra lại giảm giá (BILL_1402). Dùng để xử lý EX-15.
     */
    MedicineInvoiceResponse updateItems(UUID id, MedicineInvoiceRequest request);

    /** Khách mua ngoài: PENDING → CANCELLED, kho không đổi. Hủy là kết thúc: đơn này không có hóa đơn thuốc nào khác. */
    MedicineInvoiceResponse cancel(UUID id);

    /**
     * Thu tiền + trừ kho + ghi nhật ký kho EXPORT_SALE trong MỘT giao dịch (EX-15: thiếu kho ở bất kỳ dòng nào thì rollback toàn bộ, hóa đơn giữ
     * PENDING, PHARM_1406). Giá được làm mới theo bảng giá hiện hành (EX-20). Thu hai lần → BILL_1403 (EX-16). Sau commit phát
     * MedicineInvoicePaidEvent và MedicineLowStockEvent.
     */
    MedicineInvoiceResponse confirmPayment(UUID id);

    /** Cho clinical (T14): đơn thuốc đã có hóa đơn chưa, trạng thái gì (PAID thì không được sửa đơn). */
    MedicineInvoiceStatusResponse statusOfPrescription(UUID prescriptionId);
}
