package com.vetclinic.reporting.event;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Cổng giữa các listener và job dựng lại báo cáo (Task 4.5.4). Listener chạy chung (khóa đọc: nhiều listener cùng lúc); job dựng lại giữ
 * khóa ghi suốt lúc đọc outbox, xóa cửa sổ báo cáo và phát lại, nên trong thời gian đó listener CHỜ (tin nhắn nằm lại trong queue, không bị
 * DLQ) và xử lý tiếp ngay khi job xong. Luồng giữ khóa ghi vẫn gọi được vào listener (hạ cấp từ ghi xuống đọc của ReentrantReadWriteLock).
 */
@Component
public class ReportingGate {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public <T> T shared(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> T exclusive(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
