import { useState } from 'react';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Button, ConfirmDialog, Field, Input, Notice, PageHead, Panel, Select, Stats, Table, Tabs } from '../../components/ui';
import { appointmentsApi, reportsApi } from '../../api/endpoints';
import useAsync from '../../hooks/useAsync';
import { formatDate, formatMoney, formatNumber, todayIso } from '../../lib/format';
import { groupRevenue } from '../../lib/reportPeriods';
import { APPOINTMENT_STATUS, BOOKING_CHANNEL, serviceCategoryLabel } from '../../lib/labels';

const percent = (value) => `${formatNumber((value || 0) * 100, 1)}%`;
const MAX_DAYS = 366;

function Bars({ rows, label, value, format }) {
  const max = Math.max(...rows.map((row) => Number(row[value])), 1);
  return <div className="ui-stack" style={{ gap: 6 }} role="img" aria-label="Biểu đồ cột">
    {rows.map((row) => <div key={row[label]} style={{ display: 'grid', gridTemplateColumns: '110px 1fr 120px', gap: 10, alignItems: 'center', fontSize: 12 }}>
      <span className="ui-muted">{row[label]}</span>
      <div style={{ background: 'var(--ui-raised)', borderRadius: 6, height: 14 }}><div style={{ width: `${(Number(row[value]) / max) * 100}%`, height: '100%', borderRadius: 6, background: 'var(--ui-brand)' }} /></div>
      <strong style={{ textAlign: 'right', color: 'var(--ui-text)' }}>{format(row[value])}</strong>
    </div>)}
  </div>;
}

const REVENUE_TYPE = { SERVICE: 'Dịch vụ', MEDICINE: 'Thuốc' };
const REPORT_VISIT_STATUS = { WAITING: 'Chưa hoàn tất', COMPLETED: 'Hoàn tất', CANCELLED: 'Đã hủy' };

/** Doanh thu v4.0: MỘT nguồn (reporting-service), đã gộp dịch vụ + thuốc và trừ hoàn tiền (dòng âm). Gom tuần / tháng / quý làm ở giao diện. */
function Revenue({ range, groupBy }) {
  const query = { from: range.fromDate, to: range.toDate };
  const report = useAsync(async () => {
    const [daily, categories] = await Promise.all([reportsApi.revenueDaily(query), reportsApi.revenueByCategory(query)]);
    return { daily, categories };
  }, [range.fromDate, range.toDate]);
  return <AsyncView state={report}>{({ daily, categories }) => {
    const totals = daily.totals;
    const periods = groupRevenue(daily.items, groupBy);
    return <>
      <Stats items={[['Doanh thu thực thu', formatMoney(totals.netAmount), 'Đã gồm VAT, trừ giảm giá và hoàn tiền', 'green'],
        ['Dịch vụ', formatMoney(totals.serviceNetAmount)], ['Thuốc', formatMoney(totals.medicineNetAmount)],
        ['Giảm giá đã cho', formatMoney(totals.discountAmount), `${formatNumber(totals.lineCount)} dòng thu (đã trừ dòng hoàn)`]]} />
      <Panel title="Doanh thu theo nhóm" subtitle="Nhóm dịch vụ và nhóm thuốc trong kỳ; dòng hoàn tiền đã trừ vào nhóm tương ứng (giờ Việt Nam)." flush>
        <Table caption="Doanh thu theo nhóm" rowKey="rowId" empty="Chưa có doanh thu trong kỳ." rows={categories.items.map((row) => ({ ...row, rowId: `${row.revenueType}-${row.categoryCode}` }))} columns={[
          { title: 'Loại', render: (row) => REVENUE_TYPE[row.revenueType] || row.revenueType }, { title: 'Nhóm', render: (row) => row.categoryName || serviceCategoryLabel(row.categoryCode) }, { title: 'Dòng', align: 'right', key: 'lineCount' },
          { title: 'Trước VAT', align: 'right', render: (row) => formatMoney(row.amountBeforeVat) }, { title: 'VAT', align: 'right', render: (row) => formatMoney(row.vatAmount) },
          { title: 'Giảm giá', align: 'right', render: (row) => formatMoney(row.discountAmount) }, { title: 'Thực thu', align: 'right', render: (row) => <strong>{formatMoney(row.netAmount)}</strong> }]} />
      </Panel>
      <Panel title="Doanh thu theo kỳ" subtitle="Theo ngày thu tiền (dịch vụ + thuốc, đã trừ hoàn tiền).">
        {periods.length ? <Bars rows={periods} label="label" value="netAmount" format={formatMoney} /> : <p className="ui-muted">Chưa có doanh thu trong kỳ.</p>}
      </Panel>
      <Panel title="Chi tiết doanh thu theo kỳ" flush><Table caption="Doanh thu theo kỳ" rowKey="key" empty="Chưa có doanh thu trong kỳ." rows={periods} columns={[
        { title: 'Kỳ', key: 'label' }, { title: 'Dịch vụ', align: 'right', render: (row) => formatMoney(row.serviceNet) }, { title: 'Thuốc', align: 'right', render: (row) => formatMoney(row.medicineNet) },
        { title: 'Giảm giá', align: 'right', render: (row) => formatMoney(row.discountAmount) }, { title: 'Thực thu', align: 'right', render: (row) => <strong>{formatMoney(row.netAmount)}</strong> }]} /></Panel>
    </>;
  }}</AsyncView>;
}

