"""Kiểm tra THẬT (MySQL thật, không mock) bước 2.2: quản lý nhân sự & hồ sơ của auth-service.
Gọi từ scripts/service_check.sh:  python scripts/auth_staff_check.py <cổng auth-service> <tên container MySQL>
Mỗi lần chạy tạo dữ liệu mới với SĐT duy nhất nên chạy lại được trên cùng CSDL tạm."""
import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

PORT = sys.argv[1]
MYSQL = sys.argv[2]
BASE = f"http://localhost:{PORT}"
SUFFIX = str(int(time.time()))[-7:]
PASSWORD = "Admin@123"
FAILS = []


def jwt_claims(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        # Service chạy trực tiếp (không qua Gateway) chỉ tin header do Gateway gắn: tự đóng vai Gateway từ claim của JWT
        claims = jwt_claims(token)
        req.add_header("X-User-Id", claims["userId"])
        req.add_header("X-User-Roles", ",".join(claims["roles"]))
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw.decode("utf-8", "replace")}


def login(username, password=PASSWORD):
    return call("POST", "/api/v1/auth/login", body={"username": username, "password": password})


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sql(query):
    out = subprocess.run(["docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "-N", "-B", "-e", query],
                         capture_output=True, text=True, encoding="utf-8")
    return out.stdout.strip()


def vet(license_number):
    return {"licenseNumber": license_number, "licenseIssuedDate": "2020-01-01", "licenseIssuedBy": "Cục Thú y",
            "licenseExpiryDate": "2032-01-01", "academicDegree": "Bác sĩ thú y", "specialty": "Nội khoa",
            "graduatedFrom": "ĐH Nông nghiệp", "careerStartYear": 2018}


def staff_body(phone, roles, room=None, vet_profile=None, name="Nhân sự kiểm tra"):
    body = {"password": "Staff@1234", "fullName": name, "phoneNumber": phone, "email": f"ns{phone}@example.com",
            "roleCodes": roles, "roomNumber": room, "positionTitle": None}
    if vet_profile is not None:
        body["vetProfile"] = vet_profile
    return body


status, res = login("admin")
admin = res["data"]["accessToken"]
status, res = login("bs_tien")
vet_token = res["data"]["accessToken"]
status, res = login("letan_hoa")
rec_token = res["data"]["accessToken"]

phone = "097" + SUFFIX
room = "T" + SUFFIX[-4:]

# --- đăng nhập đồng thời cùng một tài khoản (hai thiết bị): không được xung đột khóa lạc quan
from concurrent.futures import ThreadPoolExecutor
with ThreadPoolExecutor(max_workers=8) as pool:
    results = list(pool.map(lambda _: login("admin")[0], range(8)))
check("8 lần đăng nhập đồng thời của admin đều 200 (last_login_at không bump @Version)", results == [200] * 8, results)

# --- tạo nhân sự
st, res = call("POST", "/api/v1/staff", admin, staff_body(phone, ["VETERINARIAN"], room))
check("Bác sĩ thiếu hồ sơ chuyên môn -> 400 AUTH_1108", st == 400 and res.get("errorCode") == "AUTH_1108", (st, res))

st, res = call("POST", "/api/v1/staff", vet_token, staff_body(phone, ["VETERINARIAN"], room, vet("CC-" + SUFFIX)))
check("Bác sĩ gọi POST /staff -> 403", st == 403, (st, res))
st, res = call("POST", "/api/v1/staff", rec_token, staff_body(phone, ["RECEPTIONIST"]))
check("Lễ tân gọi POST /staff -> 403", st == 403, (st, res))

st, res = call("POST", "/api/v1/staff", admin, staff_body(phone, ["VETERINARIAN"], room, vet("CC-" + SUFFIX)))
check("Tạo Bác sĩ kèm hồ sơ chuyên môn -> 201", st == 201 and res["data"].get("vetProfile"), (st, res))
doc = res["data"]
doc_id = doc["id"]
check("mã nhân viên NVxxxx, phòng viết hoa, vai trò VETERINARIAN",
      doc["employeeCode"].startswith("NV") and doc["roomNumber"] == room.upper() and doc["roles"] == ["VETERINARIAN"], doc)
check("id là UUID", len(doc_id) == 36, doc_id)

for table, column in (("users", "id"), ("user_roles", "user_id"), ("user_profiles", "user_id"), ("staff_profiles", "user_id"),
                      ("veterinarian_profiles", "user_id")):
    n = sql(f"SELECT COUNT(*) FROM pet_clinic_auth_db.{table} WHERE {column} = UUID_TO_BIN('{doc_id}')")
    check(f"một giao dịch ghi bảng {table}", n == "1", n)

st, res = call("POST", "/api/v1/staff", admin, staff_body(phone, ["RECEPTIONIST"]))
check("trùng SĐT -> 409 AUTH_1102", st == 409 and res.get("errorCode") == "AUTH_1102", (st, res))
st, res = call("POST", "/api/v1/staff", admin, staff_body("098" + SUFFIX, ["VETERINARIAN"], room, vet("CC-X" + SUFFIX)))
check("trùng số phòng -> 409 AUTH_1113", st == 409 and res.get("errorCode") == "AUTH_1113", (st, res))
st, res = call("POST", "/api/v1/staff", admin, staff_body("099" + SUFFIX, ["VETERINARIAN"], "U" + SUFFIX[-4:], vet("CC-" + SUFFIX)))
check("trùng số chứng chỉ -> 400", st == 400, (st, res))
n = sql(f"SELECT COUNT(*) FROM pet_clinic_auth_db.user_profiles WHERE phone_number IN ('098{SUFFIX}','099{SUFFIX}')")
check("lỗi nghiệp vụ không để lại dòng nào (rollback/không ghi)", n == "0", n)
st, res = call("POST", "/api/v1/staff", admin, staff_body("096" + SUFFIX, ["PET_OWNER"]))
check("PET_OWNER không phải nhân sự -> 400", st == 400, (st, res))

# --- đọc / tìm
st, res = call("GET", f"/api/v1/staff/{doc_id}", admin)
check("GET /staff/{id} trả hồ sơ nhân sự + chuyên môn", st == 200 and res["data"]["vetProfile"]["licenseNumber"] == "CC-" + SUFFIX, (st, res))
st, res = call("GET", f"/api/v1/staff?role=VETERINARIAN&keyword={room}", admin)
check("GET /staff tìm theo vai trò + từ khóa (số phòng)", st == 200 and [i["id"] for i in res["data"]["items"]] == [doc_id], (st, res))
st, res = call("GET", "/api/v1/staff", rec_token)
check("Lễ tân xem danh sách nhân sự -> 403", st == 403, (st, res))
st, res = call("GET", "/api/v1/staff/doctors", rec_token)
check("GET /staff/doctors có Bác sĩ mới", st == 200 and any(d["id"] == doc_id for d in res["data"]), (st, res))
st, res = call("GET", "/api/v1/roles", admin)
check("GET /roles trả 6 vai trò", st == 200 and len(res["data"]) == 6, (st, res))

# --- hồ sơ chuyên môn, hồ sơ nhân sự
st, res = call("PUT", f"/api/v1/staff/{doc_id}/vet-profile", admin, vet("CC-" + SUFFIX) | {"specialty": "Ngoại khoa"})
check("PUT vet-profile sửa chuyên khoa", st == 200 and res["data"]["vetProfile"]["specialty"] == "Ngoại khoa", (st, res))
st, res = call("PUT", f"/api/v1/staff/{doc_id}", admin, {"positionTitle": "Trưởng khoa Ngoại"})
check("PUT /staff/{id} đổi chức danh", st == 200 and res["data"]["positionTitle"] == "Trưởng khoa Ngoại", (st, res))

# --- hồ sơ cá nhân -> outbox
st, res = call("PUT", f"/api/v1/users/{doc_id}/profile", admin, {"fullName": "BS Đã Đổi Tên"})
check("PUT /users/{id}/profile đổi họ tên", st == 200 and res["data"]["fullName"] == "BS Đã Đổi Tên", (st, res))
n = sql("SELECT COUNT(*) FROM pet_clinic_auth_db.outbox_events "
        f"WHERE aggregate_id = UNHEX(REPLACE('{doc_id}','-','')) AND event_type = 'UserProfileUpdatedEvent'")
check("outbox có UserProfileUpdatedEvent (tạo nhân sự + đổi tên = 2 dòng)", n == "2", n)

# --- khóa / mở khóa / ngừng
st, res = call("POST", f"/api/v1/users/{doc_id}/lock", admin)
check("lock -> LOCKED", st == 200 and res["data"]["status"] == "LOCKED", (st, res))
st, res = login(phone, "Staff@1234")
check("đăng nhập khi bị khóa -> 401 AUTH_1107", st == 401 and res.get("errorCode") == "AUTH_1107", (st, res))
st, res = call("GET", "/api/v1/staff/doctors", rec_token)
check("Bác sĩ bị khóa không còn trong danh sách hoạt động", st == 200 and not any(d["id"] == doc_id for d in res["data"]), (st, res))
n = sql("SELECT COUNT(*) FROM pet_clinic_auth_db.outbox_events "
        f"WHERE aggregate_id = UNHEX(REPLACE('{doc_id}','-','')) AND event_type = 'UserStatusChangedEvent'")
check("outbox có UserStatusChangedEvent", n == "1", n)
st, res = call("POST", f"/api/v1/users/{doc_id}/lock", admin)
check("khóa lại lần hai -> 400", st == 400, (st, res))
st, res = call("POST", f"/api/v1/users/{doc_id}/unlock", admin)
check("unlock -> ACTIVE", st == 200 and res["data"]["status"] == "ACTIVE", (st, res))
st, res = login(phone, "Staff@1234")
check("mở khóa -> đăng nhập lại được", st == 200, (st, res))
st, res = call("POST", f"/api/v1/users/{doc_id}/deactivate", admin)
check("deactivate (xóa mềm) -> INACTIVE", st == 200 and res["data"]["status"] == "INACTIVE", (st, res))
n = sql(f"SELECT COUNT(*) FROM pet_clinic_auth_db.users WHERE id = UUID_TO_BIN('{doc_id}')")
check("deactivate không xóa dòng", n == "1", n)
call("POST", f"/api/v1/users/{doc_id}/unlock", admin)

admin_id = call("GET", "/api/v1/auth/me", admin)[1]["data"]["id"]
st, res = call("POST", f"/api/v1/users/{admin_id}/lock", admin)
check("Admin không tự khóa mình -> AUTH_1114", st == 400 and res.get("errorCode") == "AUTH_1114", (st, res))

# --- vai trò
st, res = call("PUT", f"/api/v1/users/{doc_id}/roles", admin, {"roleCodes": ["VETERINARIAN", "ADMIN"]})
check("PUT roles: Bác sĩ kiêm Quản trị viên", st == 200 and res["data"]["roles"] == ["ADMIN", "VETERINARIAN"], (st, res))
st, res = login(phone, "Staff@1234")
check("đăng nhập lại mang cả hai vai trò", st == 200 and "ADMIN" in json.dumps(res) and "VETERINARIAN" in json.dumps(res), (st, res))
st, res = call("PUT", f"/api/v1/users/{doc_id}/roles", admin, {"roleCodes": ["RECEPTIONIST"]})
check("đổi sang Lễ tân: phòng bị xóa", st == 200 and res["data"].get("roomNumber") in (None, ""), (st, res))
st, res = call("PUT", f"/api/v1/users/{doc_id}/roles", admin, {"roleCodes": ["VETERINARIAN"]})
check("quay lại Bác sĩ vẫn được vì hồ sơ chuyên môn còn", st == 200, (st, res))

# --- khách hàng (Lễ tân)
owner_phone = "095" + SUFFIX
st, res = call("POST", "/api/v1/auth/users", rec_token,
               {"password": "Owner@1234", "fullName": "Khách kiểm tra", "phoneNumber": owner_phone, "roleCode": "PET_OWNER"})
check("Lễ tân tạo khách hàng -> 201", st == 201, (st, res))
owner_id = res["data"]["id"] if st == 201 else None
channel = sql(f"SELECT registered_channel FROM pet_clinic_auth_db.pet_owner_profiles WHERE user_id = UUID_TO_BIN('{owner_id}')")
check("pet_owner_profiles.registered_channel = AT_CLINIC", channel == "AT_CLINIC", channel)
st, res = call("POST", "/api/v1/auth/users", rec_token,
               {"password": "Staff@1234", "fullName": "Mạo danh", "phoneNumber": "094" + SUFFIX, "roleCode": "VETERINARIAN"})
check("Lễ tân tạo nhân sự bằng đường khách hàng -> lỗi", st >= 400, (st, res))
st, res = call("PUT", f"/api/v1/users/{owner_id}/profile", rec_token, {"address": "Số 2 Hà Nội"})
check("Lễ tân sửa hồ sơ khách", st == 200, (st, res))
st, res = call("PUT", f"/api/v1/users/{doc_id}/profile", rec_token, {"address": "Số 3"})
check("Lễ tân sửa hồ sơ nhân sự -> 403", st == 403, (st, res))
st, res = call("POST", f"/api/v1/users/{owner_id}/lock", rec_token)
check("Lễ tân không khóa được tài khoản -> 403", st == 403, (st, res))

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: quản lý nhân sự & hồ sơ chạy đúng trên MySQL thật")
