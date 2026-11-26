package com.vetclinic.common.event;

import com.vetclinic.common.util.UuidV7;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bước 7.1: các sự kiện mang thêm dữ liệu cho reporting-service (tên thú cưng / loài / chủ nuôi / Bác sĩ, tên nhóm dịch vụ, các dòng thuốc).
 * Các trường mới phải qua vòng JSON đầy đủ, và tin nhắn PHÁT TRƯỚC khi mở rộng (thiếu trường) vẫn đọc được với trường mới = null
 * (RabbitMQ có thể còn tin cũ trong queue lúc nâng cấp).
 */
class ReportingEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void visitCheckedIn_roundTripKeepsReportingFields_andOldPayloadReadsAsNull() {
        UUID visit = UuidV7.generate();
        var event = new VisitCheckedInEvent(UuidV7.generate(), NOW, UuidV7.generate(), visit, "VIS-1", UuidV7.generate(), null, true, UuidV7.generate(),
                "Lu", "CAT", UuidV7.generate(), "Hoàng Thị Lan", "BS Tiến");
        assertThat(mapper.readValue(mapper.writeValueAsString(event), VisitCheckedInEvent.class)).isEqualTo(event);

        String old = "{\"eventId\":\"" + UuidV7.generate() + "\",\"occurredAt\":\"2026-11-21T03:00:00Z\",\"actorUserId\":\"" + UuidV7.generate()
                + "\",\"visitId\":\"" + visit + "\",\"visitCode\":\"VIS-1\",\"petId\":\"" + UuidV7.generate() + "\",\"appointmentId\":null,\"emergency\":false,\"doctorId\":null}";
        var parsed = mapper.readValue(old, VisitCheckedInEvent.class);
        assertThat(parsed.visitId()).isEqualTo(visit);
        assertThat(parsed.petName()).isNull();
        assertThat(parsed.ownerId()).isNull();
        assertThat(parsed.doctorName()).isNull();
    }

    @Test
    void visitCompleted_carriesDoctor_andOldPayloadReadsAsNull() {
        UUID doctor = UuidV7.generate();
        var event = new VisitCompletedEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(), "VIS-1", UuidV7.generate(), false, doctor, "BS Tiến");
        assertThat(mapper.readValue(mapper.writeValueAsString(event), VisitCompletedEvent.class)).isEqualTo(event);

        String old = "{\"eventId\":\"" + UuidV7.generate() + "\",\"occurredAt\":\"2026-11-21T03:00:00Z\",\"actorUserId\":null,\"visitId\":\"" + UuidV7.generate()
                + "\",\"visitCode\":\"VIS-1\",\"petId\":\"" + UuidV7.generate() + "\",\"abandoned\":true}";
        var parsed = mapper.readValue(old, VisitCompletedEvent.class);
        assertThat(parsed.abandoned()).isTrue();
        assertThat(parsed.doctorId()).isNull();
    }

    @Test
    void serviceInvoicePaid_lineCarriesCategoryName() {
        var line = new ServiceInvoicePaidEvent.Line(UuidV7.generate(), "SINV-1", UuidV7.generate(), UuidV7.generate(), "XRAY", "Chụp X-quang", "IMAGING",
                "Chẩn đoán hình ảnh", "IMAGING", 1, new BigDecimal("250000.00"), new BigDecimal("0.00"), BigDecimal.ZERO, new BigDecimal("250000.00"));
        var event = new ServiceInvoicePaidEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(), "VIS-1", UuidV7.generate(), "Lu", "Lan",
                UuidV7.generate(), false, UuidV7.generate(), NOW, List.of(line));

        var parsed = mapper.readValue(mapper.writeValueAsString(event), ServiceInvoicePaidEvent.class);

        assertThat(parsed).isEqualTo(event);
        assertThat(parsed.lines().get(0).categoryName()).isEqualTo("Chẩn đoán hình ảnh");
        assertThat(parsed.doctorName()).isNull();   // dạng cũ không có tên Bác sĩ

        var named = new ServiceInvoicePaidEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(), "VIS-1", UuidV7.generate(), "Lu", "Lan",
                UuidV7.generate(), "BS Tiến", false, UuidV7.generate(), NOW, List.of(line));
        assertThat(mapper.readValue(mapper.writeValueAsString(named), ServiceInvoicePaidEvent.class)).isEqualTo(named);
    }

    @Test
    void medicineInvoicePaid_carriesLines_andOldPayloadReadsWithoutThem() {
        var line = new MedicineInvoicePaidEvent.Line(UuidV7.generate(), "AMOX", "Clavamox", "ANTIBIOTIC", "Kháng sinh", 10, new BigDecimal("15000.00"), new BigDecimal("0.05"));
        var event = new MedicineInvoicePaidEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(), "MINV-1", UuidV7.generate(), UuidV7.generate(),
                new BigDecimal("157500.00"), BigDecimal.ZERO, NOW, List.of(line));
        assertThat(mapper.readValue(mapper.writeValueAsString(event), MedicineInvoicePaidEvent.class)).isEqualTo(event);

        String old = "{\"eventId\":\"" + UuidV7.generate() + "\",\"occurredAt\":\"2026-11-21T03:00:00Z\",\"actorUserId\":null,\"invoiceId\":\"" + UuidV7.generate()
                + "\",\"invoiceCode\":\"MINV-1\",\"prescriptionId\":\"" + UuidV7.generate() + "\",\"visitId\":\"" + UuidV7.generate() + "\",\"payableAmount\":157500.00}";
        var parsed = mapper.readValue(old, MedicineInvoicePaidEvent.class);
        assertThat(parsed.payableAmount()).isEqualByComparingTo("157500.00");
        assertThat(parsed.lines()).isNull();
        assertThat(parsed.paidAt()).isNull();
    }
}
