import { useState } from 'react';
import StaffLayout from '../../layouts/StaffLayout';
import Modal from '../../components/common/Modal';
import { AsyncView, Badge, Button, Field, FormDialog, Input, Notice, PageHead, Pager, Panel, SearchBox, Select, Table } from '../../components/ui';
import { pharmacyApi } from '../../api/endpoints';
import { mapFieldErrors } from '../../api/client';
import useAsync from '../../hooks/useAsync';
import useDebounced from '../../hooks/useDebounced';
import { blankToNull, formatDateTime, formatMoney, formatNumber } from '../../lib/format';
import { INVENTORY_TYPE } from '../../lib/labels';

/** Ngày giờ nhập ở ô datetime-local (giờ máy) → mốc ISO (UTC) gửi backend; để trống = áp dụng ngay. */
const toIsoOrNull = (local) => (local ? new Date(local).toISOString() : null);
const vatPercent = (rate) => Math.round(Number(rate || 0) * 100);
const ingredientText = (medicine) => (medicine.ingredients || []).map((i) => (i.strengthValue != null ? `${i.ingredientName} ${formatNumber(i.strengthValue, 3)}${i.strengthUnit ? ` ${i.strengthUnit}` : ''}` : i.ingredientName)).join(' + ') || '—';

/** Thêm / sửa thông tin thuốc (v4.0): quy cách đóng gói, nhà sản xuất, thành phần hoạt chất. Giá và tồn kho KHÔNG sửa ở đây (bảng giá có lịch sử, nhập kho có nhật ký). */
function MedicineDialog({ medicine, categories, onClose, onSaved }) {
  const editing = Boolean(medicine);
  const firstCategory = categories.find((c) => c.active)?.code || '';
  const [form, setForm] = useState({
    code: medicine?.code || '', name: medicine?.name || '', categoryCode: medicine?.categoryCode || firstCategory, dosageForm: medicine?.dosageForm || '', strength: medicine?.strength || '',
    baseUnit: medicine?.baseUnit || '', packagingSpec: medicine?.packagingSpec || '', manufacturer: medicine?.manufacturer || '', countryOfOrigin: medicine?.countryOfOrigin || '',
    registrationNumber: medicine?.registrationNumber || '', storageConditions: medicine?.storageConditions || '', requiresPrescription: medicine?.requiresPrescription ?? true,
    minStockAlert: medicine?.minStockAlert ?? 10, salePrice: '', vatPercent: 5, initialStock: 0, active: medicine?.active ?? true,
  });
  const [ingredients, setIngredients] = useState((medicine?.ingredients || []).map((i) => ({ ingredientName: i.ingredientName, strengthValue: i.strengthValue ?? '', strengthUnit: i.strengthUnit || '' })));
  const [errors, setErrors] = useState({});
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.type === 'checkbox' ? event.target.checked : event.target.value });
  const setIngredient = (index, key, value) => setIngredients(ingredients.map((row, position) => (position === index ? { ...row, [key]: value } : row)));
  const submit = async () => {
    const found = {};
    if (!editing && !form.code.trim()) found.code = 'Nhập mã thuốc.';
    if (!form.name.trim()) found.name = 'Nhập tên thuốc.';
    if (!form.dosageForm.trim()) found.dosageForm = 'Nhập dạng bào chế.';
    if (!form.baseUnit.trim()) found.baseUnit = 'Nhập đơn vị bán nhỏ nhất.';
    if (!form.packagingSpec.trim()) found.packagingSpec = 'Nhập quy cách đóng gói.';
    if (!editing && (form.salePrice === '' || Number(form.salePrice) < 0)) found.salePrice = 'Giá bán không được âm.';
    if (!editing && (form.vatPercent === '' || Number(form.vatPercent) < 0 || Number(form.vatPercent) > 100)) found.vatPercent = 'VAT từ 0 đến 100%.';
    if (ingredients.some((row) => !row.ingredientName.trim())) found.ingredients = 'Mỗi dòng hoạt chất cần có tên (xóa dòng trống).';
    setErrors(found);
    if (Object.keys(found).length) throw new Error('Vui lòng kiểm tra lại các trường được đánh dấu.');
    const common = {
      categoryCode: form.categoryCode, name: form.name.trim(), dosageForm: form.dosageForm.trim(), strength: blankToNull(form.strength), baseUnit: form.baseUnit.trim(), packagingSpec: form.packagingSpec.trim(),
      manufacturer: blankToNull(form.manufacturer), countryOfOrigin: blankToNull(form.countryOfOrigin), registrationNumber: blankToNull(form.registrationNumber), storageConditions: blankToNull(form.storageConditions),
      requiresPrescription: Boolean(form.requiresPrescription), minStockAlert: Number(form.minStockAlert),
      ingredients: ingredients.map((row) => ({ ingredientName: row.ingredientName.trim(), strengthValue: row.strengthValue === '' ? null : Number(row.strengthValue), strengthUnit: blankToNull(row.strengthUnit) })),
    };
    try {
      onSaved(editing ? await pharmacyApi.updateMedicine(medicine.id, { ...common, active: form.active })
        : await pharmacyApi.createMedicine({ ...common, code: form.code.trim().toUpperCase(), salePrice: Number(form.salePrice), vatRate: Number(form.vatPercent) / 100, initialStock: Number(form.initialStock) }));
    } catch (problem) { setErrors(mapFieldErrors(problem)); throw problem; }
  };
  return <FormDialog title={editing ? `Sửa thuốc ${medicine.name}` : 'Thêm thuốc'} id="medicine-form-title" onClose={onClose} onSubmit={submit} submitLabel={editing ? 'Lưu thay đổi' : 'Thêm thuốc'} wide>
    <div className="ui-grid">
      <Field label="Mã thuốc *" error={errors.code} hint={editing ? 'Mã không đổi được.' : undefined}><Input value={form.code} onChange={set('code')} disabled={editing} maxLength={50} /></Field>
      <Field label="Tên thuốc *" error={errors.name}><Input value={form.name} onChange={set('name')} maxLength={150} /></Field>
      <Field label="Nhóm thuốc *" error={errors.categoryCode}><Select value={form.categoryCode} onChange={set('categoryCode')}>{categories.filter((c) => c.active || c.code === form.categoryCode).map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}</Select></Field>
      <Field label="Dạng bào chế *" error={errors.dosageForm}><Input value={form.dosageForm} onChange={set('dosageForm')} maxLength={50} placeholder="Viên nén, lọ tiêm đông khô…" /></Field>
      <Field label="Hàm lượng" error={errors.strength}><Input value={form.strength} onChange={set('strength')} maxLength={50} placeholder="250mg, 1 liều…" /></Field>
      <Field label="Đơn vị bán nhỏ nhất *" error={errors.baseUnit}><Input value={form.baseUnit} onChange={set('baseUnit')} maxLength={30} placeholder="viên, lọ, ống…" /></Field>
      <Field label="Quy cách đóng gói *" error={errors.packagingSpec} wide><Input value={form.packagingSpec} onChange={set('packagingSpec')} maxLength={150} placeholder="Hộp 10 vỉ x 10 viên" /></Field>
      <Field label="Nhà sản xuất" error={errors.manufacturer}><Input value={form.manufacturer} onChange={set('manufacturer')} maxLength={150} /></Field>
      <Field label="Nước sản xuất" error={errors.countryOfOrigin}><Input value={form.countryOfOrigin} onChange={set('countryOfOrigin')} maxLength={100} /></Field>
      <Field label="Số đăng ký lưu hành" error={errors.registrationNumber}><Input value={form.registrationNumber} onChange={set('registrationNumber')} maxLength={50} /></Field>
      <Field label="Điều kiện bảo quản" error={errors.storageConditions}><Input value={form.storageConditions} onChange={set('storageConditions')} maxLength={255} placeholder="Bảo quản lạnh 2 - 8 độ C" /></Field>
      <Field label="Ngưỡng cảnh báo tồn kho" error={errors.minStockAlert}><Input type="number" min="0" value={form.minStockAlert} onChange={set('minStockAlert')} /></Field>
      <label className="ui-check"><input type="checkbox" checked={form.requiresPrescription} onChange={set('requiresPrescription')} />Thuốc kê đơn</label>
      {editing && <label className="ui-check"><input type="checkbox" checked={form.active} onChange={set('active')} />Đang kinh doanh</label>}
      {!editing && <Field label="Giá bán (VND) *" error={errors.salePrice} hint="Dòng giá đầu tiên của lịch sử giá."><Input type="number" min="0" step="500" value={form.salePrice} onChange={set('salePrice')} /></Field>}
      {!editing && <Field label="VAT (%) *" error={errors.vatPercent}><Input type="number" min="0" max="100" step="1" value={form.vatPercent} onChange={set('vatPercent')} /></Field>}
      {!editing && <Field label="Tồn kho ban đầu" error={errors.initialStock} hint="Ghi vào nhật ký kho như một lần nhập."><Input type="number" min="0" value={form.initialStock} onChange={set('initialStock')} /></Field>}
    </div>
    <h3 className="ui-subhead" style={{ marginTop: 14 }}>Thành phần hoạt chất</h3>
    {errors.ingredients && <div className="ui-alert error" role="alert"><span>{errors.ingredients}</span></div>}
    <div className="ui-stack" style={{ gap: 8 }}>
      {ingredients.map((row, index) => <div key={index} style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 2fr) minmax(0, 1fr) minmax(0, 1fr) auto', gap: 8, alignItems: 'center' }}>
        <Input aria-label={`Hoạt chất ${index + 1}`} placeholder="Tên hoạt chất" value={row.ingredientName} maxLength={150} onChange={(event) => setIngredient(index, 'ingredientName', event.target.value)} />
        <Input aria-label={`Hàm lượng ${index + 1}`} type="number" min="0" step="any" placeholder="Hàm lượng" value={row.strengthValue} onChange={(event) => setIngredient(index, 'strengthValue', event.target.value)} />
        <Input aria-label={`Đơn vị hàm lượng ${index + 1}`} placeholder="mg, ml, IU…" value={row.strengthUnit} maxLength={20} onChange={(event) => setIngredient(index, 'strengthUnit', event.target.value)} />
        <Button className="small danger" aria-label={`Bỏ hoạt chất ${index + 1}`} onClick={() => setIngredients(ingredients.filter((_, position) => position !== index))}>×</Button>
      </div>)}
      <div><Button className="small" onClick={() => setIngredients([...ingredients, { ingredientName: '', strengthValue: '', strengthUnit: '' }])}>+ Thêm hoạt chất</Button></div>
    </div>
    {editing && <p className="ui-muted" style={{ fontSize: 12 }}>Giá bán đổi ở nút “Bảng giá”, tồn kho đổi bằng Nhập kho / Điều chỉnh để có lịch sử và nhật ký.</p>}
  </FormDialog>;
}

