package com.vetclinic.reporting.service;

import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.EventRouting;
import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.ServiceInvoiceRefundedEvent;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.event.outbox.OutboxReplayItem;
import com.vetclinic.common.event.processed.ProcessedEventRepository;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.util.UuidV7;
import com.vetclinic.reporting.client.OutboxClients;
import com.vetclinic.reporting.dto.response.RebuildResponse;
import com.vetclinic.reporting.event.ReportingEventProcessor;
import com.vetclinic.reporting.event.ReportingGate;
import com.vetclinic.reporting.repository.DailyRevenueSummaryRepository;
import com.vetclinic.reporting.repository.DoctorDailyPerformanceRepository;
import com.vetclinic.reporting.repository.RevenueFactRepository;
import com.vetclinic.reporting.repository.VisitFactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Job dựng lại mô hình đọc báo cáo từ {@code outbox_events} của các service nguồn (Task 4.5.4), chạy tay bởi ADMIN. Outbox chỉ giữ 7 ngày nên
 * job dựng lại {@code days} NGÀY NGHIỆP VỤ gần nhất (đủ ngày, kể cả hôm nay; tối đa {@code rebuild-max-days} = 6 để ngày đầu của cửa sổ chắc
 * chắn còn đủ sự kiện); dữ liệu của các ngày cũ hơn được GIỮ NGUYÊN.
 * <p>
 * Trình tự (an toàn khi lỗi sớm): (1) giữ khóa ghi của {@link ReportingGate} (listener chờ, tin nhắn nằm lại trong queue); (2) ĐỌC outbox của cả 3
 * service nguồn (auth, clinical, billing) - service nào không trả lời thì dừng NGAY, chưa xóa gì; (3) xếp mọi sự kiện theo thời điểm phát sinh;
 * (4) xóa dữ liệu báo cáo từ ngày đầu cửa sổ trở đi, đưa các ca tiếp đón trước cửa sổ nhưng đóng trong cửa sổ về WAITING, quên
 * {@code processed_events} của các sự kiện sẽ phát lại; (5) phát lại từng sự kiện qua đúng bộ xử lý của listener
 * ({@link ReportingEventProcessor}), mỗi sự kiện một giao dịch. Sự kiện không áp dụng được (vd. hoàn tiền của khoản thu đã quá cửa sổ outbox) bị
 * đếm và ghi log, không làm hỏng cả job. Chạy lại nhiều lần cho cùng kết quả (bước 4 luôn xóa lại cửa sổ).
 * <p>
 * Không gửi lại gì lên RabbitMQ: các consumer khác (notification...) không nhận trùng.
 */
@Slf4j
@Service
public class ReportRebuildService {

    static final int DEFAULT_DAYS = 3;
    static final int PAGE_SIZE = 500;
    static final int CHUNK = 500;
    static final int MAX_REASONS = 20;

    /** Kiểu sự kiện reporting xử lý -> lớp để đọc JSON trong outbox. */
    private static final Map<String, Class<? extends DomainEvent>> TYPES = Map.of(
            "VisitCheckedInEvent", VisitCheckedInEvent.class,
            "VisitCompletedEvent", VisitCompletedEvent.class,
            "VisitCancelledEvent", VisitCancelledEvent.class,
            "ServiceInvoicePaidEvent", ServiceInvoicePaidEvent.class,
            "ServiceInvoiceRefundedEvent", ServiceInvoiceRefundedEvent.class,
            "MedicineInvoicePaidEvent", MedicineInvoicePaidEvent.class,
            "UserProfileUpdatedEvent", UserProfileUpdatedEvent.class,
            "PetUpdatedEvent", PetUpdatedEvent.class);

    /** Một service nguồn: tên (cho thông báo lỗi), routing key nó phát mà reporting cần, hàm đọc một trang outbox. */
    record Source(String name, List<String> routingKeys, PageReader reader) {
    }

    @FunctionalInterface
    interface PageReader {
        ApiResponse<List<OutboxReplayItem>> read(String since, List<String> routingKeys, long afterId, int limit);
    }