function Operations({ range }) {
  const query = { from: range.fromDate, to: range.toDate };
  const report = useAsync(async () => {
    const [summary, performance] = await Promise.all([reportsApi.visitsSummary(query), reportsApi.doctorPerformance(query)]);
    return { summary, performance };
  }, [range.fromDate, range.toDate]);
  return <AsyncView state={report}>{({ summary: r, performance }) => {
    const assigned = Object.fromEntries(r.doctors.map((d) => [d.doctorId, d.assignedVisits]));
    const doctors = performance.doctors.map((d) => ({ ...d, assignedVisits: assigned[d.doctorId] ?? 0 }));
    return <>
      <Stats items={[['Tổng số ca', r.totalVisits], ['Hoàn tất', r.completedVisits, undefined, 'green'], ['Đã hủy', r.cancelledVisits], ['Cấp cứu', r.emergencyVisits, undefined, r.emergencyVisits ? 'red' : ''], ['Ca thường', r.routineVisits], ['Ca có cận lâm sàng', r.paraclinicalVisits]]} />
      <div className="ui-cols">
        <Panel title="Ca khám theo trạng thái" subtitle="Theo ngày tiếp đón." flush><Table caption="Ca theo trạng thái" rowKey="status" rows={Object.entries(r.byStatus).map(([status, count]) => ({ status, count }))} columns={[{ title: 'Trạng thái', render: (row) => REPORT_VISIT_STATUS[row.status] || row.status }, { title: 'Số ca', align: 'right', key: 'count' }]} /></Panel>
        <Panel title="Hiệu suất theo bác sĩ" subtitle="Hoàn tất / hủy / cấp cứu tính theo ngày xảy ra; doanh thu là dịch vụ đã thu (trừ hoàn tiền)." flush><Table caption="Bác sĩ" rowKey="doctorId" rows={doctors} columns={[
          { title: 'Bác sĩ', render: (row) => row.doctorName || `#${row.doctorId}` }, { title: 'Được giao', align: 'right', key: 'assignedVisits' }, { title: 'Hoàn tất', align: 'right', key: 'completedVisits' },
          { title: 'Hủy', align: 'right', key: 'cancelledVisits' }, { title: 'Cấp cứu', align: 'right', key: 'emergencyVisits' }, { title: 'Doanh thu dịch vụ', align: 'right', render: (row) => <strong>{formatMoney(row.serviceNetRevenue)}</strong> }]} empty="Chưa có ca nào được ghi nhận." /></Panel>
      </div>
    </>;
  }}</AsyncView>;
}

function Appointments({ range }) {
  const report = useAsync(() => appointmentsApi.report(range), [range.fromDate, range.toDate]);
  return <AsyncView state={report}>{(r) => <>
    <Stats items={[['Tổng lịch hẹn', r.totalAppointments], ['Tỉ lệ vắng mặt', percent(r.noShowRate), 'Vắng ÷ (đã tiếp nhận + vắng)', r.noShowRate > 0.2 ? 'red' : ''], ['Tỉ lệ hủy', percent(r.cancellationRate), 'Hủy ÷ tổng lịch hẹn']]} />
    <div className="ui-cols">
      <Panel title="Theo trạng thái" flush><Table caption="Lịch hẹn theo trạng thái" rowKey="key" rows={Object.entries(r.byStatus).map(([key, count]) => ({ key, count }))} columns={[{ title: 'Trạng thái', render: (row) => APPOINTMENT_STATUS[row.key]?.label || row.key }, { title: 'Số lịch', align: 'right', key: 'count' }]} /></Panel>
      <Panel title="Theo kênh đặt" flush><Table caption="Lịch hẹn theo kênh" rowKey="key" rows={Object.entries(r.byChannel).map(([key, count]) => ({ key, count }))} columns={[{ title: 'Kênh', render: (row) => BOOKING_CHANNEL[row.key] || row.key }, { title: 'Số lịch', align: 'right', key: 'count' }]} /></Panel>
    </div>
  </>}</AsyncView>;
}

