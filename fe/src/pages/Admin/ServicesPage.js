import { useState } from 'react';
import StaffLayout from '../../layouts/StaffLayout';
import Modal from '../../components/common/Modal';
import { AsyncView, Badge, Button, Field, FormDialog, Input, Notice, PageHead, Panel, Select, Table, Tabs, Textarea } from '../../components/ui';
import { catalogApi } from '../../api/endpoints';
import { mapFieldErrors } from '../../api/client';
import useAsync from '../../hooks/useAsync';
import { blankToNull, formatDateTime, formatMoney } from '../../lib/format';

const QUEUE_LABEL = { DOCTOR: 'Hàng đợi Bác sĩ', LABORATORY: 'Hàng đợi Xét nghiệm', IMAGING: 'Hàng đợi CĐHA' };

/** Ngày giờ nhập ở ô datetime-local (giờ máy) → mốc ISO (UTC) gửi backend; để trống = áp dụng ngay. */
const toIsoOrNull = (local) => (local ? new Date(local).toISOString() : null);
const vatPercent = (rate) => Math.round(Number(rate || 0) * 100);

function ServiceDialog({ service, categories, onClose, onSaved }) {
  const editing = Boolean(service);
  const firstCategory = categories.find((c) => c.active)?.code || '';
  const [form, setForm] = useState({ code: service?.code || '', name: service?.name || '', categoryCode: service?.category || firstCategory, unit: service?.unit || 'Lần', estimatedMinutes: service?.estimatedMinutes ?? '', preparationNotes: service?.preparationNotes || '', price: '', vatPercent: 0, description: service?.description || '', active: service?.active ?? true });
  const [errors, setErrors] = useState({});
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.type === 'checkbox' ? event.target.checked : event.target.value });
  const submit = async () => {
    const found = {};
    if (!editing && !form.code.trim()) found.code = 'Nhập mã dịch vụ.';
    if (!form.name.trim()) found.name = 'Nhập tên dịch vụ.';
    if (!form.unit.trim()) found.unit = 'Nhập đơn vị tính.';
    if (!editing && (form.price === '' || Number(form.price) < 0)) found.price = 'Đơn giá không được âm.';
    if (!editing && (form.vatPercent === '' || Number(form.vatPercent) < 0 || Number(form.vatPercent) > 100)) found.vatRate = 'VAT từ 0 đến 100%.';
    setErrors(found);
    if (Object.keys(found).length) throw new Error('Vui lòng kiểm tra lại các trường được đánh dấu.');
    const info = { name: form.name.trim(), unit: form.unit.trim(), estimatedMinutes: form.estimatedMinutes === '' ? null : Number(form.estimatedMinutes), preparationNotes: blankToNull(form.preparationNotes), description: blankToNull(form.description) };
    try {
      const saved = editing
        ? await catalogApi.updateService(service.id, { ...info, active: form.active })
        : await catalogApi.createService({ code: form.code.trim().toUpperCase(), categoryCode: form.categoryCode, ...info, price: Number(form.price), vatRate: Number(form.vatPercent) / 100 });
      onSaved(saved);
    } catch (problem) { setErrors(mapFieldErrors(problem)); throw problem; }
  };
  return <FormDialog title={editing ? `Sửa dịch vụ ${service.name}` : 'Thêm dịch vụ'} id="service-form-title" onClose={onClose} onSubmit={submit} submitLabel={editing ? 'Lưu thay đổi' : 'Thêm dịch vụ'}>
    <div className="ui-grid">
      <Field label="Mã dịch vụ *" error={errors.code} hint={editing ? 'Mã không đổi được.' : 'Ví dụ: VAC_RABIES'}><Input value={form.code} onChange={set('code')} disabled={editing} maxLength={50} /></Field>
      <Field label="Nhóm dịch vụ *" error={errors.categoryCode} hint={editing ? 'Nhóm không đổi được.' : 'Nhóm quyết định hàng đợi và việc thu trước.'}>
        <Select value={form.categoryCode} onChange={set('categoryCode')} disabled={editing}>{categories.filter((c) => c.active || c.code === form.categoryCode).map((c) => <option key={c.id} value={c.code}>{c.name}</option>)}</Select></Field>
      <Field label="Tên dịch vụ *" error={errors.name} wide><Input value={form.name} onChange={set('name')} maxLength={150} /></Field>
      <Field label="Đơn vị tính *" error={errors.unit} hint="Lần, mũi, tư thế..."><Input value={form.unit} onChange={set('unit')} maxLength={30} /></Field>
      <Field label="Thời gian dự kiến (phút)" error={errors.estimatedMinutes}><Input type="number" min="1" max="600" value={form.estimatedMinutes} onChange={set('estimatedMinutes')} /></Field>
      <Field label="Dặn dò chuẩn bị" error={errors.preparationNotes} wide hint="Ví dụ: nhịn ăn 8 giờ trước khi siêu âm."><Input value={form.preparationNotes} onChange={set('preparationNotes')} maxLength={255} /></Field>
      {!editing && <Field label="Đơn giá (VND) *" error={errors.price}><Input type="number" min="0" step="1000" value={form.price} onChange={set('price')} /></Field>}
      {!editing && <Field label="VAT (%) *" error={errors.vatRate} hint="Giá và VAT được chốt vào hóa đơn lúc chỉ định."><Input type="number" min="0" max="100" step="1" value={form.vatPercent} onChange={set('vatPercent')} /></Field>}
      {editing && <label className="ui-check"><input type="checkbox" checked={form.active} onChange={set('active')} />Đang cung cấp</label>}
      <Field label="Mô tả" wide><Textarea value={form.description} onChange={set('description')} rows={2} /></Field>
    </div>
    {editing && <p className="ui-muted" style={{ fontSize: 12 }}>Đổi đơn giá ở nút “Bảng giá”: giá mới chỉ áp dụng cho chỉ định từ thời điểm hiệu lực, hóa đơn đã lập giữ giá cũ. Dịch vụ đã phát sinh giao dịch chỉ ngừng cung cấp, không xóa.</p>}
  </FormDialog>;
}

