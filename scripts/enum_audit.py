"""Đối chiếu 18 enum Java (+ LabEvaluation) với docs/DANH_SACH_KIEU_ENUM_HE_THONG.md (bước 8.1) và rà cột ENUM / khóa Long của thực thể nghiệp vụ.
  python scripts/enum_audit.py
Mỗi enum phải có ít nhất một lớp Java cùng tên có ĐÚNG tập giá trị trong bảng mục C của docs (PaymentStatus có hai bản: billing không có REFUNDED);
không còn cột ENUM trong docs/schema_init.sql; thực thể dùng khóa Long chỉ là các bảng con / lịch sử / audit mà docs cũng để BIGINT AUTO_INCREMENT."""
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
FAILS = []


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


doc = open(os.path.join(ROOT, "docs", "DANH_SACH_KIEU_ENUM_HE_THONG.md"), encoding="utf-8").read()
want = {}
for line in doc.splitlines():
    m = re.match(r"\| \*\*(\d+)\*\* \| `(\w+)` \|", line)
    if m:
        # cột cuối là tập giá trị hợp lệ; bỏ phần chú thích trong ngoặc ("REFUNDED chỉ ở service_invoices")
        last = line.rstrip().rstrip("|").rsplit("|", 1)[1]
        want[m.group(2)] = set(re.findall(r"`([A-Z][A-Z_]+)`", last))
want["LabEvaluation"] = {"LOW", "NORMAL", "HIGH"}
check("docs liệt kê đúng 18 enum lưu CSDL + LabEvaluation", len(want) == 19, len(want))

have = {}
for path in glob.glob(os.path.join(ROOT, "vetclinic-backend", "*", "src", "main", "java", "**", "*.java"), recursive=True):
    text = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", open(path, encoding="utf-8").read(), flags=re.S))   # bỏ chú thích (có thể chứa dấu ;)
    m = re.search(r"public enum (\w+)\s*\{(.*?)\n\}", text, re.S)
    if not m or m.group(1) not in want:
        continue
    head = m.group(2).split(";", 1)[0]
    consts = set(re.findall(r"\b[A-Z][A-Z_0-9]+\b", head))
    module = path.split("vetclinic-backend" + os.sep)[1].split(os.sep)[0]
    have.setdefault(m.group(1), []).append((module, consts))

for name, values in sorted(want.items()):
    found = have.get(name, [])
    ok = bool(found) and all(consts <= values for _, consts in found) and any(consts == values or (name == "PaymentStatus" and consts == values - {"REFUNDED"}) for _, consts in found)
    detail = f"docs {sorted(values)}; Java {[(m, sorted(c)) for m, c in found]}"
    check(f"{name}: Java khớp docs ({', '.join(m for m, _ in found) or 'THIẾU'})", ok, detail)

schema = open(os.path.join(ROOT, "docs", "schema_init.sql"), encoding="utf-8").read()
check("không còn cột kiểu ENUM của MySQL", not re.search(r"\bENUM\s*\(", schema, re.I))

# khóa Long của thực thể: chỉ bảng con / lịch sử / audit (docs cũng BIGINT AUTO_INCREMENT)
docs_ddl = open(os.path.join(ROOT, "docs", "schema_init.sql"), encoding="utf-8").read()
bigint_tables = {t for t, body in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\n\);", docs_ddl, re.S) if re.search(r"^\s+id BIGINT AUTO_INCREMENT PRIMARY KEY", body, re.M)}
bad = []
for path in glob.glob(os.path.join(ROOT, "vetclinic-backend", "*", "src", "main", "java", "**", "entity", "*.java"), recursive=True) + \
        glob.glob(os.path.join(ROOT, "vetclinic-backend", "common-library", "src", "main", "java", "**", "outbox", "OutboxEvent.java"), recursive=True):
    text = open(path, encoding="utf-8").read()
    if re.search(r"private Long id;", text):
        table = re.search(r'@Table\(name = "(\w+)"', text)
        if not table or table.group(1) not in bigint_tables:
            bad.append((os.path.basename(path), table.group(1) if table else None))
check("thực thể dùng khóa Long đều là bảng BIGINT AUTO_INCREMENT trong docs (con / lịch sử / audit); thực thể nghiệp vụ dùng UUID", not bad, bad)
fk_long = []
for path in glob.glob(os.path.join(ROOT, "vetclinic-backend", "*", "src", "main", "java", "**", "entity", "*.java"), recursive=True):
    for m in re.finditer(r"private Long (\w+Id)\b", open(path, encoding="utf-8").read()):
        fk_long.append((os.path.basename(path), m.group(1)))
check("không còn khóa ngoại kiểu Long (xxxId) trong thực thể", not fk_long, fk_long)

# ---- 4 bảng danh mục (L1..L4) khớp mục B của docs
doc_roles = set(re.findall(r"^  - `([A-Z_]+)`: ", doc, re.M))
seed_roles = set(re.findall(r"^\('([A-Z_]+)', '[^']*', '[^']*', TRUE\)", schema, re.M))
check("L1 roles: 6 vai trò hệ thống trong seed đúng như docs", len(doc_roles) == 6 and doc_roles == seed_roles, (doc_roles, seed_roles))
doc_cats = {(m.group(1), m.group(2) or None, m.group(3)) for m in re.finditer(r"^\| `([A-Z_]+)` \| [^|]+ \| (?:`(DOCTOR|LABORATORY|IMAGING)`|NULL) \| (TRUE|FALSE) \|", doc, re.M)}
seed_cats = {(m.group(1), m.group(2), m.group(3)) for m in re.finditer(r"^\('([A-Z_]+)', '[^']*', (?:'(DOCTOR|LABORATORY|IMAGING)'|NULL), (TRUE|FALSE), \d+\)", schema, re.M)}
check("L2 service_categories: 9 nhóm (mã, loại hàng đợi, thu trước) trong seed đúng như docs", len(doc_cats) == 9 and doc_cats == seed_cats, (sorted(doc_cats - seed_cats), sorted(seed_cats - doc_cats)))
species = set(re.findall(r"INSERT INTO species \(code, name, description\) VALUES\n(.*?);", schema, re.S)[0].count("('") and re.findall(r"\('([A-Z_]+)', '", re.findall(r"INSERT INTO species \(code, name, description\) VALUES\n(.*?);", schema, re.S)[0]))
check("L3 species: seed có Chó và Mèo (loài mới thêm bằng INSERT)", {"DOG", "CAT"} <= species, species)
med_cats = re.findall(r"INSERT INTO medicine_categories \(code, name\) VALUES\n(.*?);", schema, re.S)
check("L4 medicine_categories: có dữ liệu khởi tạo", bool(med_cats) and med_cats[0].count("('") >= 4)
for table in ("roles", "service_categories", "species", "medicine_categories"):
    check(f"bảng {table} có khóa số nguyên nhỏ (SMALLINT) như docs", re.search(rf"CREATE TABLE IF NOT EXISTS {table} \(\s*id SMALLINT", schema) is not None)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI")
    sys.exit(1)
print("\nOK: enum Java, cột ENUM và kiểu khóa khớp docs")
