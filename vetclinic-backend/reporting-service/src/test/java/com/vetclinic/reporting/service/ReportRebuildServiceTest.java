package com.vetclinic.reporting.service;

import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.event.outbox.OutboxReplayItem;
import com.vetclinic.common.event.processed.ProcessedEventRepository;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.util.UuidV7;
import com.vetclinic.reporting.dto.response.RebuildResponse;
import com.vetclinic.reporting.event.ReportingEventProcessor;
import com.vetclinic.reporting.event.ReportingGate;
import com.vetclinic.reporting.repository.DailyRevenueSummaryRepository;
import com.vetclinic.reporting.repository.DoctorDailyPerformanceRepository;
import com.vetclinic.reporting.repository.RevenueFactRepository;
import com.vetclinic.reporting.repository.VisitFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportRebuildServiceTest {

    @Mock ReportingEventProcessor processor;
    @Mock VisitFactRepository visitFacts;
    @Mock RevenueFactRepository revenueFacts;
    @Mock DailyRevenueSummaryRepository summaries;
    @Mock DoctorDailyPerformanceRepository performances;
    @Mock ProcessedEventRepository processedEvents;
    @Mock PlatformTransactionManager transactionManager;

    final JsonMapper mapper = new JsonMapper();
    /** 21/11/2026 10:00 giờ phòng khám. */
    final Clock clock = Clock.fixed(Instant.parse("2026-11-21T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    final UUID visit = UUID.randomUUID();
    final UUID pet = UUID.randomUUID();

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"));
    }

    private OutboxReplayItem item(long id, DomainEvent event, String type) {
        return new OutboxReplayItem(id, event.eventId(), type, event.routingKey(), mapper.writeValueAsString(event), event.occurredAt(), "PUBLISHED");
    }

    private ReportRebuildService service(List<ReportRebuildService.Source> sources) {
        return new ReportRebuildService(sources, new ReportingGate(), processor, visitFacts, revenueFacts, summaries, performances, processedEvents, mapper,
                clock, new TransactionTemplate(transactionManager), 6);
    }

    private static ReportRebuildService.Source source(String name, List<OutboxReplayItem> items) {
        // trang đầu trả hết, trang sau rỗng (đúng cách job phân trang bằng afterId)
        return new ReportRebuildService.Source(name, List.of("k"), (since, keys, afterId, limit) -> ApiResponse.success(afterId == 0 ? items : List.<OutboxReplayItem>of()));
    }

    @BeforeEach
    void setUp() {
        // nhiều service mô phỏng cùng lúc: không stub gì thêm
    }

    @Test
    void replaysAllSourcesInChronologicalOrderAfterWipingTheWindow() {
        VisitCheckedInEvent in = new VisitCheckedInEvent(UuidV7.generate(at("2026-11-20T03:00:00Z")), Instant.parse("2026-11-20T03:00:00Z"), null, visit, "VIS-1", pet, null, false, null);
        VisitCompletedEvent done = new VisitCompletedEvent(UuidV7.generate(at("2026-11-20T04:00:00Z")), Instant.parse("2026-11-20T04:00:00Z"), null, visit, "VIS-1", pet, false);
        VisitCancelledEvent cancelled = new VisitCancelledEvent(UuidV7.generate(at("2026-11-20T05:00:00Z")), Instant.parse("2026-11-20T05:00:00Z"), null, UUID.randomUUID(), "VIS-2", pet, null, "x");
        // clinical trả lẫn thứ tự; auth không có gì
        var clinical = source("clinical-service", List.of(item(2, done, "VisitCompletedEvent"), item(3, cancelled, "VisitCancelledEvent"), item(1, in, "VisitCheckedInEvent")));
        when(processor.apply(any())).thenReturn(true);

        RebuildResponse res = service(List.of(source("auth-service", List.of()), clinical)).rebuild(2);

        assertThat(res.fromDate()).isEqualTo(LocalDate.of(2026, 11, 20));   // hôm nay 21/11, 2 ngày -> từ 20/11
        assertThat(res.fetched()).isEqualTo(3);
        assertThat(res.applied()).isEqualTo(3);
        assertThat(res.skipped()).isZero();
        assertThat(res.fetchedByType()).containsEntry("VisitCheckedInEvent", 1).containsEntry("VisitCompletedEvent", 1).containsEntry("VisitCancelledEvent", 1);

        InOrder order = inOrder(revenueFacts, summaries, performances, visitFacts, processedEvents, processor);
        order.verify(revenueFacts).deleteFromDate(LocalDate.of(2026, 11, 20));
        order.verify(summaries).deleteFromDate(LocalDate.of(2026, 11, 20));
        order.verify(performances).deleteFromDate(LocalDate.of(2026, 11, 20));
        order.verify(visitFacts).deleteFromDate(LocalDate.of(2026, 11, 20));
        order.verify(visitFacts).reopenBefore(anyCollection(), eq(LocalDate.of(2026, 11, 20)));
        order.verify(processedEvents).deleteByEventIds(anyCollection());
        ArgumentCaptor<DomainEvent> applied = ArgumentCaptor.forClass(DomainEvent.class);
        order.verify(processor, org.mockito.Mockito.times(3)).apply(applied.capture());
        assertThat(applied.getAllValues()).containsExactly(in, done, cancelled);   // theo thời điểm phát sinh, không theo thứ tự trả về
    }

    @Test
    @SuppressWarnings("unchecked")
    void reopensVisitsClosedInWindowAndForgetsReplayedEventIds() {
        VisitCompletedEvent done = new VisitCompletedEvent(UuidV7.generate(at("2026-11-20T04:00:00Z")), Instant.parse("2026-11-20T04:00:00Z"), null, visit, "VIS-1", pet, false);
        when(processor.apply(any())).thenReturn(true);

        service(List.of(source("clinical-service", List.of(item(1, done, "VisitCompletedEvent"))))).rebuild(1);

        ArgumentCaptor<Collection<UUID>> reopened = ArgumentCaptor.forClass(Collection.class);
        verify(visitFacts).reopenBefore(reopened.capture(), eq(LocalDate.of(2026, 11, 21)));
        assertThat(reopened.getValue()).containsExactly(visit);
        ArgumentCaptor<Collection<UUID>> forgotten = ArgumentCaptor.forClass(Collection.class);
        verify(processedEvents).deleteByEventIds(forgotten.capture());
        assertThat(forgotten.getValue()).containsExactly(done.eventId());
    }

    @Test
    void sourceThatCannotBeReadAbortsBeforeAnythingIsDeleted() {
        var broken = new ReportRebuildService.Source("billing-pharmacy-service", List.of("k"), (s, k, a, l) -> {
            throw new IllegalStateException("connection refused");
        });

        assertThatThrownBy(() -> service(List.of(source("auth-service", List.of()), broken)).rebuild(3))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessageContaining("billing-pharmacy-service");

        verifyNoInteractions(revenueFacts, summaries, performances, visitFacts, processedEvents, processor);
    }

    @Test
    void eventThatCannotBeAppliedIsSkippedAndTheRestContinue() {
        VisitCheckedInEvent in = new VisitCheckedInEvent(UuidV7.generate(at("2026-11-21T01:00:00Z")), Instant.parse("2026-11-21T01:00:00Z"), null, visit, "VIS-1", pet, null, false, null);
        VisitCompletedEvent done = new VisitCompletedEvent(UuidV7.generate(at("2026-11-21T02:00:00Z")), Instant.parse("2026-11-21T02:00:00Z"), null, visit, "VIS-1", pet, false);
        doThrow(new IllegalStateException("thiếu dữ liệu đi trước")).when(processor).apply(in);
        when(processor.apply(done)).thenReturn(true);

        RebuildResponse res = service(List.of(source("clinical-service", List.of(item(1, in, "VisitCheckedInEvent"), item(2, done, "VisitCompletedEvent"))))).rebuild(1);

        assertThat(res.applied()).isEqualTo(1);
        assertThat(res.skipped()).isEqualTo(1);
        assertThat(res.skippedReasons()).singleElement().asString().contains("thiếu dữ liệu đi trước");
    }

    @Test
    void unknownEventTypeAndBrokenPayloadAreCountedAsSkippedNotApplied() {
        VisitCheckedInEvent in = new VisitCheckedInEvent(UuidV7.generate(at("2026-11-21T01:00:00Z")), Instant.parse("2026-11-21T01:00:00Z"), null, visit, "VIS-1", pet, null, false, null);
        var weird = new OutboxReplayItem(9, UUID.randomUUID(), "SomethingElseEvent", "k", "{}", Instant.now(), "PUBLISHED");
        var broken = new OutboxReplayItem(10, UUID.randomUUID(), "VisitCheckedInEvent", "k", "not json", Instant.now(), "PUBLISHED");
        when(processor.apply(any())).thenReturn(true);

        RebuildResponse res = service(List.of(source("clinical-service", List.of(item(1, in, "VisitCheckedInEvent"), weird, broken)))).rebuild(1);

        assertThat(res.fetched()).isEqualTo(3);
        assertThat(res.applied()).isEqualTo(1);
        assertThat(res.skipped()).isEqualTo(2);
    }

    @Test
    void rejectsDaysOutsideOneToMax() {
        for (int days : new int[]{0, -1, 7}) {
            assertThatThrownBy(() -> service(List.of()).rebuild(days)).isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));
        }
        verify(processor, never()).apply(any());
    }

    @Test
    void defaultsToThreeDays() {
        RebuildResponse res = service(List.of(source("auth-service", List.of()))).rebuild(null);

        assertThat(res.days()).isEqualTo(3);
        assertThat(res.fromDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(res.fetched()).isZero();
    }
}
