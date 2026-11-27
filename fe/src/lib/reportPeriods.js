// Gom các dòng doanh thu theo NGÀY (GET /api/v1/reports/revenue/daily) thành tuần / tháng / quý để hiển thị.
// Ngày là chuỗi yyyy-MM-dd (ngày nghiệp vụ theo giờ phòng khám): tính bằng số học trên chuỗi, không qua Date địa phương để khỏi lệch múi giờ.
const pad = (n) => String(n).padStart(2, '0');
const dayNumber = (iso) => { const [y, m, d] = iso.split('-').map(Number); return Date.UTC(y, m - 1, d) / 86400000; };
const isoOf = (dayNum) => { const d = new Date(dayNum * 86400000); return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`; };

/** Khóa sắp xếp + nhãn hiển thị của kỳ chứa {@code iso}. Tuần bắt đầu từ thứ Hai. */
export function periodOf(iso, groupBy) {
  const [year, month, day] = iso.split('-');
  if (groupBy === 'MONTH') return { key: `${year}-${month}`, label: `${month}/${year}` };
  if (groupBy === 'QUARTER') { const q = Math.floor((Number(month) - 1) / 3) + 1; return { key: `${year}-Q${q}`, label: `Quý ${q}/${year}` }; }
  if (groupBy === 'WEEK') {
    const n = dayNumber(iso);
    const monday = n - ((n + 3) % 7);   // 01/01/1970 là thứ Năm: (n + 3) % 7 = 0 vào thứ Hai
    const start = isoOf(monday);
    const [sy, sm, sd] = start.split('-');
    return { key: start, label: `Tuần ${sd}/${sm}/${sy}` };
  }
  return { key: iso, label: `${day}/${month}/${year}` };
}

/**
 * items: [{ businessDate, revenueType, lineCount, amountBeforeVat, vatAmount, discountAmount, netAmount }]
 * -> [{ key, label, serviceNet, medicineNet, netAmount, lineCount, amountBeforeVat, vatAmount, discountAmount }] theo thứ tự thời gian.
 */
export function groupRevenue(items, groupBy = 'DAY') {
  const periods = new Map();
  for (const item of items) {
    const { key, label } = periodOf(item.businessDate, groupBy);
    const row = periods.get(key) || { key, label, serviceNet: 0, medicineNet: 0, netAmount: 0, lineCount: 0, amountBeforeVat: 0, vatAmount: 0, discountAmount: 0 };
    const net = Number(item.netAmount);
    if (item.revenueType === 'MEDICINE') row.medicineNet += net; else row.serviceNet += net;
    row.netAmount += net;
    row.lineCount += Number(item.lineCount);
    row.amountBeforeVat += Number(item.amountBeforeVat);
    row.vatAmount += Number(item.vatAmount);
    row.discountAmount += Number(item.discountAmount);
    periods.set(key, row);
  }
  return [...periods.values()].sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
}
