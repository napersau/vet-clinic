package com.vetclinic.reporting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ReportingConfig {

    /** Đồng hồ giờ phòng khám (thay được trong test); dùng cho processed_events và mọi phép tính ngày. */
    @Bean
    public Clock reportingClock(ReportingProperties properties) {
        return Clock.system(properties.zoneId());
    }
}