/** Lịch sử giá của một dịch vụ + đặt giá mới (đóng dòng đang mở, thêm dòng mới; dòng cũ không bao giờ bị sửa). */
function PriceDialog({ service, onClose, onChanged }) {
  const history = useAsync(() => catalogApi.servicePrices(service.id), [service.id]);
  const [form, setForm] = useState({ price: '', vatPercent: service.vatRate == null ? 0 : vatPercent(service.vatRate), effectiveFrom: '', reason: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });
  const submit = async (event) => {
    event.preventDefault();
    if (busy) return;
    if (form.price === '' || Number(form.price) < 0) { setError('Nhập đơn giá hợp lệ.'); return; }
    if (form.vatPercent === '' || Number(form.vatPercent) < 0 || Number(form.vatPercent) > 100) { setError('VAT từ 0 đến 100%.'); return; }
    setBusy(true); setError('');
    try {
      await catalogApi.changeServicePrice(service.id, { price: Number(form.price), vatRate: Number(form.vatPercent) / 100, effectiveFrom: toIsoOrNull(form.effectiveFrom), reason: blankToNull(form.reason) });
      setForm({ ...form, price: '', effectiveFrom: '', reason: '' });
      history.refresh(); onChanged();
    } catch (problem) { setError(problem.message || 'Không đặt được giá mới.'); } finally { setBusy(false); }
  };
  return <Modal title={`Bảng giá — ${service.name}`} id="service-price-title" onClose={onClose} busy={busy} className="ui-dialog-wide">
    <div className="ui-form-body">
      <AsyncView state={history} empty="Chưa có dòng giá nào.">
        {(rows) => <Table caption="Lịch sử giá" rows={rows} columns={[
          { title: 'Đơn giá', align: 'right', render: (row) => <>{formatMoney(row.price)}<small>VAT {vatPercent(row.vatRate)}%</small></> },
          { title: 'Hiệu lực từ', render: (row) => formatDateTime(row.effectiveFrom) },
          { title: 'Đến', render: (row) => (row.effectiveTo ? formatDateTime(row.effectiveTo) : '—') },
          { title: 'Lý do', render: (row) => row.changeReason || '—' },
          { title: '', render: (row) => (row.current ? <Badge tone="green">Đang áp dụng</Badge> : (!row.effectiveTo ? <Badge tone="blue">Đã lên lịch</Badge> : <Badge tone="gray">Đã đóng</Badge>)) }]} />}
      </AsyncView>
      <form onSubmit={submit} noValidate>
        <h3 className="ui-subhead" style={{ marginTop: 16 }}>Đặt giá mới</h3>
        <div className="ui-grid">
          <Field label="Đơn giá mới (VND) *"><Input type="number" min="0" step="1000" value={form.price} onChange={set('price')} /></Field>
          <Field label="VAT (%) *"><Input type="number" min="0" max="100" step="1" value={form.vatPercent} onChange={set('vatPercent')} /></Field>
          <Field label="Hiệu lực từ" hint="Để trống = áp dụng ngay. Phải sau thời điểm bắt đầu của giá đang mở."><Input type="datetime-local" value={form.effectiveFrom} onChange={set('effectiveFrom')} /></Field>
          <Field label="Lý do"><Input value={form.reason} onChange={set('reason')} maxLength={255} /></Field>
        </div>
        {error && <div className="ui-alert error" role="alert"><span>{error}</span></div>}
        <div className="ui-form-actions"><Button onClick={onClose}>Đóng</Button><Button type="submit" variant="primary" busy={busy}>Đặt giá mới</Button></div>
      </form>
    </div>
  </Modal>;
}

