package com.vetclinic.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Đồng hồ hệ thống (UTC) cho mốc giao dịch và sự kiện. OutboxWriter cũng lấy bean này nên thời điểm ghi outbox cùng nguồn
 * với {@code occurredAt} của sự kiện; test thay bằng Clock.fixed.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
