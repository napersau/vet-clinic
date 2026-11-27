package com.vetclinic.clinical.queue;

/** Kết quả "Gọi" một phiếu: {@code calledCount} là số lần đã gọi sau lần này (0 nếu không gọi được). */
public record CallResult(QueueOutcome outcome, int calledCount) {
}
