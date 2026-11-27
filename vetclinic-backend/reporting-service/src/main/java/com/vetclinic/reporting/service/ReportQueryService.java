package com.vetclinic.reporting.service;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.reporting.constant.RevenueType;
import com.vetclinic.reporting.dto.response.DoctorPerformanceResponse;
import com.vetclinic.reporting.dto.response.RevenueByCategoryResponse;
import com.vetclinic.reporting.dto.response.RevenueDailyResponse;
import com.vetclinic.reporting.dto.response.VisitSummaryResponse;
import com.vetclinic.reporting.entity.DailyRevenueSummary;
import com.vetclinic.reporting.entity.DoctorDailyPerformance;
import com.vetclinic.reporting.repository.DailyRevenueSummaryRepository;
import com.vetclinic.reporting.repository.DoctorDailyPerformanceRepository;
import com.vetclinic.reporting.repository.RevenueFactRepository;
import com.vetclinic.reporting.repository.StatusCount;
import com.vetclinic.reporting.repository.VisitFactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Các truy vấn báo cáo (FR-69, FR-70): CHỈ đọc {@code pet_clinic_reporting_db} - không gọi service khác, không JOIN chéo CSDL.
 * Ngày là ngày nghiệp vụ theo giờ phòng khám. Khoảng mặc định 30 ngày gần nhất, tối đa 366 ngày.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryService {

    static final int DEFAULT_DAYS = 30;
    static final long MAX_DAYS = 366;
    /** Nhóm dịch vụ được tính là "có cận lâm sàng" (Kiến trúc: xét nghiệm + chẩn đoán hình ảnh). */
    static final List<String> PARACLINICAL_CATEGORIES = List.of("LABORATORY", "IMAGING");

    private final DailyRevenueSummaryRepository summaries;
    private final DoctorDailyPerformanceRepository performances;
    private final VisitFactRepository visits;
    private final RevenueFactRepository revenueFacts;
    private final Clock clock;

    /** Khoảng ngày [from, to]: thiếu {@code to} = hôm nay, thiếu {@code from} = 30 ngày gần nhất. */
    record Range(LocalDate from, LocalDate to) {
    }

    Range range(LocalDate fromDate, LocalDate toDate) {
        LocalDate to = toDate != null ? toDate : LocalDate.now(clock);
        LocalDate from = fromDate != null ? fromDate : to.minusDays(DEFAULT_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Khoảng báo cáo tối đa " + MAX_DAYS + " ngày");
        }
        return new Range(from, to);
    }

    private static RevenueType normalizeType(String type) {
        try {
            return RevenueType.parse(type);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Loại doanh thu phải là SERVICE hoặc MEDICINE");
        }
    }

    // ================================================================== doanh thu

    /** Mỗi dòng = (ngày, loại): cộng các nhóm trong ngày; tổng toàn kỳ kèm cột riêng dịch vụ / thuốc. */
    public RevenueDailyResponse revenueDaily(LocalDate fromDate, LocalDate toDate, String typeParam) {
        Range range = range(fromDate, toDate);
        RevenueType type = normalizeType(typeParam);
        Map<String, Acc> byDayAndType = new TreeMap<>();
        Acc total = new Acc();
        BigDecimal serviceNet = BigDecimal.ZERO;
        BigDecimal medicineNet = BigDecimal.ZERO;
        for (DailyRevenueSummary s : summaries.findByBusinessDateBetweenOrderByBusinessDateAscRevenueTypeAscCategoryCodeAsc(range.from(), range.to())) {
            if (type != null && type != s.getRevenueType()) {
                continue;
            }
            byDayAndType.computeIfAbsent(s.getBusinessDate() + "|" + s.getRevenueType(), k -> new Acc(s.getBusinessDate(), s.getRevenueType().name())).add(s);
            total.add(s);
            if (s.getRevenueType() == RevenueType.SERVICE) {
                serviceNet = serviceNet.add(s.getNetAmount());
            } else {
                medicineNet = medicineNet.add(s.getNetAmount());
            }
        }
        List<RevenueDailyResponse.Item> items = byDayAndType.values().stream()
                .map(a -> new RevenueDailyResponse.Item(a.date, a.type, a.lines, a.before, a.vat, a.discount, a.net)).toList();
        return new RevenueDailyResponse(range.from(), range.to(), type == null ? null : type.name(), total.totals(serviceNet, medicineNet), items);
    }

    /** Theo tháng ({@code month=yyyy-MM}) hoặc theo khoảng ngày; hai cách không dùng cùng lúc. */
    public RevenueByCategoryResponse revenueByCategory(String month, LocalDate fromDate, LocalDate toDate) {
        Range range;
        String monthLabel = null;
        if (month != null && !month.isBlank()) {
            if (fromDate != null || toDate != null) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ truyền month hoặc from / to, không truyền cả hai");
            }
            try {
                YearMonth ym = YearMonth.parse(month.trim());
                range = new Range(ym.atDay(1), ym.atEndOfMonth());
                monthLabel = ym.toString();
            } catch (DateTimeParseException e) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tháng phải có dạng yyyy-MM, ví dụ 2026-11");
            }
        } else {
            range = range(fromDate, toDate);
        }
        Map<String, Acc> byCategory = new LinkedHashMap<>();
        Acc total = new Acc();
        BigDecimal serviceNet = BigDecimal.ZERO;
        BigDecimal medicineNet = BigDecimal.ZERO;
        for (DailyRevenueSummary s : summaries.findByBusinessDateBetweenOrderByBusinessDateAscRevenueTypeAscCategoryCodeAsc(range.from(), range.to())) {
            Acc acc = byCategory.computeIfAbsent(s.getRevenueType() + "|" + s.getCategoryCode(), k -> new Acc(null, s.getRevenueType().name()));
            acc.categoryCode = s.getCategoryCode();
            acc.categoryName = s.getCategoryName();   // dòng mới nhất (thứ tự ngày tăng dần) mang tên mới nhất
            acc.add(s);
            total.add(s);
            if (s.getRevenueType() == RevenueType.SERVICE) {
                serviceNet = serviceNet.add(s.getNetAmount());
            } else {
                medicineNet = medicineNet.add(s.getNetAmount());
            }
        }
        List<RevenueByCategoryResponse.Item> items = byCategory.values().stream()
                .sorted(java.util.Comparator.comparing((Acc a) -> a.type).thenComparing(a -> a.net.negate()))
                .map(a -> new RevenueByCategoryResponse.Item(a.type, a.categoryCode, a.categoryName, a.lines, a.before, a.vat, a.discount, a.net)).toList();
        return new RevenueByCategoryResponse(monthLabel, range.from(), range.to(), total.totals(serviceNet, medicineNet), items);
    }

    // ================================================================== Bác sĩ, ca khám

    public DoctorPerformanceResponse doctorPerformance(LocalDate fromDate, LocalDate toDate) {
        Range range = range(fromDate, toDate);
        Map<UUID, long[]> counts = new LinkedHashMap<>();
        Map<UUID, BigDecimal> revenue = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (DoctorDailyPerformance p : performances.findByBusinessDateBetweenOrderByBusinessDateAsc(range.from(), range.to())) {
            long[] c = counts.computeIfAbsent(p.getDoctorId(), k -> new long[3]);
            c[0] += p.getCompletedVisits();
            c[1] += p.getCancelledVisits();
            c[2] += p.getEmergencyVisits();
            revenue.merge(p.getDoctorId(), p.getServiceNetRevenue(), BigDecimal::add);
            names.put(p.getDoctorId(), p.getDoctorName());   // ngày mới nhất ghi đè: giữ tên hiện hành
        }
        List<DoctorPerformanceResponse.Doctor> doctors = counts.entrySet().stream()
                .map(e -> new DoctorPerformanceResponse.Doctor(e.getKey(), names.get(e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2], revenue.get(e.getKey())))
                .sorted(java.util.Comparator.comparing(DoctorPerformanceResponse.Doctor::serviceNetRevenue).reversed()
                        .thenComparing(DoctorPerformanceResponse.Doctor::doctorName, java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
        return new DoctorPerformanceResponse(range.from(), range.to(), doctors);
    }

    /** {@code date} là cách viết tắt của {@code from = to = date}. */
    public VisitSummaryResponse visitSummary(LocalDate date, LocalDate fromDate, LocalDate toDate) {
        if (date != null && (fromDate != null || toDate != null)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ truyền date hoặc from / to, không truyền cả hai");
        }
        Range range = date != null ? new Range(date, date) : range(fromDate, toDate);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("WAITING", 0L);
        byStatus.put("COMPLETED", 0L);
        byStatus.put("CANCELLED", 0L);
        long total = 0;
        long emergency = 0;
        for (StatusCount row : visits.countByStatus(range.from(), range.to())) {
            byStatus.merge(row.status(), row.total(), Long::sum);
            total += row.total();
            emergency += row.emergency();
        }
        long paraclinical = revenueFacts.visitsWithPositiveNet(PARACLINICAL_CATEGORIES, range.from(), range.to()).size();
        List<VisitSummaryResponse.DoctorLoad> doctors = visits.doctorLoads(range.from(), range.to()).stream()
                .map(r -> new VisitSummaryResponse.DoctorLoad(r.doctorId(), r.doctorName(), r.assigned(), r.completed())).toList();
        return new VisitSummaryResponse(range.from(), range.to(), total, byStatus.get("COMPLETED"), byStatus.get("CANCELLED"), emergency,
                Math.max(total - paraclinical, 0), paraclinical, byStatus, doctors);
    }

    /** Bộ cộng dồn một nhóm dòng tổng hợp. */
    private static final class Acc {
        LocalDate date;
        String type;
        String categoryCode;
        String categoryName;
        long lines;
        BigDecimal before = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;

        Acc() {
        }

        Acc(LocalDate date, String type) {
            this.date = date;
            this.type = type;
        }

        void add(DailyRevenueSummary s) {
            lines += s.getLineCount();
            before = before.add(s.getAmountBeforeVat());
            vat = vat.add(s.getVatAmount());
            discount = discount.add(s.getDiscountAmount());
            net = net.add(s.getNetAmount());
        }

        RevenueDailyResponse.Totals totals(BigDecimal serviceNet, BigDecimal medicineNet) {
            return new RevenueDailyResponse.Totals(lines, before, vat, discount, net, serviceNet, medicineNet);
        }
    }
}
