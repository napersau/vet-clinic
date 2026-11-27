package com.vetclinic.reporting;

import com.vetclinic.common.event.processed.EnableIdempotentConsumer;
import com.vetclinic.common.util.TimeZones;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

// reporting-service chỉ NHẬN sự kiện (không phát) nên không cần @EnableOutbox; Feign chỉ dùng cho job dựng lại báo cáo (đọc outbox của service nguồn); @EnableIdempotentConsumer: chống xử lý trùng (processed_events)
@EnableIdempotentConsumer
@EnableFeignClients(basePackages = "com.vetclinic.reporting.client")
@ConfigurationPropertiesScan
@SpringBootApplication(scanBasePackages = "com.vetclinic")
public class ReportingServiceApplication {

    public static void main(String[] args) {
        // JVM phải ở UTC để khớp phiên JDBC (xem TimeZones); ngày nghiệp vụ tính tường minh bằng Clock / ZoneId của phòng khám
        TimeZones.forceUtcDefault();
        SpringApplication.run(ReportingServiceApplication.class, args);
    }
}
