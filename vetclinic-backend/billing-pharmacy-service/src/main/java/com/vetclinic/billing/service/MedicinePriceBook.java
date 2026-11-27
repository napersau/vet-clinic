package com.vetclinic.billing.service;

import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicinePrice;
import com.vetclinic.billing.repository.MedicinePriceRepository;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Bảng giá thuốc có lịch sử (Quy chuẩn 7.4, BR-38, EX-20), cùng quy tắc với {@code ServicePriceBook} bên clinical. Dòng giá KHÔNG bao giờ bị sửa số
 * tiền hay xóa: đổi giá = đóng dòng đang mở + thêm dòng mới trong một giao dịch. Hóa đơn thuốc chụp {@code unitPrice}, {@code vatRate} từ dòng đang
 * hiệu lực.
 * <p>Cột {@code effective_from/to} là DATETIME (độ chính xác 1 giây, MySQL LÀM TRÒN phần thập phân): mọi mốc hiệu lực được cắt về giây (làm tròn
 * xuống) trước khi lưu và so sánh, để giá vừa đặt không bị lưu lệch vào tương lai.
 */
@Service
@RequiredArgsConstructor
public class MedicinePriceBook {

    private final MedicinePriceRepository priceRepository;
    private final Clock clock;

    /** Giá đang áp dụng ngay bây giờ, dùng để lập hóa đơn thuốc. */
    @Transactional(readOnly = true)
    public MedicinePrice current(UUID medicineId) {
        return priceRepository.findEffective(medicineId, clock.instant())
                .orElseThrow(() -> new AppException(ErrorCode.MEDICINE_PRICE_NOT_FOUND));   // PHARM_1412
    }

    /**
     * Admin đặt giá mới. Có thể đặt giá hiệu lực trong tương lai (khi đó giá hiện hành vẫn áp dụng tới lúc đó). Thời điểm hiệu lực mới phải sau
     * thời điểm bắt đầu của dòng đang mở ({@code BILL_1410}).
     */
    @Transactional(rollbackFor = Exception.class)
    public MedicinePrice changePrice(Medicine medicine, BigDecimal salePrice, BigDecimal vatRate, Instant effectiveFrom, String reason, UUID adminId) {
        MedicinePrice open = priceRepository.findByMedicineIdAndEffectiveToIsNull(medicine.getId())
                .orElseThrow(() -> new AppException(ErrorCode.MEDICINE_PRICE_NOT_FOUND));
        effectiveFrom = effectiveFrom.truncatedTo(ChronoUnit.SECONDS);
        if (!effectiveFrom.isAfter(open.getEffectiveFrom())) {
            throw new AppException(ErrorCode.PRICE_EFFECTIVE_DATE_INVALID);   // BILL_1410
        }
        open.setEffectiveTo(effectiveFrom);   // chỉ đóng dòng cũ, không sửa số tiền
        priceRepository.saveAndFlush(open);
        return priceRepository.saveAndFlush(MedicinePrice.builder()
                .medicine(medicine).salePrice(salePrice).vatRate(vatRate).effectiveFrom(effectiveFrom)
                .changeReason(reason).createdBy(adminId).build());   // UNIQUE(medicine_id, effective_from)
    }

    /** Giá đầu tiên của một thuốc mới tạo (hiệu lực từ lúc tạo). */
    @Transactional(rollbackFor = Exception.class)
    public MedicinePrice openFirstPrice(Medicine medicine, BigDecimal salePrice, BigDecimal vatRate, String reason, UUID adminId) {
        return priceRepository.saveAndFlush(MedicinePrice.builder()
                .medicine(medicine).salePrice(salePrice).vatRate(vatRate).effectiveFrom(clock.instant().truncatedTo(ChronoUnit.SECONDS))
                .changeReason(reason).createdBy(adminId).build());
    }
}
