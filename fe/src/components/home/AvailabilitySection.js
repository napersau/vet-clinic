import { useState } from 'react';
import SectionHeading from '../common/SectionHeading';
import { appointmentsApi } from '../../api/endpoints';
import useAsync from '../../hooks/useAsync';
import { formatTime, todayIso } from '../../lib/format';

const chip = (available) => ({
  display: 'inline-flex', flexDirection: 'column', alignItems: 'center', minWidth: 92, padding: '10px 14px', borderRadius: 14,
  border: '1px solid currentColor', opacity: available ? 1 : 0.45, fontWeight: 600,
});

/** Khung giờ còn trống, công khai (không cần đăng nhập): GET /appointments/slots đi qua Gateway không cần token (FR-13). */
export default function AvailabilitySection() {
  const [date, setDate] = useState(todayIso());
  const state = useAsync(() => appointmentsApi.slots(date), [date]);
  const slots = state.data || [];
  return <section id="availability" className="section">
    <div className="container">
      <SectionHeading icon="🕘" eyebrow="Lịch khám" description="Xem nhanh khung giờ còn chỗ trong ngày. Đăng nhập để đặt lịch cho bé.">Khung giờ <span className="gradient-text">còn trống</span></SectionHeading>
      <p style={{ textAlign: 'center' }}><label>Ngày khám{' '}<input type="date" min={todayIso()} value={date} onChange={(event) => setDate(event.target.value || todayIso())} /></label></p>
      {state.loading && <p style={{ textAlign: 'center' }} role="status">Đang tải khung giờ…</p>}
      {state.error && <p style={{ textAlign: 'center' }} role="alert">Chưa tải được khung giờ trống. <button type="button" onClick={state.reload}>Thử lại</button></p>}
      {!state.loading && slots.length > 0 && <div role="list" aria-label="Khung giờ khám" style={{ display: 'flex', flexWrap: 'wrap', gap: 12, justifyContent: 'center' }}>
        {slots.map((slot) => {
          const available = slot.available && !slot.past;
          return <div role="listitem" key={slot.startTime} style={chip(available)}>{formatTime(slot.startTime)}<small>{slot.past ? 'Đã qua' : available ? `Còn ${slot.remaining}/${slot.capacity}` : 'Hết chỗ'}</small></div>;
        })}
      </div>}
    </div>
  </section>;
}
