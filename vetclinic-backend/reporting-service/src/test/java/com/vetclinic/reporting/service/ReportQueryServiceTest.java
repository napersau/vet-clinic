package com.vetclinic.reporting.service;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.reporting.dto.response.DoctorPerformanceResponse;
import com.vetclinic.reporting.dto.response.RevenueByCategoryResponse;
import com.vetclinic.reporting.dto.response.RevenueDailyResponse;
import com.vetclinic.reporting.dto.response.VisitSummaryResponse;
import com.vetclinic.reporting.entity.DailyRevenueSummary;
import com.vetclinic.reporting.entity.DoctorDailyPerformance;
import com.vetclinic.reporting.repository.DailyRevenueSummaryRepository;
import com.vetclinic.reporting.repository.DoctorDailyPerformanceRepository;
import com.vetclinic.reporting.repository.DoctorLoadRow;
import com.vetclinic.reporting.repository.RevenueFactRepository;
import com.vetclinic.reporting.repository.StatusCount;
import com.vetclinic.reporting.repository.VisitFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportQueryServiceTest {

    @Mock DailyRevenueSummaryRepository summaries;
    @Mock DoctorDailyPerformanceRepository performances;
    @Mock VisitFactRepository visits;
    @Mock RevenueFactRepository revenueFacts;

    /** 21/11/2026 00:30 giờ phòng khám (17:30 UTC hôm trước): "hôm nay" phải là 21/11 chứ không phải 20/11. */
    final Clock clock = Clock.fixed(Instant.parse("2026-11-20T17:30:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    ReportQueryService service;

    @BeforeEach
    void setUp() {
        service = new ReportQueryService(summaries, performances, visits, revenueFacts, clock);
    }

    private static DailyRevenueSummary summary(String date, String type, String code, String name, long lines, String before, String vat, String discount, String net) {
        DailyRevenueSummary s = new DailyRevenueSummary();
        ReflectionTestUtils.setField(s, "businessDate", LocalDate.parse(date));
        ReflectionTestUtils.setField(s, "revenueType", com.vetclinic.reporting.constant.RevenueType.valueOf(type));
        ReflectionTestUtils.setField(s, "categoryCode", code);
        ReflectionTestUtils.setField(s, "categoryName", name);
        ReflectionTestUtils.setField(s, "lineCount", (int) lines);
        ReflectionTestUtils.setField(s, "amountBeforeVat", new BigDecimal(before));
        ReflectionTestUtils.setField(s, "vatAmount", new BigDecimal(vat));
        ReflectionTestUtils.setField(s, "discountAmount", new BigDecimal(discount));
        ReflectionTestUtils.setField(s, "netAmount", new BigDecimal(net));
        return s;
    }

    private static DoctorDailyPerformance perf(String date, UUID doctor, String name, int completed, int cancelled, int emergency, String revenue) {
        DoctorDailyPerformance p = new DoctorDailyPerformance();
        ReflectionTestUtils.setField(p, "businessDate", LocalDate.parse(date));
        ReflectionTestUtils.setField(p, "doctorId", doctor);
        ReflectionTestUtils.setField(p, "doctorName", name);
        ReflectionTestUtils.setField(p, "completedVisits", completed);
        ReflectionTestUtils.setField(p, "cancelledVisits", cancelled);
        ReflectionTestUtils.setField(p, "emergencyVisits", emergency);
        ReflectionTestUtils.setField(p, "serviceNetRevenue", new BigDecimal(revenue));
        return p;
    }

    private void standardDay() {
        // 21/11: khám 100.000 + XN 250.000 + X-quang 180.000 (dịch vụ) ; thuốc 157.500. 22/11: X-quang hoàn -180.000.
        when(summaries.findByBusinessDateBetweenOrderByBusinessDateAscRevenueTypeAscCategoryCodeAsc(any(), any())).thenReturn(List.of(
                summary("2026-11-21", "MEDICINE", "ANTIBIOTIC", "Kháng sinh", 1, "150000.00", "7500.00", "0.00", "157500.00"),
                summary("2026-11-21", "SERVICE", "EXAMINATION", "Khám bệnh", 1, "100000.00", "5000.00", "0.00", "105000.00"),
                summary("2026-11-21", "SERVICE", "IMAGING", "Chẩn đoán hình ảnh", 1, "180000.00", "0.00", "0.00", "180000.00"),
                summary("2026-11-21", "SERVICE", "LABORATORY", "Xét nghiệm", 1, "250000.00", "0.00", "0.00", "250000.00"),
                summary("2026-11-22", "SERVICE", "IMAGING", "Chẩn đoán hình ảnh", -1, "-180000.00", "0.00", "0.00", "-180000.00")));
    }

    // ------------------------------------------------------------------ khoảng ngày

    @Test
    void defaultRangeIsLast30DaysEndingTodayInClinicTimeZone() {
        when(summaries.findByBusinessDateBetweenOrderByBusinessDateAscRevenueTypeAscCategoryCodeAsc(any(), any())).thenReturn(List.of());

        RevenueDailyResponse res = service.revenueDaily(null, null, null);

        assertThat(res.toDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(res.fromDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(res.items()).isEmpty();
        assertThat(res.totals().netAmount()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsReversedAndTooLongRangesAndBadType() {
        assertThatThrownBy(() -> service.revenueDaily(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), null))
                .isInstanceOf(AppException.class).extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA);
        assertThatThrownBy(() -> service.revenueDaily(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 1), null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.revenueDaily(null, null, "FOOD")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.revenueByCategory("2026-13", null, null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.revenueByCategory("2026-11", LocalDate.of(2026, 11, 1), null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.visitSummary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), null)).isInstanceOf(AppException.class);
    }

    // ------------------------------------------------------------------ doanh thu

    @Test
    void revenueDailyGroupsByDayAndTypeAndNetsOutRefunds() {
        standardDay();

        RevenueDailyResponse res = service.revenueDaily(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22), null);

        assertThat(res.items()).extracting(RevenueDailyResponse.Item::businessDate, RevenueDailyResponse.Item::revenueType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 11, 21), "MEDICINE"),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 11, 21), "SERVICE"),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 11, 22), "SERVICE"));
        RevenueDailyResponse.Item serviceDay1 = res.items().get(1);
        assertThat(serviceDay1.netAmount()).isEqualByComparingTo("535000.00");   // 105.000 + 180.000 + 250.000
        assertThat(serviceDay1.lineCount()).isEqualTo(3);
        assertThat(res.items().get(2).netAmount()).isEqualByComparingTo("-180000.00");
        assertThat(res.totals().serviceNetAmount()).isEqualByComparingTo("355000.00");   // 535.000 - 180.000
        assertThat(res.totals().medicineNetAmount()).isEqualByComparingTo("157500.00");
        assertThat(res.totals().netAmount()).isEqualByComparingTo("512500.00");
        assertThat(res.totals().vatAmount()).isEqualByComparingTo("12500.00");
    }

    @Test
    void revenueDailyFiltersByType() {
        standardDay();

        RevenueDailyResponse res = service.revenueDaily(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22), "medicine");

        assertThat(res.type()).isEqualTo("MEDICINE");
        assertThat(res.items()).hasSize(1);
        assertThat(res.totals().netAmount()).isEqualByComparingTo("157500.00");
        assertThat(res.totals().serviceNetAmount()).isEqualByComparingTo("0");
    }

    @Test
    void revenueByCategoryUsesWholeMonthAndSortsByTypeThenNetDescending() {
        standardDay();

        RevenueByCategoryResponse res = service.revenueByCategory("2026-11", null, null);

        assertThat(res.month()).isEqualTo("2026-11");
        assertThat(res.fromDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(res.toDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(res.items()).extracting(RevenueByCategoryResponse.Item::categoryCode).containsExactly("ANTIBIOTIC", "LABORATORY", "EXAMINATION", "IMAGING");
        RevenueByCategoryResponse.Item imaging = res.items().get(3);
        assertThat(imaging.netAmount()).isEqualByComparingTo("0.00");   // thu 180.000 - hoàn 180.000
        assertThat(imaging.lineCount()).isZero();
        assertThat(res.totals().netAmount()).isEqualByComparingTo("512500.00");
    }

    // ------------------------------------------------------------------ Bác sĩ, ca khám

    @Test
    void doctorPerformanceAddsUpDaysAndKeepsLatestName() {
        UUID tien = UUID.randomUUID();
        UUID an = UUID.randomUUID();
        when(performances.findByBusinessDateBetweenOrderByBusinessDateAsc(any(), any())).thenReturn(List.of(
                perf("2026-11-20", tien, "BS Tiến cũ", 2, 1, 0, "300000.00"),
                perf("2026-11-21", tien, "BS Tiến", 3, 0, 1, "200000.00"),
                perf("2026-11-21", an, "BS An", 1, 0, 0, "900000.00")));

        DoctorPerformanceResponse res = service.doctorPerformance(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21));

        assertThat(res.doctors()).hasSize(2);
        DoctorPerformanceResponse.Doctor first = res.doctors().get(0);   // doanh thu cao nhất đứng đầu
        assertThat(first.doctorName()).isEqualTo("BS An");
        DoctorPerformanceResponse.Doctor second = res.doctors().get(1);
        assertThat(second.doctorName()).isEqualTo("BS Tiến");
        assertThat(second.completedVisits()).isEqualTo(5);
        assertThat(second.cancelledVisits()).isEqualTo(1);
        assertThat(second.emergencyVisits()).isEqualTo(1);
        assertThat(second.serviceNetRevenue()).isEqualByComparingTo("500000.00");
    }

    @Test
    void visitSummaryCountsStatusesEmergencyAndParaclinical() {
        LocalDate day = LocalDate.of(2026, 11, 21);
        UUID tien = UUID.randomUUID();
        when(visits.countByStatus(day, day)).thenReturn(List.of(new StatusCount("COMPLETED", 5L, 1L), new StatusCount("CANCELLED", 1L, 0L), new StatusCount("WAITING", 2L, 1L)));
        when(revenueFacts.visitsWithPositiveNet(eq(List.of("LABORATORY", "IMAGING")), eq(day), eq(day))).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        when(visits.doctorLoads(day, day)).thenReturn(List.of(new DoctorLoadRow(tien, "BS Tiến", 6L, 4L)));

        VisitSummaryResponse res = service.visitSummary(day, null, null);

        assertThat(res.fromDate()).isEqualTo(day);
        assertThat(res.toDate()).isEqualTo(day);
        assertThat(res.totalVisits()).isEqualTo(8);
        assertThat(res.completedVisits()).isEqualTo(5);
        assertThat(res.cancelledVisits()).isEqualTo(1);
        assertThat(res.emergencyVisits()).isEqualTo(2);
        assertThat(res.paraclinicalVisits()).isEqualTo(3);
        assertThat(res.routineVisits()).isEqualTo(5);
        assertThat(res.byStatus()).containsEntry("WAITING", 2L).containsEntry("COMPLETED", 5L).containsEntry("CANCELLED", 1L);
        assertThat(res.doctors()).singleElement().satisfies(d -> {
            assertThat(d.doctorName()).isEqualTo("BS Tiến");
            assertThat(d.assignedVisits()).isEqualTo(6);
            assertThat(d.completedVisits()).isEqualTo(4);
        });
    }

    @Test
    void visitSummaryWithNoDataReturnsZerosForEveryStatus() {
        when(visits.countByStatus(any(), any())).thenReturn(List.of());
        when(revenueFacts.visitsWithPositiveNet(any(), any(), any())).thenReturn(List.of());
        when(visits.doctorLoads(any(), any())).thenReturn(List.of());

        VisitSummaryResponse res = service.visitSummary(null, null, null);

        assertThat(res.totalVisits()).isZero();
        assertThat(res.byStatus()).containsOnlyKeys("WAITING", "COMPLETED", "CANCELLED").containsValues(0L, 0L, 0L);
        assertThat(res.doctors()).isEmpty();
    }
}
