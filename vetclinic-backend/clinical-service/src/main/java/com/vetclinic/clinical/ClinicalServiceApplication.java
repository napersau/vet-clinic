package com.vetclinic.clinical;

import com.vetclinic.common.event.outbox.EnableOutbox;
import com.vetclinic.common.event.processed.EnableIdempotentConsumer;
import com.vetclinic.common.util.TimeZones;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

// @EnableOutbox: VisitCheckedIn / ParaclinicalResultsReady / VisitCompleted đi qua Transactional Outbox (bảng outbox_events)
@EnableOutbox
@EnableIdempotentConsumer   // processed_events: clinical NHẬN UserProfileUpdatedEvent (cập nhật snapshot chủ nuôi)
@ConfigurationPropertiesScan
@EnableFeignClients(basePackages = "com.vetclinic.clinical.client")
@SpringBootApplication(scanBasePackages = "com.vetclinic")
public class ClinicalServiceApplication {

    public static void main(String[] args) {
        // JVM phải ở UTC để khớp phiên JDBC (xem TimeZones); giờ phòng khám tính tường minh bằng Clock
        TimeZones.forceUtcDefault();
        SpringApplication.run(ClinicalServiceApplication.class, args);
    }
}
