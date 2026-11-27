// Id là giá trị "đục" (opaque): số ở lược đồ v3, UUID dạng chuỗi từ v4. Giao diện KHÔNG ép id sang số (Number/parseInt/+id)
// và KHÔNG so sánh id bằng `===` khi hai bên có thể khác kiểu (id từ useParams / <select> / khóa object luôn là chuỗi).
// Gửi lên API: dùng nguyên giá trị (backend v3 nhận chuỗi số cho trường Long; v4 nhận chuỗi UUID).

/** Hai id có cùng giá trị không (bất kể số hay chuỗi). Id rỗng / null / undefined không bao giờ bằng gì cả. */
export const sameId = (a, b) => a !== null && a !== undefined && a !== '' && b !== null && b !== undefined && b !== '' && String(a) === String(b);

/** Chuỗi rỗng (ô chọn chưa chọn) → null; còn lại giữ nguyên, không ép kiểu. */
export const idOrNull = (value) => (value === '' || value === null || value === undefined ? null : value);
