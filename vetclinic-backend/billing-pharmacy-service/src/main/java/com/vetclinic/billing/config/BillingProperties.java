package com.vetclinic.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

/**
 * v3.0: thuế suất là DỮ LIỆU ({@code medicines.vat_rate}, snapshot vào hóa đơn thuốc), không còn cấu hình {@code vetclinic.billing.vat.*}.
 *
 * @param zone múi giờ vận hành của phòng khám, dùng cho năm trong mã hóa đơn và ngày trong báo cáo
 */
@ConfigurationProperties(prefix = "vetclinic.billing")
public record BillingProperties(@DefaultValue("Asia/Ho_Chi_Minh") String zone) {

    public BillingProperties {
        ZoneId.of(zone); // sai múi giờ thì dừng ngay lúc khởi động
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
