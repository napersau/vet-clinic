package com.vetclinic.reporting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/** {@code vetclinic.reporting.*}: múi giờ của ngày nghiệp vụ (business_date). */
@ConfigurationProperties(prefix = "vetclinic.reporting")
public record ReportingProperties(String zone) {

    public ReportingProperties {
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Ho_Chi_Minh";
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
