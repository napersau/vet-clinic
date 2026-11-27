"""Kiểm tra THẬT bước 4.2 (MySQL thật + RabbitMQ thật, không cần dựng cả hệ thống Docker): đợt khám UUID, tách sinh hiệu (vital_signs) và
hồ sơ khám (medical_records), doctor_name_snapshot, khóa hồ sơ CLIN_1317, chốt ca, bệnh án trọn đời đọc 3 bảng.
  python scripts/visit_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh clinical-service --reset), Docker, JDK 21 và jar của
auth-service / clinical-service trong <module>/target (mvn -o -pl ... install -DskipTests)."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL, RABBIT, RABBIT_PORT = "vetclinic-check-mysql", "vetclinic-check-rabbit", "5673"
REDIS, REDIS_PORT = "vetclinic-check-redis", "6391"   # hàng đợi trên Redis (bước 6.2): phiếu phát sau khi thu phí khám
PORTS = {"auth-service": 18081, "clinical-service": 18083}
SUFFIX = str(int(time.time()))[-6:]
FAILS, PROCS = [], []
TIEN = "019a9442-d810-7000-8000-000000000002"   # bs_tien
AN = "019a9442-d810-7000-8000-000000000003"     # bs_an
BASE = "/api/v1/clinical/pet-visits"


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sh(*args):
    return (subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout or "").strip()


def sql(query):
    return sh("docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "--default-character-set=utf8mb4", "-N", "-B", "-e", query)


def b(uid):
    return f"UNHEX(REPLACE('{uid}','-',''))"


def claims(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def call(service, method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(f"http://localhost:{PORTS[service]}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:  # tự đóng vai Gateway: gắn header danh tính từ claim của JWT
        c = claims(token)
        req.add_header("X-User-Id", c["userId"])
        req.add_header("X-User-Roles", ",".join(c["roles"]))
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


def clin(method, path, token=None, body=None):
    return call("clinical-service", method, path, token, body)


def login(username):
    st, res = call("auth-service", "POST", "/api/v1/auth/login", body={"username": username, "password": "Admin@123"})
    return res["data"]["accessToken"]


def wait_for(fn, seconds=25):
    end = time.time() + seconds
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.5)
    return None


def start_jar(module):
    jar = os.path.join(ROOT, "vetclinic-backend", module, "target", f"{module}.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}: chạy 'cd vetclinic-backend && mvn -o -pl {module} install -DskipTests' trước")
    log = os.path.join(tempfile.gettempdir(), f"visit-check-{module}.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC",
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost", RABBITMQ_PORT=RABBIT_PORT, REDIS_HOST="localhost", REDIS_PORT=REDIS_PORT)
    args = [JAVA, "-Duser.timezone=UTC", "-jar", jar, f"--server.port={PORTS[module]}", "--eureka.client.enabled=false"]
    for name, port in PORTS.items():   # không có Eureka: Feign tìm service theo tên qua cấu hình tĩnh
        args.append(f"--spring.cloud.discovery.client.simple.instances.{name}[0].uri=http://localhost:{port}")
    proc = subprocess.Popen(args, stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env)
    PROCS.append(proc)
    for _ in range(60):
        time.sleep(2)
        if "Started " in open(log, encoding="utf-8", errors="replace").read():
            return
        if proc.poll() is not None:
            break
    sys.exit(f"KHÔNG KHỞI ĐỘNG ĐƯỢC {module}; xem log {log}")


def new_pet(owner_token, name):
    st, res = clin("POST", "/api/v1/pets", owner_token, {"speciesId": 1, "name": name, "gender": "MALE"})
    assert st == 201, (st, res)
    return res["data"]["id"]


def pay_exam_fee(visit_id, rec):
    st, res = clin("GET", f"{BASE}/{visit_id}/service-invoices", rec)
    ids = [i["id"] for i in res["data"]["invoices"] if i["paymentStatus"] == "PENDING"]
    res = clin("POST", f"{BASE}/{visit_id}/service-invoices/confirm-payment", rec, {"invoiceIds": ids})
    if res[0] == 200:   # phiếu phát SAU khi thu tiền (ServiceInvoicePaidEvent -> Redis): chờ có thời hạn
        wait_for(lambda: clin("GET", f"{BASE}/{visit_id}/service-invoices", rec)[1]["data"]["tickets"], 30)
    return res


def to_uuid(hexstr):
    h = hexstr.lower()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def code(res):
    return res.get("errorCode")


try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh clinical-service --reset' trước")
    sh("docker", "rm", "-f", RABBIT, REDIS)
    sh("docker", "run", "-d", "--name", REDIS, "-p", f"{REDIS_PORT}:6379", "redis:7-alpine")
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT):
            break
    time.sleep(3)
    for module in ("clinical-service", "auth-service"):   # clinical khai báo queue trước khi auth phát sự kiện
        start_jar(module)

    LAB_CBC = to_uuid(sql("SELECT HEX(id) FROM pet_clinic_clinical_db.services WHERE code = 'LAB_CBC'"))
    sql("DELETE FROM pet_clinic_clinical_db.queue_ticket_histories")   # Redis tạm đếm số phiếu lại từ 1: bỏ lịch sử của lần chạy trước (UNIQUE loại + ngày + số)
    # chạy lại được trên cùng CSDL tạm: đóng các ca còn dở của hai Bác sĩ thử (lần chạy trước có ca "mở lại" để lại IN_PROGRESS)
    sql(f"UPDATE pet_clinic_clinical_db.pet_visits SET status = 'CANCELLED' WHERE doctor_id IN ({b(TIEN)}, {b(AN)}) AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY')")
    admin, rec = login("admin"), login("letan_hoa")
    tien, an = login("bs_tien"), login("bs_an")
    lan = login("khach_lan")
    tien_name = sql(f"SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = {b(TIEN)}")

    # ================================================================== tiếp đón (T01): lý do đến khám + bản chụp tên Bác sĩ
    pet1, pet2 = new_pet(lan, f"Khám1-{SUFFIX}"), new_pet(lan, f"Khám2-{SUFFIX}")
    st, res = clin("POST", f"{BASE}/check-in", rec, {"petId": pet1, "reasonForVisit": "Bỏ ăn 2 ngày, nôn", "doctorId": TIEN, "isEmergency": False})
    v1 = res.get("data", {})
    check("check-in -> 201, id UUID, mã VIS-năm-XXXXXX, lý do đến khám", st == 201 and len(v1.get("id", "")) == 36
          and v1["visitCode"].startswith("VIS-") and len(v1["visitCode"]) == 15 and v1["reasonForVisit"] == "Bỏ ăn 2 ngày, nôn", (st, res))
    check("response có doctorName (bản chụp lúc chọn Bác sĩ) và KHÔNG còn trường sinh hiệu / chẩn đoán trên đợt khám",
          v1.get("doctorName") == tien_name and "weightAtVisit" not in v1 and "diagnosis" not in v1 and "initialSymptoms" not in v1, v1)
    row = sql(f"SELECT reason_for_visit, doctor_name_snapshot, status FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v1['id'])}").split("\t")
    check("DB: pet_visits.id BINARY(16), reason_for_visit, doctor_name_snapshot, status VARCHAR", row == ["Bỏ ăn 2 ngày, nôn", tien_name, "WAITING"], row)
    check("DB: chưa có medical_records, chưa có vital_signs khi mới tiếp đón",
          sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.medical_records WHERE visit_id = {b(v1['id'])}") == "0"
          and sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.vital_signs WHERE visit_id = {b(v1['id'])}") == "0")
    check("check-in tên cũ initialSymptoms vẫn được nhận (tương thích)",
          clin("POST", f"{BASE}/check-in", rec, {"petId": pet2, "initialSymptoms": "Ho nhiều"})[1]["data"]["reasonForVisit"] == "Ho nhiều")
    v2 = to_uuid(sql(f"SELECT HEX(id) FROM pet_clinic_clinical_db.pet_visits WHERE pet_id = {b(pet2)}"))

    # ================================================================== T02: chưa nộp phí khám thì không bắt đầu khám được (CLIN_1314)
    st, res = clin("POST", f"{BASE}/{v1['id']}/start-exam", tien, {})
    check("chưa nộp phí khám -> start-exam bị từ chối CLIN_1314", st >= 400 and code(res) == "CLIN_1314", (st, res))
    st, res = pay_exam_fee(v1["id"], rec)
    tickets_now = clin("GET", f"{BASE}/{v1['id']}/service-invoices", rec)[1]["data"]["tickets"]   # phiếu phát SAU khi thu tiền (sự kiện + Redis)
    check("thu ngân thu phí khám -> 200, rồi phát phiếu DOCTOR (qua sự kiện)", st == 200 and any(t["queueType"] == "DOCTOR" for t in tickets_now), (st, tickets_now))

    # ================================================================== T02: tạo medical_records + lần đo đầu tiên
    st, res = clin("POST", f"{BASE}/{v1['id']}/start-exam", an, {})
    check("Bác sĩ khác bắt đầu khám ca không phải của mình -> CLIN_1311", st >= 400 and code(res) == "CLIN_1311", (st, res))
    st, res = clin("POST", f"{BASE}/{v1['id']}/start-exam", tien,
                   {"vitalSigns": {"weightKg": 4.6, "temperatureC": 39.2, "heartRateBpm": 160}, "anamnesis": "Nôn 3 lần, bỏ ăn từ hôm qua"})
    d = res.get("data", {})
    check("bắt đầu khám (có sinh hiệu + bệnh sử) -> 200 IN_PROGRESS", st == 200 and d.get("status") == "IN_PROGRESS", (st, res))
    check("chi tiết ca kèm vitalSigns (1 dòng) và medicalRecord (bệnh sử, chưa khóa)",
          len(d.get("vitalSigns") or []) == 1 and d["vitalSigns"][0]["weightKg"] == 4.6 and d["vitalSigns"][0]["recordedBy"] == TIEN
          and d["medicalRecord"]["anamnesis"] == "Nôn 3 lần, bỏ ăn từ hôm qua" and d["medicalRecord"]["locked"] is False, d)
    check("DB: medical_records (visit_id BINARY(16) = pet_visits.id) + vital_signs",
          sql(f"SELECT anamnesis FROM pet_clinic_clinical_db.medical_records WHERE visit_id = {b(v1['id'])}") == "Nôn 3 lần, bỏ ăn từ hôm qua"
          and sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.vital_signs WHERE visit_id = {b(v1['id'])}") == "1")
    check("dịch vụ khám đổi sang IN_PROGRESS, bảng chi tiết có version",
          sql("SELECT status, version FROM pet_clinic_clinical_db.visit_service_details WHERE visit_id = " + b(v1["id"])).split("\t")[0] == "IN_PROGRESS")

    # ================================================================== sinh hiệu: mỗi lần đo một dòng
    st, res = clin("POST", f"{BASE}/{v1['id']}/vital-signs", tien, {"temperatureC": 38.4, "respiratoryRateBpm": 30, "notes": "Đo lại sau hạ sốt"})
    check("ghi lần đo thứ hai -> 201", st == 201 and res["data"]["temperatureC"] == 38.4 and res["data"]["weightKg"] is None, (st, res))
    st, res = clin("GET", f"{BASE}/{v1['id']}/vital-signs", tien)
    check("GET vital-signs: 2 lần đo theo thứ tự thời gian, lần đầu không bị ghi đè",
          st == 200 and len(res["data"]) == 2 and res["data"][0]["temperatureC"] == 39.2 and res["data"][1]["temperatureC"] == 38.4, (st, res))
    check("lần đo trống -> 400", clin("POST", f"{BASE}/{v1['id']}/vital-signs", tien, {})[0] == 400)
    check("chỉ số ngoài khoảng (thân nhiệt 50) -> 400", clin("POST", f"{BASE}/{v1['id']}/vital-signs", tien, {"temperatureC": 50})[0] == 400)
    check("Bác sĩ khác ghi sinh hiệu vào ca không phải của mình -> CLIN_1311", code(clin("POST", f"{BASE}/{v1['id']}/vital-signs", an, {"weightKg": 5})[1]) == "CLIN_1311")
    check("Lễ tân không ghi được sinh hiệu -> 403", clin("POST", f"{BASE}/{v1['id']}/vital-signs", rec, {"weightKg": 5})[0] == 403)
    check("cân nặng gần nhất của thú cưng đọc từ vital_signs", clin("GET", f"/api/v1/pets/{pet1}", rec)[1]["data"]["weight"] == 4.6)

    # ================================================================== hồ sơ khám: @Version, chỉ Bác sĩ phụ trách
    ver = clin("GET", f"{BASE}/{v1['id']}", tien)[1]["data"]["medicalRecord"]["version"]
    st, res = clin("PUT", f"{BASE}/{v1['id']}/medical-record", tien,
                   {"clinicalFindings": "Mất nước nhẹ, đau bụng", "preliminaryDiagnosis": "Viêm dạ dày ruột", "version": ver})
    check("sửa hồ sơ khám -> 200, version tăng, trường không gửi giữ nguyên",
          st == 200 and res["data"]["clinicalFindings"] == "Mất nước nhẹ, đau bụng" and res["data"]["anamnesis"] == "Nôn 3 lần, bỏ ăn từ hôm qua"
          and res["data"]["version"] > ver, (st, res))
    st, res = clin("PUT", f"{BASE}/{v1['id']}/medical-record", tien, {"anamnesis": "Ghi đè bản cũ", "version": ver})
    check("sửa trên phiên bản cũ -> 409 SYS_1005 (không ghi đè)", st == 409 and code(res) == "SYS_1005", (st, res))
    check("Bác sĩ khác sửa hồ sơ ca không phải của mình -> CLIN_1311", code(clin("PUT", f"{BASE}/{v1['id']}/medical-record", an, {"anamnesis": "x"})[1]) == "CLIN_1311")
    check("Lễ tân không sửa được hồ sơ khám -> 403", clin("PUT", f"{BASE}/{v1['id']}/medical-record", rec, {"anamnesis": "x"})[0] == 403)

    # ================================================================== chốt ca (T05): finalDiagnosis, khóa hồ sơ (CLIN_1317)
    st, res = clin("POST", f"{BASE}/{v1['id']}/close-visit", tien, {"treatmentPlan": "Truyền dịch"})
    check("chốt ca khi chưa có chẩn đoán xác định: dùng chẩn đoán sơ bộ (ca không làm cận lâm sàng)", st == 200 and res["data"]["status"] == "WAITING_PAYMENT", (st, res))
    # (ca 1 đã chốt bằng chẩn đoán sơ bộ; kiểm tra dữ liệu ghi xuống)
    rec1 = sql(f"SELECT final_diagnosis, preliminary_diagnosis, treatment_plan FROM pet_clinic_clinical_db.medical_records WHERE visit_id = {b(v1['id'])}").split("\t")
    check("DB: final_diagnosis = chẩn đoán sơ bộ, treatment_plan đã ghi", rec1 == ["Viêm dạ dày ruột", "Viêm dạ dày ruột", "Truyền dịch"], rec1)
    st, res = clin("PUT", f"{BASE}/{v1['id']}/medical-record", tien, {"anamnesis": "Sửa sau khi chốt"})
    check("hồ sơ khám bị khóa sau khi chốt -> 409 CLIN_1317", st == 409 and code(res) == "CLIN_1317", (st, res))
    st, res = clin("POST", f"{BASE}/{v1['id']}/vital-signs", tien, {"weightKg": 5})
    check("không ghi thêm sinh hiệu sau khi chốt", st >= 400, (st, res))
    d = clin("GET", f"{BASE}/{v1['id']}", tien)[1]["data"]
    check("chi tiết ca sau chốt: locked = true cả đợt khám và hồ sơ khám", d["locked"] is True and d["medicalRecord"]["locked"] is True)

    # ================================================================== ca 2: CLIN_1307 (thiếu chẩn đoán), CLIN_1308 (thiếu sinh hiệu đủ cân + nhiệt)
    check("Lễ tân chọn Bác sĩ An cho ca 2", clin("POST", f"{BASE}/{v2}/assign-doctor", rec, {"doctorId": AN})[0] == 200)
    check("snapshot tên Bác sĩ đổi theo lần phân công",
          bool(sql(f"SELECT doctor_name_snapshot FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v2)}")) and
          sql(f"SELECT doctor_name_snapshot FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v2)}") != tien_name)
    check("thu phí khám ca 2", pay_exam_fee(v2, rec)[0] == 200)
    check("Bác sĩ An bắt đầu khám ca 2 không kèm sinh hiệu -> 200", clin("POST", f"{BASE}/{v2}/start-exam", an, {})[0] == 200)
    check("hồ sơ khám được tạo dù không có bệnh sử / sinh hiệu",
          sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.medical_records WHERE visit_id = {b(v2)}") == "1"
          and sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.vital_signs WHERE visit_id = {b(v2)}") == "0")
    st, res = clin("POST", f"{BASE}/{v2}/paraclinical-orders", an, {"serviceIds": [LAB_CBC]})
    check("chỉ định cận lâm sàng khi chưa có lần đo nào -> CLIN_1308", st >= 400 and code(res) == "CLIN_1308", (st, res))
    st, res = clin("POST", f"{BASE}/{v2}/close-visit", an, {})
    check("chốt ca không có chẩn đoán nào -> CLIN_1307", st >= 400 and code(res) == "CLIN_1307", (st, res))
    clin("PUT", f"{BASE}/{v2}/medical-record", an, {"preliminaryDiagnosis": "Viêm đường hô hấp", "followUpDate": (date.today() + timedelta(days=7)).isoformat()})
    check("có chẩn đoán nhưng chưa có sinh hiệu -> CLIN_1308", code(clin("POST", f"{BASE}/{v2}/close-visit", an, {})[1]) == "CLIN_1308")
    clin("POST", f"{BASE}/{v2}/vital-signs", an, {"weightKg": 12.5})
    check("chỉ có cân nặng (thiếu thân nhiệt) vẫn -> CLIN_1308", code(clin("POST", f"{BASE}/{v2}/close-visit", an, {})[1]) == "CLIN_1308")
    clin("POST", f"{BASE}/{v2}/vital-signs", an, {"temperatureC": 38.8})
    check("cân nặng và thân nhiệt ở hai lần đo khác nhau vẫn chưa đủ một lần đo đủ cả hai -> CLIN_1308",
          code(clin("POST", f"{BASE}/{v2}/close-visit", an, {})[1]) == "CLIN_1308")
    clin("POST", f"{BASE}/{v2}/vital-signs", an, {"weightKg": 12.5, "temperatureC": 38.6})
    st, res = clin("POST", f"{BASE}/{v2}/close-visit", an, {"finalDiagnosis": "Viêm phế quản", "treatmentPlan": "Kháng sinh 7 ngày",
                                                           "doctorAdvice": "Giữ ấm", "followUpDate": (date.today() + timedelta(days=7)).isoformat()})
    check("đủ một lần đo cả hai chỉ số -> chốt ca thành công", st == 200 and res["data"]["status"] == "WAITING_PAYMENT", (st, res))
    mr = sql(f"SELECT final_diagnosis, preliminary_diagnosis, treatment_plan, doctor_advice, follow_up_date IS NOT NULL FROM pet_clinic_clinical_db.medical_records WHERE visit_id = {b(v2)}").split("\t")
    check("DB: finalDiagnosis, treatmentPlan, doctorAdvice, followUpDate ghi vào medical_records",
          mr == ["Viêm phế quản", "Viêm đường hô hấp", "Kháng sinh 7 ngày", "Giữ ấm", "1"], mr)

    # ================================================================== mở lại hồ sơ (T14) sửa được lại, khóa lại khi chốt
    st, res = clin("POST", f"{BASE}/{v2}/reopen", an, {"reason": "Bổ sung lời dặn"})
    check("mở lại hồ sơ -> IN_PROGRESS, hồ sơ khám sửa được lại", st == 200 and res["data"]["status"] == "IN_PROGRESS"
          and clin("PUT", f"{BASE}/{v2}/medical-record", an, {"doctorAdvice": "Giữ ấm, uống đủ nước"})[0] == 200)

    # ================================================================== bệnh án trọn đời đọc 3 bảng (pet_visits + vital_signs + medical_records)
    st, res = clin("GET", f"/api/v1/pets/{pet1}/medical-history", tien)
    items = res.get("data", {}).get("items", [])
    e = items[0] if items else {}
    check("bệnh án trọn đời: ca kèm sinh hiệu (2 lần), hồ sơ khám, tên Bác sĩ", st == 200 and len(items) == 1 and len(e["visit"]["vitalSigns"]) == 2
          and e["visit"]["medicalRecord"]["finalDiagnosis"] == "Viêm dạ dày ruột" and e["doctorName"] == tien_name, (st, res))
    # ca đã chốt nhưng chưa COMPLETED thì chủ nuôi chưa thấy; thu hết tiền -> COMPLETED -> thấy đủ
    st, res = pay_exam_fee(v1["id"], rec)   # phí khám đã thu từ lúc tiếp đón: không còn hóa đơn PENDING, xác nhận với danh sách rỗng -> COMPLETED (T10)
    check("thu ngân xác nhận ca đã thu đủ (invoiceIds rỗng) -> COMPLETED", st == 200 and res["data"]["visitStatus"] == "COMPLETED", (st, res))
    st, res = clin("GET", f"/api/v1/pets/{pet1}/medical-history", lan)
    own = res.get("data", {}).get("items", [])
    check("chủ nuôi xem bệnh án của chính thú cưng (ca đã thu đủ tiền): thấy sinh hiệu + chẩn đoán",
          st == 200 and len(own) == 1 and own[0]["visit"]["medicalRecord"]["finalDiagnosis"] == "Viêm dạ dày ruột" and len(own[0]["visit"]["vitalSigns"]) == 2, (st, res))

    # ================================================================== doctor_name_snapshot đổi theo UserProfileUpdatedEvent
    new_name = f"BS Tiến Đổi {SUFFIX}"
    st, res = call("auth-service", "PUT", f"/api/v1/users/{TIEN}/profile", admin, {"fullName": new_name})
    check("Admin đổi họ tên Bác sĩ -> 200", st == 200, (st, res))
    ok = wait_for(lambda: sql(f"SELECT doctor_name_snapshot FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v1['id'])}") == new_name)
    check("auth -> RabbitMQ -> clinical cập nhật doctor_name_snapshot trên đợt khám của Bác sĩ đó", bool(ok))
    check("đợt khám của Bác sĩ khác không bị đụng",
          sql(f"SELECT doctor_name_snapshot FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v2)}") != new_name)
    call("auth-service", "PUT", f"/api/v1/users/{TIEN}/profile", admin, {"fullName": tien_name})   # trả lại tên seed
finally:
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT, REDIS)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: đợt khám UUID + sinh hiệu + hồ sơ khám + bệnh án chạy đúng trên MySQL + RabbitMQ thật")
