"""Kiểm tra THẬT (MySQL thật, không mock) bước 3.1: nhóm dịch vụ, dịch vụ UUID, bảng giá có lịch sử, xét nghiệm của clinical-service.
Gọi từ scripts/service_check.sh:  python scripts/catalog_check.py <cổng clinical-service> <tên container MySQL>
Tạo dữ liệu với mã duy nhất theo thời gian nên chạy lại được trên cùng CSDL tạm; không đổi giá dịch vụ seed."""
import json
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

PORT, MYSQL = sys.argv[1], sys.argv[2]
BASE = f"http://localhost:{PORT}"
SUFFIX = str(int(time.time()))[-6:]
ADMIN = ("019a9442-d800-7729-87cc-fbb1ceda8817", "ADMIN")
RECEPTION = ("019a9442-d810-7000-8000-000000000005", "RECEPTIONIST")
VET = ("019a9442-d810-7000-8000-000000000002", "VETERINARIAN")
OWNER = "019a9442-d810-7000-8000-000000000008"
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


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sql(query):
    out = subprocess.run(["docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "-N", "-B", "-e", query],
                         capture_output=True, text=True, encoding="utf-8")
    return out.stdout.strip()


def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def money(value):
    return None if value is None else f"{float(value):.2f}"


# ------------------------------------------------------------------ nhóm dịch vụ
st, res = call("GET", "/api/v1/catalog/service-categories", VET)
cats = {c["code"]: c for c in res.get("data", [])}
SEED_CATEGORIES = ("EXAMINATION", "VACCINATION", "DEWORMING", "EXTERNAL_PARASITE", "INJECTION_INFUSION", "LABORATORY", "IMAGING", "SURGERY", "PROCEDURE")
check("GET service-categories: đủ 9 nhóm seed", st == 200 and all(c in cats for c in SEED_CATEGORIES), (st, list(cats)))
check("EXAMINATION: hàng đợi DOCTOR, thu trước", cats["EXAMINATION"]["queueType"] == "DOCTOR" and cats["EXAMINATION"]["requiresPrepayment"])
check("LABORATORY / IMAGING: hàng đợi riêng, thu trước", cats["LABORATORY"]["queueType"] == "LABORATORY" and cats["IMAGING"]["queueType"] == "IMAGING"
      and cats["LABORATORY"]["requiresPrepayment"] and cats["IMAGING"]["requiresPrepayment"])
check("VACCINATION: làm tại phòng (queueType null), thu khi chốt ca", cats["VACCINATION"]["queueType"] is None and not cats["VACCINATION"]["requiresPrepayment"])

code = f"TK{SUFFIX}"
st, res = call("POST", "/api/v1/catalog/service-categories", RECEPTION, {"code": code, "name": "Thử"})
check("Lễ tân tạo nhóm -> 403", st == 403, (st, res))
st, res = call("POST", "/api/v1/catalog/service-categories", ADMIN, {"code": code.lower(), "name": "Nhóm thử", "displayOrder": 50})
check("Admin tạo nhóm làm tại phòng -> 201, mã viết hoa, không thu trước", st == 201 and res["data"]["code"] == code and not res["data"]["requiresPrepayment"], (st, res))
cat_id = res["data"]["id"] if st == 201 else None
st, res = call("POST", "/api/v1/catalog/service-categories", ADMIN, {"code": code, "name": "Trùng"})
check("trùng mã nhóm -> 409 CLIN_1327", st == 409 and res.get("errorCode") == "CLIN_1327", (st, res))
st, res = call("PUT", f"/api/v1/catalog/service-categories/{cat_id}", ADMIN, {"code": "KHAC", "name": "Nhóm thử đổi tên", "requiresPrepayment": True})
check("sửa nhóm: mã bất biến, đổi chính sách thu trước", st == 200 and res["data"]["code"] == code and res["data"]["requiresPrepayment"], (st, res))
st, res = call("POST", "/api/v1/catalog/service-categories", ADMIN, {"code": f"BAD{SUFFIX}", "name": "x", "queueType": "KITCHEN"})
check("loại hàng đợi lạ -> 400", st == 400, (st, res))

# ------------------------------------------------------------------ dịch vụ + giá
st, res = call("GET", "/api/v1/catalog/services", VET)
services = {s["code"]: s for s in res.get("data", [])}
check("GET catalog/services: 7 dịch vụ seed kèm giá hiện hành", st == 200 and all(c in services for c in ("EXAM_GEN", "VAC_7B", "VAC_4B", "DEWORM", "LAB_CBC", "IMG_US_AB", "IMG_XRAY")), (st, list(services)))
check("EXAM_GEN = 100.000, LAB_CBC = 250.000 (từ service_prices)", money(services["EXAM_GEN"]["price"]) == "100000.00" and money(services["LAB_CBC"]["price"]) == "250000.00", (services["EXAM_GEN"], services["LAB_CBC"]))
check("id dịch vụ là UUID, nhóm là mã, queueType đọc từ nhóm", len(services["LAB_CBC"]["id"]) == 36 and services["LAB_CBC"]["category"] == "LABORATORY"
      and services["LAB_CBC"]["queueType"] == "LABORATORY" and services["LAB_CBC"]["requiresPrepayment"], services["LAB_CBC"])
st, res = call("GET", "/api/v1/catalog/services?categoryCode=LABORATORY", VET)
check("lọc theo categoryCode=LABORATORY -> chỉ LAB_CBC", st == 200 and [s["code"] for s in res["data"]] == ["LAB_CBC"], (st, res))
view = sql("SELECT COUNT(*) FROM pet_clinic_clinical_db.v_current_service_prices")
check("VIEW v_current_service_prices có đủ dịch vụ seed", int(view) >= 7, view)

scode = f"SV{SUFFIX}"
body = {"code": scode.lower(), "name": "Dịch vụ thử", "categoryCode": code.lower(), "price": 200000, "vatRate": 0.05, "unit": "Mũi", "estimatedMinutes": 15,
        "preparationNotes": "Nhịn ăn", "description": "thử"}
st, res = call("POST", "/api/v1/catalog/services", RECEPTION, body)
check("Lễ tân tạo dịch vụ -> 403", st == 403, (st, res))
st, res = call("POST", "/api/v1/catalog/services", ADMIN, body)
check("Admin tạo dịch vụ + giá đầu tiên -> 201", st == 201 and res["data"]["code"] == scode and money(res["data"]["price"]) == "200000.00"
      and money(res["data"]["vatRate"]) == "0.05" and res["data"]["unit"] == "Mũi", (st, res))
sid = res["data"]["id"] if st == 201 else None
rows = sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.service_prices WHERE service_id = UNHEX(REPLACE('{sid}','-',''))")
check("service_prices có đúng 1 dòng mở cho dịch vụ mới", rows == "1", rows)
st, res = call("POST", "/api/v1/catalog/services", ADMIN, body)
check("trùng mã dịch vụ -> 409", st == 409, (st, res))
st, res = call("POST", "/api/v1/catalog/services", ADMIN, dict(body, code=f"SZ{SUFFIX}", categoryCode="NOPE"))
check("nhóm không tồn tại -> 400", st == 400, (st, res))

now = datetime.now(timezone.utc)
tomorrow = (now + timedelta(days=1)).replace(microsecond=0)
st, res = call("POST", f"/api/v1/catalog/services/{sid}/prices", ADMIN, {"price": 250000, "vatRate": 0.05, "effectiveFrom": iso(tomorrow), "reason": "Điều chỉnh giá"})
check("đặt giá mới có hiệu lực ngày mai -> 201, chưa là giá hiện hành", st == 201 and res["data"]["current"] is False, (st, res))
st, res = call("GET", f"/api/v1/catalog/services/{sid}", VET)
check("hôm nay dịch vụ vẫn giá cũ 200.000 (EX-20)", money(res["data"]["price"]) == "200000.00", res)
st, res = call("GET", f"/api/v1/catalog/services/{sid}/prices", ADMIN)
check("lịch sử giá 2 dòng: dòng mới trước, dòng cũ đã đóng đúng lúc dòng mới bắt đầu",
      st == 200 and len(res["data"]) == 2 and res["data"][0]["effectiveTo"] is None and res["data"][1]["effectiveTo"] == res["data"][0]["effectiveFrom"]
      and res["data"][1]["current"] is True, (st, res))
check("DB: dòng cũ không bị sửa số tiền", sql(f"SELECT price FROM pet_clinic_clinical_db.service_prices WHERE service_id = UNHEX(REPLACE('{sid}','-','')) AND effective_to IS NOT NULL") == "200000.00")
st, res = call("POST", f"/api/v1/catalog/services/{sid}/prices", ADMIN, {"price": 1, "vatRate": 0, "effectiveFrom": iso(now - timedelta(days=30))})
check("hiệu lực sớm hơn giá đang mở -> 400 BILL_1410", st == 400 and res.get("errorCode") == "BILL_1410", (st, res))
st, res = call("POST", f"/api/v1/catalog/services/{sid}/prices", ADMIN, {"price": 1, "vatRate": 1.5})
check("thuế suất > 100% -> 400", st == 400, (st, res))
st, res = call("POST", f"/api/v1/catalog/services/{sid}/prices", VET, {"price": 1, "vatRate": 0})
check("Bác sĩ đổi giá -> 403", st == 403, (st, res))
st, res = call("GET", f"/api/v1/catalog/services/{sid}/prices", VET)
check("Bác sĩ xem lịch sử giá -> 403", st == 403, (st, res))

# giá mới có hiệu lực ngay: dịch vụ khác (chỉ có 1 dòng mở)
st, res = call("POST", "/api/v1/catalog/services", ADMIN, dict(body, code=f"SW{SUFFIX}", price=100000))
sid2 = res["data"]["id"]
time.sleep(1.2)
st, res = call("POST", f"/api/v1/catalog/services/{sid2}/prices", ADMIN, {"price": 120000, "vatRate": 0})
check("đặt giá không ngày -> áp dụng ngay, giá hiện hành đổi", st == 201 and res["data"]["current"] is True, (st, res))
st, res = call("GET", f"/api/v1/catalog/services/{sid2}", VET)
check("giá hiện hành của dịch vụ = 120.000", money(res["data"]["price"]) == "120000.00", res)

st, res = call("PUT", f"/api/v1/catalog/services/{sid}", ADMIN, {"name": "Dịch vụ thử (đã đổi)", "active": False})
check("ngừng dịch vụ (xóa mềm) -> active=false, giá vẫn còn", st == 200 and res["data"]["active"] is False and res["data"]["price"] is not None, (st, res))
st, res = call("GET", "/api/v1/catalog/services", VET)
check("dịch vụ ngừng không còn trong danh sách chọn của Bác sĩ", scode not in [s["code"] for s in res["data"]], res)
st, res = call("GET", "/api/v1/catalog/services?includeInactive=true", ADMIN)
check("Admin xem được cả dịch vụ đã ngừng", scode in [s["code"] for s in res["data"]], res)

# ------------------------------------------------------------------ hóa đơn chụp giá từ bảng giá
owner_pet = f"PET-CK-{SUFFIX}"
pet_id = str(uuid.uuid4())   # pets.id là UUID (v4.0, bước 4.1)
sql("INSERT INTO pet_clinic_clinical_db.pets (id, owner_id, owner_name_snapshot, owner_phone_snapshot, species_id, pet_code, name) VALUES "
    f"(UNHEX(REPLACE('{pet_id}','-','')), UNHEX(REPLACE('{OWNER}','-','')), 'Hoàng Thị Lan', '0912000008', 1, '{owner_pet}', 'Thử giá')")
st, res = call("POST", "/api/v1/clinical/pet-visits/check-in", RECEPTION, {"petId": pet_id, "isEmergency": False, "reasonForVisit": "thử giá"})
check("check-in -> 201 (phí khám lập từ bảng giá)", st == 201, (st, res))
if st == 201:
    visit_id = f"UNHEX(REPLACE('{res['data']['id']}','-',''))"   # pet_visits.id là BINARY(16) (v4.0, bước 4.2)
    row = sql("SELECT i.unit_price, i.vat_rate FROM pet_clinic_clinical_db.service_invoices i "
              "JOIN pet_clinic_clinical_db.visit_service_details d ON d.id = i.visit_service_detail_id "
              f"WHERE d.visit_id = {visit_id}").split("\t")
    cur = sql("SELECT price, vat_rate FROM pet_clinic_clinical_db.v_current_service_prices WHERE code = 'EXAM_GEN'").split("\t")
    check("hóa đơn phí khám chụp đúng giá + VAT hiện hành của EXAM_GEN", row == cur and len(row) == 2, (row, cur))
    view_row = sql(f"SELECT category_code, payable_amount FROM pet_clinic_clinical_db.v_service_invoice_amounts WHERE visit_id = {visit_id}").split("\t")
    check("VIEW v_service_invoice_amounts trả mã nhóm EXAMINATION + số phải trả", view_row[0] == "EXAMINATION" and view_row[1] == cur[0], view_row)

# ------------------------------------------------------------------ xét nghiệm, loài
st, res = call("GET", f"/api/v1/clinical/services/{services['LAB_CBC']['id']}/lab-test-items", RECEPTION)
check("gói LAB_CBC gồm 4 chỉ số", st == 200 and sorted(i["code"] for i in res["data"]) == ["HGB", "PLT", "RBC", "WBC"], (st, res))
rbc = next(i for i in res["data"] if i["code"] == "RBC")
check("RBC có khoảng Chó 5.50–8.50 và Mèo 5.00–10.00 (khóa ghép chỉ số + loài)",
      {(r["speciesCode"], money(r["minValue"]), money(r["maxValue"])) for r in rbc["ranges"]} == {("DOG", "5.50", "8.50"), ("CAT", "5.00", "10.00")}, rbc)
st, res = call("GET", "/api/v1/species", RECEPTION)
dog = next(s for s in res["data"] if s["code"] == "DOG")
st, res = call("PUT", f"/api/v1/clinical/lab-test-items/{rbc['id']}/ranges/{dog['id']}", ADMIN, {"minValue": 5.4, "maxValue": 8.6})
check("sửa khoảng tham chiếu Chó -> vẫn đúng 1 dòng cho cặp (RBC, Chó)",
      st == 200 and sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.lab_test_reference_ranges WHERE lab_test_item_id = {rbc['id']} AND species_id = {dog['id']}") == "1", (st, res))
call("PUT", f"/api/v1/clinical/lab-test-items/{rbc['id']}/ranges/{dog['id']}", ADMIN, {"minValue": 5.5, "maxValue": 8.5})   # trả về như seed
st, res = call("POST", "/api/v1/clinical/lab-test-items", ADMIN, {"code": f"T{SUFFIX}", "name": "Chỉ số thử", "unit": "%",
               "ranges": [{"speciesId": dog["id"], "minValue": 1, "maxValue": 2}]})
check("thêm chỉ số xét nghiệm kèm khoảng theo loài -> 201", st == 201 and len(res["data"]["ranges"]) == 1 and res["data"]["active"] is True, (st, res))
st, res = call("POST", "/api/v1/species", ADMIN, {"code": f"SP{SUFFIX}", "name": "Loài thử"})
sp_id = res["data"]["id"] if st == 201 else None
check("thêm loài -> id SMALLINT, active", st == 201 and res["data"]["active"] is True, (st, res))
st, res = call("PUT", f"/api/v1/species/{sp_id}", ADMIN, {"code": f"SP{SUFFIX}", "name": "Loài thử", "active": False})
check("ngừng loài (xóa mềm)", st == 200 and res["data"]["active"] is False, (st, res))

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: danh mục dịch vụ, bảng giá có lịch sử, hóa đơn chụp giá và xét nghiệm chạy đúng trên MySQL thật")