    record Loaded(int sourceRank, OutboxReplayItem item, DomainEvent event) {
    }

    private final List<Source> sources;
    private final ReportingGate gate;
    private final ReportingEventProcessor processor;
    private final VisitFactRepository visitFacts;
    private final RevenueFactRepository revenueFacts;
    private final DailyRevenueSummaryRepository summaries;
    private final DoctorDailyPerformanceRepository performances;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final int maxDays;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public ReportRebuildService(OutboxClients.Auth auth, OutboxClients.Clinical clinical, OutboxClients.Billing billing, ReportingGate gate,
                                ReportingEventProcessor processor, VisitFactRepository visitFacts, RevenueFactRepository revenueFacts,
                                DailyRevenueSummaryRepository summaries, DoctorDailyPerformanceRepository performances,
                                ProcessedEventRepository processedEvents, ObjectMapper mapper, Clock clock, PlatformTransactionManager transactionManager,
                                @Value("${vetclinic.reporting.rebuild-max-days:6}") int maxDays) {
        this(List.of(new Source("auth-service", List.of(EventRouting.USER_PROFILE_UPDATED), auth::events),
                        new Source("clinical-service", List.of(EventRouting.VISIT_CHECKED_IN, EventRouting.VISIT_COMPLETED, EventRouting.VISIT_CANCELLED,
                                EventRouting.INVOICE_SERVICE_PAID, EventRouting.INVOICE_SERVICE_REFUNDED, EventRouting.PET_UPDATED), clinical::events),
                        new Source("billing-pharmacy-service", List.of(EventRouting.PHARMACY_MEDICINE_INVOICE_PAID), billing::events)),
                gate, processor, visitFacts, revenueFacts, summaries, performances, processedEvents, mapper, clock, new TransactionTemplate(transactionManager), maxDays);
    }

    /** Dùng cho test: truyền thẳng nguồn đọc outbox. */
    ReportRebuildService(List<Source> sources, ReportingGate gate, ReportingEventProcessor processor, VisitFactRepository visitFacts,
                         RevenueFactRepository revenueFacts, DailyRevenueSummaryRepository summaries, DoctorDailyPerformanceRepository performances,
                         ProcessedEventRepository processedEvents, ObjectMapper mapper, Clock clock, TransactionTemplate tx, int maxDays) {
        this.sources = sources;
        this.gate = gate;
        this.processor = processor;
        this.visitFacts = visitFacts;
        this.revenueFacts = revenueFacts;
        this.summaries = summaries;
        this.performances = performances;
        this.processedEvents = processedEvents;
        this.mapper = mapper;
        this.clock = clock;
        this.tx = tx;
        this.maxDays = maxDays;
    }

