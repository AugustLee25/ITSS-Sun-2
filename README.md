# HANARE

Công cụ làm bài tập nhóm: kế hoạch có TL duyệt, checkpoint theo lịch nhóm, dashboard trạng thái, hỗ trợ tự nguyện qua TL và điểm đóng góp (quỹ 100% toàn dự án).
Đặc tả: [`HANARE_UXUI_Screen_Spec.md`](HANARE_UXUI_Screen_Spec.md) (v1.2). Dữ liệu gốc từ Google Sheet nằm ở [`docs/source/`](docs/source/).

| Thư mục | Nội dung |
|---|---|
| `BE/` | Spring Boot 4.1 · Java 21 · JdbcClient · Flyway · PostgreSQL · JWT. 84 endpoint, 36+ bảng |
| `FE/` | React 19 · TypeScript · Vite · TanStack Query. 14 màn hình S00–S13, mobile-first |
| `docs/` | `API_CONTRACT.md` + `api-types.ts` (hợp đồng API), `DB_SCHEMA.md`, `BE_FOUNDATION.md`, `FE_FOUNDATION.md`, `RESEARCH.md`, `SPEC_ISSUES.md` (điểm spec mơ hồ đã quyết) |
| `.claude/skills/` | Skill cài cho dự án (nguồn ghi trong `SOURCES.md`) |

## Chạy thử nhanh (không cần Docker)

Cần JDK 21+ và Node 20+. Chạy `run-demo.cmd` (Windows): mở BE ở cửa sổ riêng (PostgreSQL nhúng, nạp dữ liệu mẫu, lần đầu mất 1–2 phút) rồi mở FE tại <http://localhost:5173>.

Tài khoản demo: lấy ở <http://localhost:8080/api/v1/demo/accounts>, mật khẩu chung `Demo@1234`. Nhóm mẫu gồm 5 người, lịch chốt thứ Sáu 22:00 (Asia/Ho_Chi_Minh). Đồng hồ demo chỉnh được bằng `POST /api/v1/demo/clock` để thử nhắc/chốt kỳ. Tắt BE bằng Ctrl+C.

## Chạy với PostgreSQL thật

```
docker compose up -d db
cd BE && mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
cd FE && npm install && npm run dev
```

Production: đặt `HANARE_DB_URL`, `HANARE_DB_USER`, `HANARE_DB_PASSWORD` và `HANARE_JWT_SECRET` (≥ 32 byte; thiếu thì BE không khởi động). Đăng nhập Google chỉ bật khi có `HANARE_GOOGLE_CLIENT_ID` (BE) và `VITE_GOOGLE_CLIENT_ID` (FE).

## Kiểm thử

```
cd BE && mvnw.cmd -B test                                  # PG nhúng, không cần Docker
cd FE && npm run lint && npm run test -- --run && npm run build
```

`AcceptanceE2ETest` chạy các tình huống nghiệm thu mục 12.10 của spec với bean thật.

## Giới hạn đã biết

- BE yêu cầu header `Idempotency-Key` ở vài thao tác điểm nhưng chưa lưu/phát lại phản hồi; duyệt và ghi điểm vẫn không bị ghi trùng nhờ ràng buộc DB.
- Chưa có endpoint tách/gộp task, hủy phạm vi trả quỹ về dự phòng, rời nhóm giữa chừng (spec nhắc nhưng mục 12.9 không có API) — xem `docs/SPEC_ISSUES.md`.
- Đồng hồ demo chạy lệch ngày thật của trình duyệt nên một số mốc thời gian hiển thị chưa trùng; S10 đang hiện trạng thái "Đang thực hiện" hai lần (lỗi hiển thị nhỏ).
- QA bằng mắt chưa phủ S07, S10 và S05 ở 1280px (chỉ kiểm bằng DOM/API). Ảnh chụp các màn còn lại ở `docs/screenshots/`.
- UI chỉ có tiếng Việt (chuỗi tập trung ở `FE/src/i18n/vi.ts`, thêm `ja.ts` được); minh chứng chỉ là URL, chưa upload tệp.
