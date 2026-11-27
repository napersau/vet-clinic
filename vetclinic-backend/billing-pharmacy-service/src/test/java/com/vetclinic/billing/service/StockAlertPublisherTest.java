package com.vetclinic.billing.service;

import java.util.UUID;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.MedicineLowStockEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StockAlertPublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final UUID ADMIN = UUID.fromString("019a9442-d800-7729-87cc-fbb1ceda8817");

    private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private final StockAlertPublisher publisher = new StockAlertPublisher(outboxWriter, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Medicine medicine(int stock, int minAlert) {
        Medicine medicine = new Medicine();
        medicine.setId(com.vetclinic.billing.testsupport.TestData.mid(7L));
        medicine.setCode("MED_AMOX");
        medicine.setName("Clavamox 250mg");
        medicine.setStock(com.vetclinic.billing.entity.MedicineStock.builder().quantityOnHand(stock).build());
        medicine.setMinStockAlert(minAlert);
        return medicine;
    }

    @Test
    void stockAtOrBelowTheThresholdWritesALowStockEventToTheOutbox() {
        publisher.publishIfLow(medicine(20, 20), ADMIN);   // đúng ngưỡng vẫn cảnh báo (stock <= min)

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("MEDICINE"), eq(com.vetclinic.billing.testsupport.TestData.mid(7L)), event.capture());
        MedicineLowStockEvent low = (MedicineLowStockEvent) event.getValue();
        assertThat(low.routingKey()).isEqualTo("pharmacy.low_stock");
        assertThat(low.medicineCode()).isEqualTo("MED_AMOX");
        assertThat(low.medicineId()).isEqualTo(com.vetclinic.billing.testsupport.TestData.mid(7L));
        assertThat(low.actorUserId()).isEqualTo(ADMIN);
        assertThat(low.eventId()).isNotNull();
        assertThat(low.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void stockAboveTheThresholdWritesNothing() {
        publisher.publishIfLow(medicine(21, 20), ADMIN);

        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));
    }
}