function CategoryDialog({ category, onClose, onSaved }) {
  const editing = Boolean(category);
  const [form, setForm] = useState({ code: category?.code || '', name: category?.name || '', queueType: category?.queueType || '', requiresPrepayment: category?.requiresPrepayment ?? false, displayOrder: category?.displayOrder ?? 0, active: category?.active ?? true });
  const [errors, setErrors] = useState({});
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.type === 'checkbox' ? event.target.checked : event.target.value });
  const submit = async () => {
    const found = {};
    if (!editing && !form.code.trim()) found.code = 'Nhập mã nhóm.';
    if (!form.name.trim()) found.name = 'Nhập tên nhóm.';
    setErrors(found);
    if (Object.keys(found).length) throw new Error('Vui lòng kiểm tra lại các trường được đánh dấu.');
    const body = { code: (editing ? category.code : form.code).trim().toUpperCase(), name: form.name.trim(), queueType: form.queueType, requiresPrepayment: form.requiresPrepayment, displayOrder: Number(form.displayOrder) || 0, active: form.active };
    try { onSaved(editing ? await catalogApi.updateServiceCategory(category.id, body) : await catalogApi.createServiceCategory(body)); } catch (problem) { setErrors(mapFieldErrors(problem)); throw problem; }
  };
  return <FormDialog title={editing ? `Sửa nhóm ${category.name}` : 'Thêm nhóm dịch vụ'} id="category-form-title" onClose={onClose} onSubmit={submit} submitLabel={editing ? 'Lưu thay đổi' : 'Thêm nhóm'}>
    <div className="ui-grid">
      <Field label="Mã nhóm *" error={errors.code} hint={editing ? 'Mã không đổi được.' : 'Ví dụ: DENTAL'}><Input value={form.code} onChange={set('code')} disabled={editing} maxLength={30} /></Field>
      <Field label="Tên nhóm *" error={errors.name}><Input value={form.name} onChange={set('name')} maxLength={100} /></Field>
      <Field label="Hàng đợi" hint="Để trống = làm ngay tại phòng Bác sĩ, không xếp hàng riêng."><Select value={form.queueType} onChange={set('queueType')}><option value="">Làm tại phòng (không xếp hàng)</option>{Object.entries(QUEUE_LABEL).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></Field>
      <Field label="Thứ tự hiển thị"><Input type="number" min="0" max="1000" value={form.displayOrder} onChange={set('displayOrder')} /></Field>
      <label className="ui-check"><input type="checkbox" checked={form.requiresPrepayment} onChange={set('requiresPrepayment')} />Thu tiền trước (khách nộp xong mới được phát phiếu xếp hàng — khám, xét nghiệm, CĐHA)</label>
      {editing && <label className="ui-check"><input type="checkbox" checked={form.active} onChange={set('active')} />Đang sử dụng</label>}
    </div>
    <p className="ui-muted" style={{ fontSize: 12 }}>Đổi hàng đợi / chính sách thu trước áp dụng cho các lần chỉ định, thu tiền và phát phiếu tiếp theo của mọi dịch vụ trong nhóm.</p>
  </FormDialog>;
}

function CategoriesTab({ categories, onChanged }) {
  const [dialog, setDialog] = useState(null);
  return <Panel flush title="Nhóm dịch vụ" subtitle="Nhóm quyết định dịch vụ vào hàng đợi nào và có thu tiền trước không." actions={<Button variant="primary" onClick={() => setDialog({})}>+ Thêm nhóm</Button>}>
    <AsyncView state={categories} empty="Chưa có nhóm nào.">
      {(rows) => <Table caption="Nhóm dịch vụ" rows={rows} columns={[
        { title: 'Nhóm', render: (row) => <><strong>{row.name}</strong><small>{row.code}</small></> },
        { title: 'Hàng đợi', render: (row) => (row.queueType ? QUEUE_LABEL[row.queueType] : 'Làm tại phòng') },
        { title: 'Thu tiền', render: (row) => (row.requiresPrepayment ? <Badge tone="blue">Thu trước</Badge> : <Badge tone="gray">Khi chốt ca</Badge>) },
        { title: 'Thứ tự', render: (row) => row.displayOrder },
        { title: 'Trạng thái', render: (row) => <Badge tone={row.active ? 'green' : 'gray'}>{row.active ? 'Đang dùng' : 'Ngừng'}</Badge> },
        { title: '', align: 'right', render: (row) => <Button className="small" onClick={() => setDialog({ category: row })}>Sửa</Button> }]} />}
    </AsyncView>
    {dialog && <CategoryDialog category={dialog.category} onClose={() => setDialog(null)} onSaved={() => { setDialog(null); onChanged(); }} />}
  </Panel>;
}

