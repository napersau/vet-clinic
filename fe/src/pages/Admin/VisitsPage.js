import { useState } from 'react';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Badge, Button, ConfirmDialog, Field, FormDialog, Input, Notice, PageHead, Pager, Panel, Select, Table, Textarea } from '../../components/ui';
import { queueTicketsApi, visitsApi } from '../../api/endpoints';
import useAsync from '../../hooks/useAsync';
import useDebounced from '../../hooks/useDebounced';
import { formatDateTime } from '../../lib/format';
import { VISIT_STATUS } from '../../lib/labels';

function ReopenDialog({ visit, onClose, onDone }) {
  const [reason, setReason] = useState('');
  return <FormDialog title={`Mở lại đợt khám ${visit.visitCode}`} onClose={onClose} submitLabel="Mở lại" danger
    onSubmit={async () => { if (!reason.trim()) throw new Error('Nhập lý do mở lại.'); await visitsApi.reopen(visit.id, reason.trim()); onDone(`Đã mở lại ${visit.visitCode}: hóa đơn chờ thanh toán đã hủy, bác sĩ có thể chỉnh sửa và chốt ca lại.`); }}>
    <p className="ui-confirm-text">Chỉ mở lại được ca <strong>chưa thu tiền</strong> và khi bác sĩ phụ trách không bận ca khác. Hóa đơn chờ thanh toán sẽ bị hủy, hồ sơ được mở khóa; khi bác sĩ chốt lại, hóa đơn được lập lại đúng mã cũ.</p>
    <Field label="Lý do *"><Textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} autoFocus /></Field>
  </FormDialog>;
}

function HistoryDialog({ visit, onClose }) {
  const history = useAsync(() => visitsApi.history(visit.id), [visit.id]);
  return <FormDialog title={`Lịch sử ${visit.visitCode}`} onClose={onClose} onSubmit={async () => {}} submitLabel="Đóng">
    <AsyncView state={history} empty="Chưa có lịch sử.">{(items) => <ul className="ui-list">{items.map((item) => <li key={item.id}><div><strong>{item.fromStatus ? `${VISIT_STATUS[item.fromStatus]?.label} → ` : ''}{VISIT_STATUS[item.toStatus]?.label}</strong>{item.notes && <small>{item.notes}</small>}</div><small>{formatDateTime(item.createdAt)}</small></li>)}</ul>}</AsyncView>
  </FormDialog>;
}

export default function VisitsPage() {
  const [status, setStatus] = useState('WAITING_PAYMENT');
  const [keyword, setKeyword] = useState('');
  const [date, setDate] = useState('');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState(null);
  const [notice, setNotice] = useState(null);
  const search = useDebounced(keyword);
  const list = useAsync(() => visitsApi.list({ status, keyword: search.trim() || undefined, date, page, size: 20 }), [status, search, date, page]);
  const done = (text) => { setDialog(null); setNotice({ type: 'success', text }); list.refresh(); };
  const reset = (setter) => (event) => { setter(event.target.value); setPage(0); };
  return <StaffLayout area="admin" title="Đợt khám">
    <PageHead eyebrow="GIÁM SÁT" title="Đợt khám" actions={<Button onClick={() => setDialog({ type: 'rebuild' })}>Dựng lại hàng đợi</Button>}>Tra cứu mọi đợt khám. Quản trị viên là người duy nhất mở lại ca đã chốt nhưng chưa thu tiền (khi bác sĩ cần chỉnh sửa hồ sơ). Nút "Dựng lại hàng đợi" dùng khi hàng đợi trên Redis bị mất hoặc thiếu phiếu.</PageHead>
    <Notice notice={notice} onClose={() => setNotice(null)} />
    <div className="ui-toolbar">
      <Input aria-label="Mã đợt khám" placeholder="Mã đợt khám (VIS-…)" value={keyword} onChange={reset(setKeyword)} />
      <Select aria-label="Trạng thái" value={status} onChange={reset(setStatus)}><option value="">Mọi trạng thái</option>{Object.entries(VISIT_STATUS).map(([key, value]) => <option key={key} value={key}>{value.label}</option>)}</Select>
      <Input type="date" aria-label="Ngày tiếp nhận" value={date} onChange={reset(setDate)} />
    </div>
    <Panel flush>
      <AsyncView state={list} isEmpty={(data) => data.items.length === 0} empty="Không có đợt khám phù hợp.">
        {(data) => <>
          <Table caption="Đợt khám" rows={data.items} columns={[
            { title: 'Mã ca', render: (row) => <><strong>{row.visitCode}</strong><small>{formatDateTime(row.checkedInAt)}</small></> },
            { title: 'Thú cưng', render: (row) => <>{row.petName}{row.emergency && <span className="ui-emergency"> · CẤP CỨU</span>}<small>{row.speciesName}</small></> },
            { title: 'Lý do đến khám', render: (row) => row.reasonForVisit || '—' },
            { title: 'Trạng thái', render: (row) => <Badge map={VISIT_STATUS} value={row.status} /> },
            { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
              {row.status === 'WAITING_PAYMENT' && <Button className="small danger" onClick={() => setDialog({ type: 'reopen', visit: row })}>Mở lại</Button>}
              <Button className="small" onClick={() => setDialog({ type: 'history', visit: row })}>Lịch sử</Button></div> }]} />
          <Pager page={data} onChange={setPage} />
        </>}
      </AsyncView>
    </Panel>
    {dialog?.type === 'reopen' && <ReopenDialog visit={dialog.visit} onClose={() => setDialog(null)} onDone={done} />}
    {dialog?.type === 'history' && <HistoryDialog visit={dialog.visit} onClose={() => setDialog(null)} />}
    {dialog?.type === 'rebuild' && <ConfirmDialog title="Dựng lại hàng đợi" confirmLabel="Dựng lại" onClose={() => setDialog(null)} onConfirm={async () => {
      const result = await queueTicketsApi.rebuild();
      done(result?.ran === false ? 'Đang có lần dựng khác chạy, thử lại sau ít giây.'
        : `Đã dựng lại hàng đợi từ MySQL: bổ sung ${result?.doctorTickets ?? 0} phiếu Bác sĩ, ${result?.roomTickets ?? 0} phiếu phòng (${result?.alreadyIssued ?? 0} phiếu đã có, không phát lại).`);
    }}>
      Phát lại các phiếu xếp hàng còn thiếu trên Redis theo dữ liệu trong MySQL (ca đã nộp phí khám, ca cấp cứu, ca đã có kết quả, dịch vụ XN / CĐHA đã thu tiền), đúng thứ tự giờ thu tiền.
      Phiếu đã có không bị phát trùng. Số phiếu mới tiếp nối sau lịch sử trong ngày; khách đã cầm số cũ có thể được cấp số mới.
    </ConfirmDialog>}
  </StaffLayout>;
}
