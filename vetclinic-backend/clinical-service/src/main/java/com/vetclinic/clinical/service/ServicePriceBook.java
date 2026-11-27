package com.vetclinic.clinical.service;

import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.ServicePrice;
import com.vetclinic.clinical.repository.ServicePriceRepository;
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
 * Bảng giá dịch vụ có lịch sử (Quy chuẩn 7.4, BR-38, EX-20). Dòng giá KHÔNG bao giờ bị sửa số tiền hay xóa: đổi giá = đóng dòng đang mở
 * + thêm dòng mới trong một giao dịch. Hóa đơn chụp {@code unitPrice}, {@code vatRate} từ dòng đang hiệu lực lúc chỉ định.
 * <p>Cột {@code effective_from/to} là DATETIME (độ chính xác 1 giây, MySQL LÀM TRÒN phần thập phân): mọi mốc hiệu lực được cắt về giây
 * (làm tròn xuống) trước khi lưu và so sánh, để giá vừa đặt không bị lưu lệch vào tương lai.
 */
@Service
@RequiredArgsConstructor
public class ServicePriceBook {

    private final ServicePriceRepository priceRepository;
    private final Clock clock;

    /** Giá đang áp dụng ngay bây giờ, dùng để lập hóa đơn lúc chỉ định / tiếp đón. */
    @Transactional(readOnly = true)
    public ServicePrice current(UUID serviceId) {
        return priceRepository.findEffective(serviceId, clock.instant())
                .orElseThrow(() -> new AppException(ErrorCode.SERVICE_PRICE_NOT_FOUND));   // CLIN_1315
    }

    /**
     * Admin đặt giá mới. Có thể đặt giá hiệu lực trong tương lai (khi đó giá hiện hành vẫn áp dụng tới lúc đó). Thời điểm hiệu lực mới
     * phải sau thời điểm bắt đầu của dòng đang mở ({@code BILL_1410}).
     */
    @Transactional(rollbackFor = Exception.class)
    public ServicePrice changePrice(ClinicService service, BigDecimal price, BigDecimal vatRate, Instant effectiveFrom,
                                    String reason, UUID adminId) {
        ServicePrice open = priceRepository.findByServiceIdAndEffectiveToIsNull(service.getId())
                .orElseThrow(() -> new AppException(ErrorCode.SERVICE_PRICE_NOT_FOUND));
        effectiveFrom = effectiveFrom.truncatedTo(ChronoUnit.SECONDS);
        if (!effectiveFrom.isAfter(open.getEffectiveFrom())) {
            throw new AppException(ErrorCode.PRICE_EFFECTIVE_DATE_INVALID);   // BILL_1410
        }
        open.setEffectiveTo(effectiveFrom);   // chỉ đóng dòng cũ, không sửa số tiền
        priceRepository.saveAndFlush(open);
        return priceRepository.saveAndFlush(ServicePrice.builder()
                .service(service).price(price).vatRate(vatRate).effectiveFrom(effectiveFrom)
                .changeReason(reason).createdBy(adminId).build());   // UNIQUE(service_id, effective_from)
    }

    /** Giá đầu tiên của một dịch vụ mới tạo (hiệu lực từ lúc tạo). */
    @Transactional(rollbackFor = Exception.class)
    public ServicePrice openFirstPrice(ClinicService service, BigDecimal price, BigDecimal vatRate, String reason, UUID adminId) {
        return priceRepository.saveAndFlush(ServicePrice.builder()
                .service(service).price(price).vatRate(vatRate).effectiveFrom(clock.instant().truncatedTo(ChronoUnit.SECONDS))
                .changeReason(reason).createdBy(adminId).build());
    }
}