export default function ServicesPage() {
  const [tab, setTab] = useState('services');
  const [inactive, setInactive] = useState(true);
  const [categoryCode, setCategoryCode] = useState('');
  const list = useAsync(() => catalogApi.services(inactive, categoryCode), [inactive, categoryCode]);
  const categories = useAsync(() => catalogApi.serviceCategories(true), []);
  const [dialog, setDialog] = useState(null);
  const [notice, setNotice] = useState(null);
  const cats = categories.data || [];
  return <StaffLayout area="admin" title="Dịch vụ">
    <PageHead eyebrow="DANH MỤC" title="Dịch vụ" actions={tab === 'services' && <Button variant="primary" disabled={cats.length === 0} onClick={() => setDialog({ type: 'service' })}>+ Thêm dịch vụ</Button>}>Danh mục dịch vụ khám chữa bệnh, nhóm dịch vụ và bảng giá có lịch sử. Dịch vụ xét nghiệm được gắn chỉ số ở mục “Xét nghiệm & tham chiếu”.</PageHead>
    <Notice notice={notice} onClose={() => setNotice(null)} />
    <Tabs value={tab} onChange={setTab} tabs={[{ key: 'services', label: 'Dịch vụ' }, { key: 'categories', label: 'Nhóm dịch vụ' }]} />
    {tab === 'categories' ? <CategoriesTab categories={categories} onChanged={() => { categories.refresh(); list.refresh(); }} /> : <>
      <div className="ui-toolbar">
        <Select aria-label="Nhóm dịch vụ" value={categoryCode} onChange={(event) => setCategoryCode(event.target.value)}><option value="">Mọi nhóm</option>{cats.map((c) => <option key={c.id} value={c.code}>{c.name}</option>)}</Select>
        <label className="ui-check"><input type="checkbox" checked={inactive} onChange={(event) => setInactive(event.target.checked)} />Hiện cả dịch vụ đã ngừng cung cấp</label>
      </div>
      <Panel flush>
        <AsyncView state={list} empty="Chưa có dịch vụ nào.">
          {(items) => <Table caption="Dịch vụ" rows={items} columns={[
            { title: 'Dịch vụ', render: (row) => <><strong>{row.name}</strong><small>{row.code} · {row.unit}{row.estimatedMinutes ? ` · ${row.estimatedMinutes} phút` : ''}</small></> },
            { title: 'Nhóm', render: (row) => row.categoryName },
            { title: 'Đơn giá', align: 'right', render: (row) => (row.price == null ? <Badge tone="red">Chưa có giá</Badge> : <>{formatMoney(row.price)}<small>VAT {vatPercent(row.vatRate)}%</small></>) },
            { title: 'Thu tiền', render: (row) => (row.requiresPrepayment ? <Badge tone="blue">Thu trước</Badge> : <Badge tone="gray">Khi chốt ca</Badge>) },
            { title: 'Trạng thái', render: (row) => <Badge tone={row.active ? 'green' : 'gray'}>{row.active ? 'Đang cung cấp' : 'Ngừng'}</Badge> },
            { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}><Button className="small" onClick={() => setDialog({ type: 'price', service: row })}>Bảng giá</Button><Button className="small" onClick={() => setDialog({ type: 'service', service: row })}>Sửa</Button></div> }]} />}
        </AsyncView>
      </Panel>
    </>}
    {dialog?.type === 'service' && <ServiceDialog service={dialog.service} categories={cats} onClose={() => setDialog(null)} onSaved={(saved) => { setNotice({ type: 'success', text: `Đã lưu dịch vụ ${saved.name}.` }); list.refresh(); }} />}
    {dialog?.type === 'price' && <PriceDialog service={dialog.service} onClose={() => setDialog(null)} onChanged={() => { setNotice({ type: 'success', text: `Đã đặt giá mới cho ${dialog.service.name}.` }); list.refresh(); }} />}
  </StaffLayout>;
}
