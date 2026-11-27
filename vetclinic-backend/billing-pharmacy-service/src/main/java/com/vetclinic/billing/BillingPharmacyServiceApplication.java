package com.vetclinic.billing;

import com.vetclinic.common.event.outbox.EnableOutbox;
import com.vetclinic.common.event.processed.EnableIdempotentConsumer;
import com.vetclinic.common.util.TimeZones;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @EnableOutbox: MedicineInvoicePaid / MedicineLowStock đi qua Transactional Outbox (bảng outbox_events)
@EnableOutbox
@EnableIdempotentConsumer   // processed_events: billing NHẬN PrescriptionCreatedEvent (hóa đơn thuốc nháp)
@ConfigurationPropertiesScan
@SpringBootApplication(scanBasePackages = "com.vetclinic")
public class BillingPharmacyServiceApplication {

    public static void main(String[] args) {
        // JVM phải ở UTC để khớp phiên JDBC (xem TimeZones); giờ phòng khám tính tường minh bằng Clock
        TimeZones.forceUtcDefault();
        SpringApplication.run(BillingPharmacyServiceApplication.class, args);
    }
}
