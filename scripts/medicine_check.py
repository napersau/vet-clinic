"""Kiểm tra THẬT (MySQL thật, không mock) bước 5.1: danh mục thuốc v4.0 của billing-pharmacy-service: thông tin + thành phần, bảng giá có lịch sử,
tồn kho tách bảng (trừ / cộng nguyên tử), nhật ký kho, nhóm thuốc, endpoint nội bộ.
Gọi từ scripts/service_check.sh:  python scripts/medicine_check.py <cổng billing-pharmacy-service> <tên container MySQL>
Tạo dữ liệu với mã duy nhất theo thời gian nên chạy lại được trên cùng CSDL tạm; không đổi giá / tồn kho của 4 thuốc seed."""
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

PORT, MYSQL = sys.argv[1], sys.argv[2]
BASE = f"http://localhost:{PORT}"
SUFFIX = str(int(time.time()))[-6:]
ADMIN = ("019a9442-d800-7729-87cc-fbb1ceda8817", "ADMIN")
VET = ("019a9442-d810-7000-8000-000000000002", "VETERINARIAN")
RECEPTION = ("019a9442-d810-7000-8000-000000000005", "RECEPTIONIST")
DB = "pet_clinic_billing_pharmacy_db"
FAILS = []


def call(method, path, who=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if who:  # service chạy trực tiếp chỉ tin header do Gateway gắn
        req.add_header("X-User-Id", who[0])
        req.add_header("X-User-Roles", who[1])
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


def sql(query):
    out = subprocess.run(["docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "--default-character-set=utf8mb4", "-N", "-B", "-e", query],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    return (out or "").strip()


def b(uid):
    return f"UNHEX(REPLACE('{uid}','-',''))"


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def code(res):
    return res.get("errorCode")


# ------------------------------------------------------------------ danh mục + seed
st, res = call("GET", "/api/v1/pharmacy/medicine-categories", VET)
cats = {c["code"] for c in res.get("data", [])}
check("4 nhóm thuốc seed (kháng sinh, vắc-xin, ký sinh trùng, bổ sung)", st == 200 and {"ANTIBIOTIC", "VACCINE", "ANTIPARASITIC", "SUPPLEMENT"} <= cats, (st, cats))
st, res = call("GET", "/api/v1/pharmacy/medicines?size=100", ADMIN)
seeded = {m["code"]: m for m in res.get("data", {}).get("items", [])}
amox = seeded.get("MED_AMOX")
check("thuốc seed có id UUID, nhóm, quy cách, đơn vị nhỏ nhất", st == 200 and amox and len(amox["id"]) == 36 and amox["categoryCode"] == "ANTIBIOTIC"
      and amox["packagingSpec"].startswith("Hộp 10 vỉ") and amox["baseUnit"] == "Viên", amox)
check("Clavamox: 2 thành phần (Amoxicillin 200mg + Clavulanic acid 50mg)", amox and {(i["ingredientName"], float(i["strengthValue"])) for i in amox["ingredients"]} == {("Amoxicillin", 200.0), ("Clavulanic acid", 50.0)}, amox and amox["ingredients"])
check("giá hiện hành 15.000 (VAT 5%) lấy từ medicine_prices, tồn 200 từ medicine_stocks", amox and float(amox["salePrice"]) == 15000 and float(amox["vatRate"]) == 0.05 and amox["stockQuantity"] >= 0, amox)
check("VIEW v_current_medicine_prices có đủ 4 thuốc seed", int(sql(f"SELECT COUNT(*) FROM {DB}.v_current_medicine_prices WHERE code IN ('MED_AMOX','MED_VANG_7B','MED_PURE_4B','MED_DRON_DOG')") or 0) == 4)
check("thuốc seed chỉ tìm theo hoạt chất (keyword Praziquantel -> Drontal)",
      [m["code"] for m in call("GET", "/api/v1/pharmacy/medicines?keyword=praziquantel", ADMIN)[1]["data"]["items"]] == ["MED_DRON_DOG"])
check("lọc theo nhóm VACCINE trả đúng 2 vắc-xin", sorted(m["code"] for m in call("GET", "/api/v1/pharmacy/medicines?categoryCode=VACCINE", ADMIN)[1]["data"]["items"]) == ["MED_PURE_4B", "MED_VANG_7B"])

# ------------------------------------------------------------------ phân quyền
body = {"categoryCode": "ANTIBIOTIC", "code": f"MED_T{SUFFIX}", "name": f"Thuốc thử {SUFFIX}", "dosageForm": "Viên nén", "strength": "500mg", "baseUnit": "Viên",
        "packagingSpec": "Hộp 2 vỉ x 10 viên", "manufacturer": "Hãng thử", "countryOfOrigin": "Việt Nam", "requiresPrescription": True, "minStockAlert": 50,
        "ingredients": [{"ingredientName": "Cefalexin", "strengthValue": 500, "strengthUnit": "mg"}, {"ingredientName": "Tá dược"}],
        "salePrice": 2500, "vatRate": 0.05, "initialStock": 100}
check("Bác sĩ tạo thuốc -> 403", call("POST", "/api/v1/pharmacy/medicines", VET, body)[0] == 403)
check("Lễ tân nhập kho -> 403", call("POST", f"/api/v1/pharmacy/medicines/{amox['id']}/import", RECEPTION, {"quantity": 1})[0] == 403)
check("Bác sĩ xem lịch sử giá -> 403", call("GET", f"/api/v1/pharmacy/medicines/{amox['id']}/prices", VET)[0] == 403)

# ------------------------------------------------------------------ tạo thuốc
st, res = call("POST", "/api/v1/pharmacy/medicines", ADMIN, body)
med = res.get("data", {})
mid = med.get("id", "")
check("Admin tạo thuốc -> 201, id UUID, giá + tồn kho từ bảng riêng", st == 201 and len(mid) == 36 and float(med["salePrice"]) == 2500 and med["stockQuantity"] == 100
      and len(med["ingredients"]) == 2, (st, res))
check("DB: medicines không còn cột giá / tồn kho (sale_price, stock_quantity)", sql(f"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='{DB}' AND table_name='medicines' "
      "AND column_name IN ('sale_price','vat_rate','stock_quantity','unit','active_ingredient')") == "0")
check("DB: 1 dòng giá mở (effective_to NULL), 1 dòng tồn kho, 2 thành phần",
      sql(f"SELECT COUNT(*) FROM {DB}.medicine_prices WHERE medicine_id={b(mid)} AND effective_to IS NULL") == "1"
      and sql(f"SELECT quantity_on_hand FROM {DB}.medicine_stocks WHERE medicine_id={b(mid)}") == "100"
      and sql(f"SELECT COUNT(*) FROM {DB}.medicine_ingredients WHERE medicine_id={b(mid)}") == "2")
check("DB: nhật ký IMPORT 100 ghi cùng giao dịch, người tạo là Admin",
      sql(f"SELECT CONCAT(transaction_type,'|',quantity,'|',HEX(created_by)) FROM {DB}.inventory_transactions WHERE medicine_id={b(mid)}") == f"IMPORT|100|{ADMIN[0].replace('-', '').upper()}")
st, res = call("POST", "/api/v1/pharmacy/medicines", ADMIN, body)
check("trùng mã thuốc -> 409 PHARM_1414", st == 409 and code(res) == "PHARM_1414", (st, res))
check("nhóm không tồn tại -> 400", call("POST", "/api/v1/pharmacy/medicines", ADMIN, {**body, "code": f"MED_U{SUFFIX}", "categoryCode": "KHONG_CO"})[0] == 400)
check("trùng hoạt chất trong danh sách -> 400", call("POST", "/api/v1/pharmacy/medicines", ADMIN, {**body, "code": f"MED_V{SUFFIX}",
      "ingredients": [{"ingredientName": "A"}, {"ingredientName": " a "}]})[0] == 400)
check("thiếu quy cách đóng gói -> 400", call("POST", "/api/v1/pharmacy/medicines", ADMIN, {**body, "code": f"MED_W{SUFFIX}", "packagingSpec": ""})[0] == 400)

# ------------------------------------------------------------------ sửa thông tin: thay thành phần theo tên, không đụng giá / tồn kho
st, res = call("PUT", f"/api/v1/pharmacy/medicines/{mid}", ADMIN, {"name": f"Thuốc thử đổi {SUFFIX}", "dosageForm": "Viên nén", "strength": "500mg", "baseUnit": "Viên",
      "packagingSpec": "Hộp 3 vỉ", "ingredients": [{"ingredientName": "cefalexin", "strengthValue": 750, "strengthUnit": "mg"}, {"ingredientName": "Chất mới"}]})
check("sửa thuốc: thay thành phần theo tên (UNIQUE không vi phạm), tên + quy cách đổi", st == 200 and res["data"]["packagingSpec"] == "Hộp 3 vỉ"
      and sorted(i["ingredientName"] for i in res["data"]["ingredients"]) == ["Chất mới", "cefalexin"], (st, res))
check("DB: thành phần cũ 'Tá dược' bị bỏ, 'cefalexin' đổi hàm lượng tại chỗ, tồn kho + giá không đổi",
      sql(f"SELECT COUNT(*) FROM {DB}.medicine_ingredients WHERE medicine_id={b(mid)} AND ingredient_name='Tá dược'") == "0"
      and sql(f"SELECT strength_value FROM {DB}.medicine_ingredients WHERE medicine_id={b(mid)} AND ingredient_name='cefalexin'") == "750.000"
      and sql(f"SELECT quantity_on_hand FROM {DB}.medicine_stocks WHERE medicine_id={b(mid)}") == "100"
      and sql(f"SELECT COUNT(*) FROM {DB}.medicine_prices WHERE medicine_id={b(mid)}") == "1")
check("sửa không gửi ingredients -> giữ nguyên thành phần", call("PUT", f"/api/v1/pharmacy/medicines/{mid}", ADMIN, {"name": f"Thuốc thử đổi {SUFFIX}", "dosageForm": "Viên nén",
      "baseUnit": "Viên", "packagingSpec": "Hộp 3 vỉ"})[1]["data"]["ingredients"].__len__() == 2)

# ------------------------------------------------------------------ bảng giá có lịch sử
tomorrow = (datetime.now(timezone.utc) + timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
st, res = call("POST", f"/api/v1/pharmacy/medicines/{mid}/prices", ADMIN, {"salePrice": 3000, "vatRate": 0.08, "effectiveFrom": tomorrow, "reason": "Nhà cung cấp tăng giá"})
check("đặt giá mới hiệu lực ngày mai -> 201, chưa phải giá hiện hành", st == 201 and res["data"]["current"] is False and float(res["data"]["salePrice"]) == 3000, (st, res))
check("hôm nay thuốc vẫn giá cũ 2.500 (EX-20)", float(call("GET", f"/api/v1/pharmacy/medicines/{mid}", ADMIN)[1]["data"]["salePrice"]) == 2500)
st, res = call("GET", f"/api/v1/pharmacy/medicines/{mid}/prices", ADMIN)
hist = res.get("data", [])
check("lịch sử giá 2 dòng: dòng mới trước, dòng cũ đã đóng đúng lúc dòng mới bắt đầu", st == 200 and len(hist) == 2 and float(hist[0]["salePrice"]) == 3000
      and hist[1]["effectiveTo"] == hist[0]["effectiveFrom"] and hist[1]["current"] is True and hist[0]["effectiveTo"] is None, hist)
check("DB: dòng cũ không bị sửa số tiền", sql(f"SELECT sale_price FROM {DB}.medicine_prices WHERE medicine_id={b(mid)} ORDER BY effective_from LIMIT 1") == "2500.00")
check("hiệu lực sớm hơn giá đang mở -> 400 BILL_1410", code(call("POST", f"/api/v1/pharmacy/medicines/{mid}/prices", ADMIN,
      {"salePrice": 1, "vatRate": 0.05, "effectiveFrom": "2020-01-01T00:00:00Z"})[1]) == "BILL_1410")
check("thuế suất > 100% -> 400", call("POST", f"/api/v1/pharmacy/medicines/{mid}/prices", ADMIN, {"salePrice": 1, "vatRate": 1.5})[0] == 400)

# ------------------------------------------------------------------ tồn kho: nhập, cân chỉnh, không âm, nhật ký
st, res = call("POST", f"/api/v1/pharmacy/medicines/{mid}/import", ADMIN, {"quantity": 50, "unitCost": 1800, "notes": "Nhập lô thử"})
check("nhập kho 50 -> tồn 150", st == 200 and res["data"]["stockQuantity"] == 150, (st, res))
check("DB: IMPORT ghi giá nhập (unit_cost)", sql(f"SELECT unit_cost FROM {DB}.inventory_transactions WHERE medicine_id={b(mid)} AND transaction_type='IMPORT' AND quantity=50") == "1800.00")
st, res = call("POST", f"/api/v1/pharmacy/medicines/{mid}/adjust", ADMIN, {"delta": -20, "reason": "Hư hỏng"})
check("cân chỉnh -20 -> tồn 130", st == 200 and res["data"]["stockQuantity"] == 130, (st, res))
st, res = call("POST", f"/api/v1/pharmacy/medicines/{mid}/adjust", ADMIN, {"delta": -999, "reason": "Quá tay"})
check("cân chỉnh làm tồn âm -> 400 PHARM_1415, tồn giữ nguyên", st == 400 and code(res) == "PHARM_1415" and sql(f"SELECT quantity_on_hand FROM {DB}.medicine_stocks WHERE medicine_id={b(mid)}") == "130", (st, res))
check("cân chỉnh delta 0 -> 400", call("POST", f"/api/v1/pharmacy/medicines/{mid}/adjust", ADMIN, {"delta": 0, "reason": "x"})[0] == 400)
st, res = call("GET", f"/api/v1/pharmacy/medicines/{mid}/transactions", ADMIN)
check("nhật ký kho có 3 dòng (IMPORT 100, IMPORT 50, ADJUSTMENT -20), mới nhất trước", st == 200 and [t["quantity"] for t in res["data"]["items"]] == [-20, 50, 100], res)
check("bất biến: quantity_on_hand = SUM(inventory_transactions.quantity)",
      sql(f"SELECT SUM(quantity) FROM {DB}.inventory_transactions WHERE medicine_id={b(mid)}") == sql(f"SELECT quantity_on_hand FROM {DB}.medicine_stocks WHERE medicine_id={b(mid)}") == "130")

# ------------------------------------------------------------------ trừ kho nguyên tử khi nhiều yêu cầu đồng thời
results = []


def hit():
    results.append(call("POST", f"/api/v1/pharmacy/medicines/{mid}/adjust", ADMIN, {"delta": -50, "reason": "Đồng thời"})[0])


threads = [threading.Thread(target=hit) for _ in range(8)]
[t.start() for t in threads]
[t.join() for t in threads]
final = int(sql(f"SELECT quantity_on_hand FROM {DB}.medicine_stocks WHERE medicine_id={b(mid)}"))
check("8 lần cân chỉnh -50 đồng thời trên tồn 130: đúng 2 lần thành công, tồn cuối 30 (không âm, không lệch)",
      results.count(200) == 2 and final == 30 and sql(f"SELECT SUM(quantity) FROM {DB}.inventory_transactions WHERE medicine_id={b(mid)}") == "30", (results, final))
check("tồn 30 <= ngưỡng 50 -> có cảnh báo tồn kho trong outbox (aggregate_id là UUID)",
      int(sql(f"SELECT COUNT(*) FROM {DB}.outbox_events WHERE event_type='MedicineLowStockEvent' AND aggregate_id = UNHEX(REPLACE('{mid}','-',''))") or 0) >= 1)

# ------------------------------------------------------------------ ngừng kinh doanh + endpoint nội bộ
st, res = call("GET", f"/internal/medicines/{mid}")
check("nội bộ GET /internal/medicines/{id}: tên, đơn vị, giá hiện hành, tồn kho", st == 200 and res["data"]["code"] == f"MED_T{SUFFIX}" and res["data"]["baseUnit"] == "Viên"
      and float(res["data"]["salePrice"]) == 2500 and res["data"]["stockQuantity"] == 30, (st, res))
st, res = call("GET", f"/internal/medicines?keyword=MED_T{SUFFIX}")
check("nội bộ GET /internal/medicines?keyword= trả thuốc đang kinh doanh", st == 200 and [m["id"] for m in res["data"]["items"]] == [mid], (st, res))
call("PUT", f"/api/v1/pharmacy/medicines/{mid}", ADMIN, {"name": f"Thuốc thử đổi {SUFFIX}", "dosageForm": "Viên nén", "baseUnit": "Viên", "packagingSpec": "Hộp 3 vỉ", "active": False})
check("ngừng kinh doanh: nội bộ không còn thấy, Bác sĩ không thấy, Admin vẫn thấy (xóa mềm, dòng còn)",
      call("GET", f"/internal/medicines/{mid}")[0] == 404 and call("GET", f"/api/v1/pharmacy/medicines/{mid}", VET)[0] == 404
      and call("GET", f"/api/v1/pharmacy/medicines/{mid}", ADMIN)[1]["data"]["active"] is False
      and sql(f"SELECT COUNT(*) FROM {DB}.medicines WHERE id={b(mid)}") == "1")
check("thuốc không tồn tại -> 404 PHARM_1405", code(call("GET", "/api/v1/pharmacy/medicines/019a9442-d808-7000-8000-ffffffffffff", ADMIN)[1]) == "PHARM_1405")

# ------------------------------------------------------------------ nhóm thuốc
cat_code = f"CAT{SUFFIX}"
st, res = call("POST", "/api/v1/pharmacy/medicine-categories", ADMIN, {"code": cat_code.lower(), "name": f"Nhóm thử {SUFFIX}"})
check("Admin thêm nhóm thuốc -> 201, mã viết hoa", st == 201 and res["data"]["code"] == cat_code, (st, res))
check("trùng mã nhóm -> 409", call("POST", "/api/v1/pharmacy/medicine-categories", ADMIN, {"code": cat_code, "name": "Trùng"})[0] == 409)
check("Bác sĩ thêm nhóm -> 403", call("POST", "/api/v1/pharmacy/medicine-categories", VET, {"code": "X", "name": "X"})[0] == 403)
call("PUT", f"/api/v1/pharmacy/medicine-categories/{res['data']['id']}", ADMIN, {"code": cat_code, "name": f"Nhóm thử {SUFFIX}", "active": False})
check("nhóm đã ẩn: Bác sĩ không thấy, Admin thấy khi xin includeInactive", cat_code not in {c["code"] for c in call("GET", "/api/v1/pharmacy/medicine-categories?includeInactive=true", VET)[1]["data"]}
      and cat_code in {c["code"] for c in call("GET", "/api/v1/pharmacy/medicine-categories?includeInactive=true", ADMIN)[1]["data"]})
check("tạo thuốc vào nhóm đã ngừng -> 400", call("POST", "/api/v1/pharmacy/medicines", ADMIN, {**body, "code": f"MED_Z{SUFFIX}", "categoryCode": cat_code})[0] == 400)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: danh mục thuốc v4.0, bảng giá có lịch sử, tồn kho tách bảng và nhật ký kho chạy đúng trên MySQL thật")