    public RebuildResponse rebuild(Integer daysParam) {
        int days = daysParam == null ? DEFAULT_DAYS : daysParam;
        if (days < 1 || days > maxDays) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Số ngày dựng lại phải từ 1 đến " + maxDays + " (outbox chỉ giữ 7 ngày)");
        }
        if (!running.compareAndSet(false, true)) {
            throw new AppException(ErrorCode.RESOURCE_BUSY, "Đang có một lần dựng lại báo cáo khác");
        }
        try {
            return gate.exclusive(() -> doRebuild(days));
        } finally {
            running.set(false);
        }
    }

    private RebuildResponse doRebuild(int days) {
        LocalDate from = LocalDate.now(clock).minusDays(days - 1L);
        Instant since = from.atStartOfDay(clock.getZone()).toInstant();

        List<String> reasons = new ArrayList<>();
        int unreadable = 0;
        List<Loaded> loaded = new ArrayList<>();
        for (int rank = 0; rank < sources.size(); rank++) {
            for (OutboxReplayItem item : fetch(sources.get(rank), since)) {
                Class<? extends DomainEvent> type = TYPES.get(item.eventType());
                try {
                    if (type == null) {
                        throw new IllegalArgumentException("kiểu sự kiện không được reporting xử lý: " + item.eventType());
                    }
                    loaded.add(new Loaded(rank, item, mapper.readValue(item.payload(), type)));
                } catch (Exception e) {
                    unreadable++;
                    addReason(reasons, "Không đọc được sự kiện " + item.eventId() + ": " + e.getMessage());
                }
            }
        }
        // Thứ tự phát sinh: theo thời điểm trong eventId (UUIDv7, ms); cùng ms thì theo service rồi theo khóa outbox (thứ tự ghi của chính service đó)
        loaded.sort(Comparator.comparingLong((Loaded l) -> UuidV7.timestampOf(l.event().eventId()))
                .thenComparingInt(Loaded::sourceRank).thenComparingLong(l -> l.item().id()));

        tx.executeWithoutResult(status -> wipe(from, loaded));

        Map<String, Integer> byType = new LinkedHashMap<>();
        int applied = 0;
        int skipped = unreadable;
        for (Loaded l : loaded) {
            byType.merge(l.item().eventType(), 1, Integer::sum);
            try {
                if (processor.apply(l.event())) {
                    applied++;
                }
            } catch (Exception e) {
                skipped++;
                addReason(reasons, l.item().eventType() + " " + l.item().eventId() + ": " + e.getMessage());
                log.warn("Dựng lại báo cáo: bỏ qua {} {}: {}", l.item().eventType(), l.item().eventId(), e.getMessage());
            }
        }
        log.info("Dựng lại báo cáo từ {} ({} ngày): đọc {} sự kiện, áp dụng {}, bỏ qua {}", from, days, loaded.size() + unreadable, applied, skipped);
        return new RebuildResponse(from, days, byType, loaded.size() + unreadable, applied, skipped, reasons);
    }

    /** Đọc hết các trang outbox của một service nguồn; lỗi thì dừng job (chưa xóa gì). */
    private List<OutboxReplayItem> fetch(Source source, Instant since) {
        List<OutboxReplayItem> all = new ArrayList<>();
        long afterId = 0;
        try {
            while (true) {
                ApiResponse<List<OutboxReplayItem>> page = source.reader().read(since.toString(), source.routingKeys(), afterId, PAGE_SIZE);
                List<OutboxReplayItem> items = page == null || page.getData() == null ? List.of() : page.getData();
                if (items.isEmpty()) {
                    return all;
                }
                all.addAll(items);
                afterId = items.get(items.size() - 1).id();
            }
        } catch (Exception e) {
            log.error("Dựng lại báo cáo: không đọc được outbox của {}: {}", source.name(), e.getMessage());
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE, "Không đọc được outbox của " + source.name() + "; báo cáo chưa bị thay đổi");
        }
    }

    /** Xóa cửa sổ báo cáo và những gì khiến sự kiện phát lại bị bỏ qua. Chạy trong MỘT giao dịch. */
    private void wipe(LocalDate from, List<Loaded> loaded) {
        revenueFacts.deleteFromDate(from);
        summaries.deleteFromDate(from);
        performances.deleteFromDate(from);
        visitFacts.deleteFromDate(from);

        Set<UUID> closedVisits = new HashSet<>();
        List<UUID> eventIds = new ArrayList<>();
        for (Loaded l : loaded) {
            eventIds.add(l.event().eventId());
            if (l.event() instanceof VisitCompletedEvent e) {
                closedVisits.add(e.visitId());
            } else if (l.event() instanceof VisitCancelledEvent e) {
                closedVisits.add(e.visitId());
            }
        }
        chunked(new ArrayList<>(closedVisits), ids -> visitFacts.reopenBefore(ids, from));
        chunked(eventIds, processedEvents::deleteByEventIds);
    }

    private static void chunked(List<UUID> ids, Function<List<UUID>, Integer> action) {
        for (int i = 0; i < ids.size(); i += CHUNK) {
            action.apply(ids.subList(i, Math.min(i + CHUNK, ids.size())));
        }
    }

    private static void addReason(List<String> reasons, String reason) {
        if (reasons.size() < MAX_REASONS) {
            reasons.add(reason.length() > 300 ? reason.substring(0, 300) : reason);
        }
    }
}