/** Lịch sử giá bán của một thuốc + đặt giá mới (đóng dòng đang mở, thêm dòng mới; dòng cũ không bao giờ bị sửa). */
function PriceDialog({ medicine, onClose, onChanged }) {
  const history = useAsync(() => pharmacyApi.medicinePrices(medicine.id), [medicine.id]);
  const [form, setForm] = useState({ salePrice: '', vatPercent: medicine.vatRate == null ? 5 : vatPercent(medicine.vatRate), effectiveFrom: '', reason: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });
  const submit = async (event) => {
    event.preventDefault();
    if (busy) return;
    if (form.salePrice === '' || Number(form.salePrice) < 0) { setError('Nhập giá bán hợp lệ.'); return; }
    if (form.vatPercent === '' || Number(form.vatPercent) < 0 || Number(form.vatPercent) > 100) { setError('VAT từ 0 đến 100%.'); return; }
    setBusy(true); setError('');
    try {
      await pharmacyApi.changeMedicinePrice(medicine.id, { salePrice: Number(form.salePrice), vatRate: Number(form.vatPercent) / 100, effectiveFrom: toIsoOrNull(form.effectiveFrom), reason: blankToNull(form.reason) });
      setForm({ ...form, salePrice: '', effectiveFrom: '', reason: '' });
      history.refresh(); onChanged();
    } catch (problem) { setError(problem.message || 'Không đặt được giá mới.'); } finally { setBusy(false); }
  };
  return <Modal title={`Bảng giá — ${medicine.name}`} id="medicine-price-title" onClose={onClose} busy={busy} className="ui-dialog-wide">
    <div className="ui-form-body">
      <AsyncView state={history} empty="Chưa có dòng giá nào.">
        {(rows) => <Table caption="Lịch sử giá" rows={rows} columns={[
          { title: 'Giá bán', align: 'right', render: (row) => <>{formatMoney(row.salePrice)}<small>VAT {vatPercent(row.vatRate)}%</small></> },
          { title: 'Hiệu lực từ', render: (row) => formatDateTime(row.effectiveFrom) },
          { title: 'Đến', render: (row) => (row.effectiveTo ? formatDateTime(row.effectiveTo) : '—') },
          { title: 'Lý do', render: (row) => row.changeReason || '—' },
          { title: '', render: (row) => (row.current ? <Badge tone="green">Đang áp dụng</Badge> : (!row.effectiveTo ? <Badge tone="blue">Đã lên lịch</Badge> : <Badge tone="gray">Đã đóng</Badge>)) }]} />}
      </AsyncView>
      <form onSubmit={submit} noValidate>
        <h3 className="ui-subhead" style={{ marginTop: 16 }}>Đặt giá mới</h3>
        <div className="ui-grid">
          <Field label="Giá bán mới (VND) *"><Input type="number" min="0" step="500" value={form.salePrice} onChange={set('salePrice')} /></Field>
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

function StockDialog({ medicine, mode, onClose, onSaved }) {
  const [amount, setAmount] = useState('');
  const [unitCost, setUnitCost] = useState('');
  const [notes, setNotes] = useState('');
  const submit = async () => {
    const value = Number(amount);
    if (!Number.isInteger(value) || value === 0 || (mode === 'import' && value < 0)) throw new Error(mode === 'import' ? 'Số lượng nhập phải là số nguyên dương.' : 'Nhập số nguyên khác 0 (âm = hao hụt).');
    if (mode === 'adjust' && !notes.trim()) throw new Error('Điều chỉnh tồn kho bắt buộc có lý do.');
    if (mode === 'import' && unitCost !== '' && Number(unitCost) < 0) throw new Error('Giá nhập không được âm.');
    onSaved(mode === 'import' ? await pharmacyApi.importStock(medicine.id, { quantity: value, unitCost: unitCost === '' ? null : Number(unitCost), notes: blankToNull(notes) })
      : await pharmacyApi.adjustStock(medicine.id, { delta: value, reason: notes.trim() }));
  };
  return <FormDialog title={`${mode === 'import' ? 'Nhập kho' : 'Điều chỉnh tồn kho'} — ${medicine.name}`} onClose={onClose} onSubmit={submit} submitLabel={mode === 'import' ? 'Nhập kho' : 'Điều chỉnh'}>
    <p className="ui-confirm-text">Tồn hiện tại: <strong>{medicine.stockQuantity} {medicine.baseUnit}</strong>.{mode === 'adjust' && ' Nhập số âm cho hao hụt; kho không được âm.'}</p>
    <Field label={mode === 'import' ? 'Số lượng nhập *' : 'Chênh lệch (+/−) *'}><Input type="number" step="1" value={amount} onChange={(event) => setAmount(event.target.value)} autoFocus /></Field>
    {mode === 'import' && <Field label={`Giá nhập một ${medicine.baseUnit} (VND)`} hint="Không bắt buộc; ghi vào nhật ký kho."><Input type="number" min="0" step="100" value={unitCost} onChange={(event) => setUnitCost(event.target.value)} /></Field>}
    <Field label={mode === 'import' ? 'Ghi chú (nhà cung cấp, số lô…)' : 'Lý do *'}><Input value={notes} onChange={(event) => setNotes(event.target.value)} maxLength={255} /></Field>
  </FormDialog>;
}

function TransactionsDialog({ medicine, onClose }) {
  const [page, setPage] = useState(0);
  const list = useAsync(() => pharmacyApi.transactions(medicine.id, { page, size: 10 }), [medicine.id, page]);
  return <FormDialog title={`Nhật ký kho — ${medicine.name}`} onClose={onClose} onSubmit={async () => {}} submitLabel="Đóng" wide>
    <AsyncView state={list} isEmpty={(data) => data.items.length === 0} empty="Chưa có giao dịch kho nào.">{(data) => <>
      <Table caption="Nhật ký kho" rows={data.items} columns={[
        { title: 'Thời gian', render: (row) => formatDateTime(row.createdAt) }, { title: 'Loại', render: (row) => INVENTORY_TYPE[row.transactionType] },
        { title: 'Số lượng', align: 'right', render: (row) => <strong style={{ color: row.quantity < 0 ? 'var(--ui-danger, #c0392b)' : undefined }}>{row.quantity > 0 ? `+${row.quantity}` : row.quantity}</strong> },
        { title: 'Giá nhập', align: 'right', render: (row) => (row.unitCost != null ? formatMoney(row.unitCost) : '—') },
        { title: 'Hóa đơn thuốc', render: (row) => (row.medicineInvoiceId ? `#${row.medicineInvoiceId}` : '—') }, { title: 'Ghi chú', render: (row) => row.notes || '—' }]} />
      <Pager page={data} onChange={setPage} /></>}</AsyncView>
  </FormDialog>;
}

export default function MedicinesPage() {
  const [keyword, setKeyword] = useState('');
  const [active, setActive] = useState('');
  const [categoryCode, setCategoryCode] = useState('');
  const [lowStock, setLowStock] = useState(new URLSearchParams(window.location.search).get('stock') === 'low');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState(null);
  const [notice, setNotice] = useState(null);
  const search = useDebounced(keyword);
  const categories = useAsync(() => pharmacyApi.categories({ includeInactive: true }), []);
  const list = useAsync(() => pharmacyApi.medicines({ keyword: search.trim() || undefined, categoryCode: categoryCode || undefined, active: active === '' ? undefined : active === 'true', lowStock, page, size: 15 }), [search, categoryCode, active, lowStock, page]);
  const done = (text) => (saved) => { setDialog(null); setNotice({ type: 'success', text: typeof text === 'function' ? text(saved) : text }); list.refresh(); };
  const cats = categories.data || [];

  return <StaffLayout area="admin" title="Thuốc & kho">
    <PageHead eyebrow="KHO THUỐC" title="Thuốc & kho" actions={<Button variant="primary" disabled={cats.length === 0} onClick={() => setDialog({ type: 'edit' })}>+ Thêm thuốc</Button>}>Quản lý danh mục thuốc (quy cách, thành phần), bảng giá có lịch sử và tồn kho. Mọi lần nhập, điều chỉnh, bán thuốc đều được ghi nhật ký (số lượng có dấu: + nhập, − xuất); tồn kho chạm ngưỡng sẽ báo cho quản trị viên.</PageHead>
    <Notice notice={notice} onClose={() => setNotice(null)} />
    <div className="ui-toolbar">
      <SearchBox value={keyword} onChange={(value) => { setKeyword(value); setPage(0); }} placeholder="Mã, tên hoặc hoạt chất" label="Tìm thuốc" />
      <Select aria-label="Nhóm thuốc" value={categoryCode} onChange={(event) => { setCategoryCode(event.target.value); setPage(0); }}><option value="">Mọi nhóm</option>{cats.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}</Select>
      <Select aria-label="Trạng thái kinh doanh" value={active} onChange={(event) => { setActive(event.target.value); setPage(0); }}><option value="">Mọi trạng thái</option><option value="true">Đang kinh doanh</option><option value="false">Ngừng kinh doanh</option></Select>
      <label className="ui-check"><input type="checkbox" checked={lowStock} onChange={(event) => { setLowStock(event.target.checked); setPage(0); }} />Chỉ thuốc sắp hết</label>
    </div>
    <Panel flush>
      <AsyncView state={list} isEmpty={(data) => data.items.length === 0} empty="Không có thuốc phù hợp.">
        {(data) => <>
          <Table caption="Thuốc" rows={data.items} columns={[
            { title: 'Thuốc', render: (row) => <><strong>{row.name}</strong><small>{row.code} · {row.categoryName}{row.requiresPrescription ? ' · kê đơn' : ''}</small><small>{ingredientText(row)}</small></> },
            { title: 'Dạng / quy cách', render: (row) => <>{row.dosageForm}{row.strength ? ` ${row.strength}` : ''} ({row.baseUnit})<small>{row.packagingSpec}</small></> },
            { title: 'Giá bán', align: 'right', render: (row) => (row.salePrice == null ? <span className="ui-muted">Chưa có giá</span> : <>{formatMoney(row.salePrice)}<small>VAT {vatPercent(row.vatRate)}%</small></>) },
            { title: 'Tồn kho', align: 'right', render: (row) => <>{row.stockQuantity}<small>Ngưỡng {row.minStockAlert}</small></> },
            { title: 'Trạng thái', render: (row) => <>{row.active ? <Badge tone="green">Đang bán</Badge> : <Badge tone="gray">Ngừng</Badge>} {row.lowStock && <Badge tone="amber">Sắp hết</Badge>}</> },
            { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
              <Button className="small primary" onClick={() => setDialog({ type: 'import', medicine: row })}>Nhập kho</Button>
              <Button className="small" onClick={() => setDialog({ type: 'adjust', medicine: row })}>Điều chỉnh</Button>
              <Button className="small" onClick={() => setDialog({ type: 'price', medicine: row })}>Bảng giá</Button>
              <Button className="small" onClick={() => setDialog({ type: 'edit', medicine: row })}>Sửa</Button>
              <Button className="small" onClick={() => setDialog({ type: 'log', medicine: row })}>Nhật ký</Button></div> }]} />
          <Pager page={data} onChange={setPage} />
        </>}
      </AsyncView>
    </Panel>
    {dialog?.type === 'edit' && <MedicineDialog medicine={dialog.medicine} categories={cats} onClose={() => setDialog(null)} onSaved={done((saved) => `Đã lưu thuốc ${saved.name}.`)} />}
    {dialog?.type === 'price' && <PriceDialog medicine={dialog.medicine} onClose={() => setDialog(null)} onChanged={() => list.refresh()} />}
    {dialog?.type === 'import' && <StockDialog mode="import" medicine={dialog.medicine} onClose={() => setDialog(null)} onSaved={done((saved) => `Đã nhập kho; tồn ${saved.name} hiện là ${saved.stockQuantity}.`)} />}
    {dialog?.type === 'adjust' && <StockDialog mode="adjust" medicine={dialog.medicine} onClose={() => setDialog(null)} onSaved={done((saved) => `Đã điều chỉnh; tồn ${saved.name} hiện là ${saved.stockQuantity}.`)} />}
    {dialog?.type === 'log' && <TransactionsDialog medicine={dialog.medicine} onClose={() => setDialog(null)} />}
  </StaffLayout>;
}