export default function ReportsPage() {
  const [tab, setTab] = useState('revenue');
  const [from, setFrom] = useState(todayIso(-29));
  const [to, setTo] = useState(todayIso());
  const [groupBy, setGroupBy] = useState('DAY');
  const days = (new Date(to) - new Date(from)) / 86400000 + 1;
  const invalid = !from || !to || from > to ? 'Khoảng ngày không hợp lệ: ngày bắt đầu phải trước hoặc bằng ngày kết thúc.' : days > MAX_DAYS ? `Khoảng báo cáo tối đa ${MAX_DAYS} ngày.` : '';
  const [applied, setApplied] = useState({ fromDate: from, toDate: to });
  const [rebuilding, setRebuilding] = useState(false);
  const [rebuildDays, setRebuildDays] = useState('3');
  const [notice, setNotice] = useState(null);
  const [reload, setReload] = useState(0);   // tăng sau khi dựng lại để các tab nạp lại số liệu
  return <StaffLayout area="admin" title="Báo cáo & thống kê">
    <PageHead eyebrow="BÁO CÁO" title="Báo cáo & thống kê" actions={<Button onClick={() => setRebuilding(true)}>Dựng lại báo cáo</Button>}>Doanh thu (dịch vụ + thuốc, đã trừ hoàn tiền), vận hành phòng khám và hiệu quả đặt lịch theo khoảng ngày (giờ Việt Nam). Mặc định 30 ngày gần nhất. Nút "Dựng lại báo cáo" dùng khi số liệu bị lệch hoặc thiếu so với giao dịch.</PageHead>
    <Notice notice={notice} onClose={() => setNotice(null)} />
    <Panel>
      <div className="ui-toolbar" style={{ margin: 0, alignItems: 'flex-end' }}>
        <Field label="Từ ngày"><Input type="date" value={from} max={to} onChange={(event) => setFrom(event.target.value)} /></Field>
        <Field label="Đến ngày"><Input type="date" value={to} min={from} onChange={(event) => setTo(event.target.value)} /></Field>
        {tab === 'revenue' && <Field label="Nhóm theo"><Select value={groupBy} onChange={(event) => setGroupBy(event.target.value)}><option value="DAY">Ngày</option><option value="WEEK">Tuần</option><option value="MONTH">Tháng</option><option value="QUARTER">Quý</option></Select></Field>}
        <Button variant="primary" disabled={Boolean(invalid)} onClick={() => setApplied({ fromDate: from, toDate: to })}>Xem báo cáo</Button>
      </div>
      {invalid && <div className="ui-alert error" role="alert" style={{ marginTop: 12 }}><span>{invalid}</span></div>}
      <p className="ui-muted" style={{ fontSize: 12, marginBottom: 0 }}>Đang xem: {formatDate(applied.fromDate)} – {formatDate(applied.toDate)}</p>
    </Panel>
    <Tabs tabs={[{ key: 'revenue', label: 'Doanh thu' }, { key: 'operations', label: 'Vận hành' }, { key: 'appointments', label: 'Lịch hẹn' }]} value={tab} onChange={setTab} />
    {tab === 'revenue' && <Revenue key={reload} range={applied} groupBy={groupBy} />}
    {tab === 'operations' && <Operations key={reload} range={applied} />}
    {tab === 'appointments' && <Appointments range={applied} />}
    {rebuilding && <ConfirmDialog title="Dựng lại báo cáo" confirmLabel="Dựng lại" onClose={() => setRebuilding(false)} onConfirm={async () => {
      const result = await reportsApi.rebuild(rebuildDays);
      setRebuilding(false);
      setReload((value) => value + 1);
      setNotice({ type: result.skipped ? 'info' : 'success', text: `Đã dựng lại báo cáo từ ${formatDate(result.fromDate)}: đọc ${result.fetched} sự kiện, áp dụng ${result.applied}${result.skipped ? `, bỏ qua ${result.skipped} (${result.skippedReasons[0]})` : ''}.` });
    }}>
      Đọc lại các sự kiện trong outbox của các service nguồn và tính lại doanh thu, tổng hợp theo ngày, hiệu suất Bác sĩ của những ngày gần nhất.
      Dữ liệu của các ngày cũ hơn được giữ nguyên (outbox chỉ giữ 7 ngày). Chạy lại nhiều lần cho cùng kết quả; trong lúc chạy, báo cáo tạm dừng nhận sự kiện mới vài giây.
      <Field label="Số ngày gần nhất (kể cả hôm nay)"><Select value={rebuildDays} onChange={(event) => setRebuildDays(event.target.value)}>{[1, 2, 3, 4, 5, 6].map((n) => <option key={n} value={n}>{n} ngày</option>)}</Select></Field>
    </ConfirmDialog>}
  </StaffLayout>;
}
