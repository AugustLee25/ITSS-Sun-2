# HANARE — Build Brief (đọc trước khi làm bất cứ việc gì)

Mục tiêu: dựng sản phẩm chạy được theo `G:\ITSS\HANARE_UXUI_Screen_Spec.md` (spec v1.2, tiếng Việt). **Spec là nguồn sự thật**; nếu mâu thuẫn nội bộ, chọn cách đọc hợp lý nhất, ghi 1 dòng vào `G:\ITSS\docs\SPEC_ISSUES.md` (append, không sửa spec). **Không sửa file spec.**

## Cấu trúc
```
G:\ITSS
├─ HANARE_UXUI_Screen_Spec.md      (spec, KHÔNG sửa)
├─ BE\                              Spring Boot 4.1.2, Java 21 target, Maven Wrapper (mvnw) — package gốc app.hanare
├─ FE\                              React + TypeScript + Vite
├─ docs\                            BUILD_BRIEF.md, API_CONTRACT.md, api-types.ts, RESEARCH.md, SPEC_ISSUES.md
│  └─ source\                       Dữ liệu gốc lấy từ Google Sheet "MVP資料_火2_ハナレ" (xlsx + text từng tab, JP/VN)
├─ .claude\skills\                  skill cài cho dự án
└─ docker-compose.yml               PostgreSQL 16 cho chạy thật
```

## Quyết định kỹ thuật đã chốt (không đổi nếu không có lý do mạnh)
- **BE:** Spring Boot 4.1.2 (Spring Framework 7, Jackson 3, Security 7). Máy KHÔNG có `mvn` → luôn dùng `BE\mvnw.cmd` (Windows) / `./mvnw`. JDK cài sẵn là 26, build target 21.
- **Truy cập DB:** `JdbcClient` + SQL tường minh + `record`. **Không dùng JPA/Hibernate, không Lombok.** Transaction bằng `@Transactional`. Các thao tác ghi nhạy cảm (duyệt, ghi điểm, chuyển vòng) phải idempotent và khóa dòng bằng `SELECT … FOR UPDATE` khi cần.
- **DB:** PostgreSQL, quản lý bằng **Flyway** (`BE/src/main/resources/db/migration`, `V<n>__*.sql`). Enum dùng `varchar + CHECK` (không dùng PG enum). Thời gian lưu `timestamptz` (UTC); hiển thị theo múi giờ nhóm. Điểm: **số nguyên đơn vị `bigint` (`point_units`) đúng như spec 12.8** (`total_units = 1_000_000` ↔ 100%, 1 đơn vị = 0,0001 điểm %). API trả `pointUnits` (số nguyên) + `percentLabel` (chuỗi đã định dạng, vd. "10,0000") — FE không tự chia/làm tròn. Làm tròn chia quỹ bằng phương pháp phần dư lớn nhất (xem `docs/RESEARCH.md` mục D).
- **Test:** JUnit 5 + `io.zonky.test:embedded-postgres` (máy không chạy Docker daemon). `docker-compose.yml` chỉ để người dùng chạy PG thật.
- **Xác thực:** email + mật khẩu (BCrypt), JWT HS256 qua `spring-boot-starter-security-oauth2-resource-server` (tên mới của Boot 4; tên cũ bị deprecated), gửi `Authorization: Bearer`. Nút "Tiếp tục với Google" hiển thị ở S00 nhưng chỉ bật khi có cấu hình OAuth (ẩn/disabled kèm tooltip nếu chưa có). Secret JWT lấy từ env `HANARE_JWT_SECRET` (có giá trị dev mặc định chỉ ở profile `dev`/`test`).
- **API:** REST JSON, tiền tố `/api/v1`, mọi giờ ISO-8601 UTC, id là UUID (`uuid`), lỗi theo một dạng chung `{ "code": "...", "message": "...", "fieldErrors": [{"field","message"}], "traceId": "..." }`. Server là nơi duy nhất tính deadline, trạng thái, màu dashboard, điểm, chuyển quyền nhận điểm. FE không tự suy luận nghiệp vụ.
- **Đồng hồ:** mọi code đọc "bây giờ" qua bean `java.time.Clock` để test được scheduler/kỳ checkpoint.
- **FE:** React 19 + TypeScript + Vite, React Router, TanStack Query, react-hook-form + zod; CSS bằng CSS variables + CSS Modules (không thêm UI framework nặng). Mobile-first (≥44×44 px chạm), WCAG AA, màu luôn kèm nhãn chữ. Toàn bộ chuỗi UI tập trung ở `FE/src/i18n/vi.ts` (tiếng Việt là ngôn ngữ chính; cấu trúc cho phép thêm `ja.ts` sau). Dev proxy `/api` → `http://localhost:8080`.
- **Cổng:** BE 8080, FE 5173. Luôn dừng tiến trình server bạn khởi chạy trước khi kết thúc.
- **Seed demo:** profile `demo` nạp 1 nhóm mẫu 5 người (dựa trên ví dụ trong spec 0.9, 12.10) để xem được mọi màn hình. Nội dung câu chữ lấy cảm hứng từ `docs\source\sheet\` khi hợp (vd. lời động viên sau khi nộp task); khi lệch spec thì **spec thắng**.

## Phân chia module BE (tránh đụng file) — gói `app.hanare.<module>`
- `common`, `security`, `config` — hạ tầng dùng chung (lỗi, Clock, JWT, CurrentUser, GroupAccess/role check, test base).
- **BE-A:** `auth`, `group` (nhóm, thành viên, lời mời), `schedule` (lịch checkpoint, kỳ/cycle, job tới hạn), `notification`, `dashboard` (màu khung tên, tổng quan S03, tóm tắt TL).
- **BE-B:** `task` (task, kế hoạch/revision, vòng xử lý, nộp minh chứng, quyết định TL, carry-over, snapshot), `support` (hỗ trợ riêng), `help` (yêu cầu hỗ trợ công khai, tự nguyện, bàn giao, duyệt), `points` (quỹ, định giá P_t, ledger, contribution, điểm của tôi), `demo` (seed).
- Module này gọi module kia **qua interface/service công khai** (vd. `NotificationService.publish(...)`), không sửa file module khác. Nếu cần hàm mới ở module khác, ghi vào `docs/SPEC_ISSUES.md`… hoặc tự thêm bằng thay đổi nhỏ, tách bạch và nêu trong báo cáo cuối.

## Phân chia FE
- `FE/src/app` (shell, router, providers), `FE/src/components` (thành phần dùng chung theo spec 2.3), `FE/src/api` (client + types), `FE/src/i18n`, `FE/src/pages/SXX-*/` (mỗi màn hình một thư mục, một người sở hữu).
- Route phải mở được trực tiếp bằng URL (kể cả S06/S07 dạng modal/bottom-sheet có link riêng — spec 1.1).

## Phát hiện từ nghiên cứu (chi tiết `docs/RESEARCH.md`)
- Zonky `embedded-postgres` 2.2.2 chạy được trên JDK 26 nhưng mặc định PG 14 → import BOM binaries 16.15.0 để khớp PG16.
- `JdbcClient` bind `Instant` bị lỗi → dùng `OffsetDateTime` (UTC) khi bind/đọc `timestamptz`.
- `react-router-dom` đã bị bỏ ở v8 → import từ `react-router`.
- Skill đã cài ở `G:\ITSS\.claude\skills\`: `frontend-design`, `react-best-practices` (đã cắt cho SPA), `composition-patterns`, `web-design-guidelines` (bản chụp cố định). Agent FE nên đọc `SKILL.md` của chúng khi làm UI.

## Nền tảng BE đã có (xem `docs/BE_FOUNDATION.md`, `docs/DB_SCHEMA.md`) — bắt buộc dùng, không viết lại
- Vai trò nhóm trong code/DB/API là **`LEAD` / `MEMBER`** (không phải LEADER). Mã lỗi theo `docs/api-types.ts`: `VALIDATION_FAILED, MALFORMED_REQUEST, UNAUTHENTICATED, TOKEN_EXPIRED, FORBIDDEN, LEAD_ONLY, NOT_A_GROUP_MEMBER, NOT_FOUND, VERSION_CONFLICT, INVALID_STATE, INTERNAL_ERROR`; mã nghiệp vụ riêng ném bằng `ApiException.of(HttpStatus, "MA", msg)`. `NOT_A_GROUP_MEMBER` → 404, `LEAD_ONLY` → 403, vi phạm unique/FK/trigger DB → 409 `VERSION_CONFLICT`.
- Thời gian DB: `DbTime.toDb(instant)` khi ghi, `DbTime.instant(rs, "col")` khi đọc. Quyền nhóm: `GroupAccess`; người dùng hiện tại: `CurrentUser`.
- Test: `@HanareTest` (context + PG nhúng, ~3–5 s/lớp → gộp test vào ít lớp hoặc dùng `fx.resetData()`), `FixedClock`, `DbFixtures` (nhóm 5 người, kỳ, task đã duyệt, kịch bản điểm), `TestAuth`. Chạy: `BE\mvnw.cmd -B test`.
- DB có thêm so với spec 12: `project_invitations`, `projects.course_name/start_at`, `tasks.independent_approver_*`, `checkpoint_participants` (V3) — xem `docs/SPEC_ISSUES.md`. Sửa migration tại chỗ được (chưa phát hành) nhưng phải chạy lại `MigrationTest` + `ConstraintsTest`.

## PHA 2 — phân công cuối cùng (GHI ĐÈ mọi phân chia module ở trên)
Hợp đồng đầy đủ: `docs/API_CONTRACT.md` (84 endpoint) + `docs/api-types.ts` (nguồn sự thật cho hình dạng JSON). Quy tắc nhạy cảm do server tính: §4 của hợp đồng. Mã lỗi: §9.
| Agent | Module (`app.hanare.*`) | Endpoint (mục hợp đồng) | Dải Flyway |
|---|---|---|---|
| **BE-A** | `auth`, `group`, `schedule`, `notification` | A (5) · B (10) · C (6, trừ `…/lead-summary`) · E (5) | `V10`–`V19` |
| **BE-B1** | `task`, `dashboard` | F (12) · G (6) · H (3) · D (2) · `GET …/checkpoint-cycles/{id}/lead-summary` | `V20`–`V29` |
| **BE-B2** | `support`, `help`, `points` | I (7) · J (13) · K (11) | `V30`–`V39` |
| Pha 3 | `demo` (L) + seed + tích hợp | L (3), gộp 3 bản BE, E2E | — |
- **Cổng chung đã có sẵn trong mã** (không đổi chữ ký): `notification.NotificationService/NotificationEvent` (BE-A cài, mọi module gọi), `schedule.CycleService` (BE-A cài), `schedule.CutoffHook` (BE-A gọi, **BE-B1 cài**), `task.TaskAcceptedHook` (**BE-B1 gọi** trong transaction duyệt, **BE-B2 cài**: ghi điểm + xác nhận contribution/support_work).
- **Mỗi agent BE làm trong BẢN SAO RIÊNG** `G:\ITSS\_work\<BE-A|BE-B1|BE-B2>` (đã chép sẵn từ `BE`, có đủ nền tảng + cổng chung) để `mvnw` không đè `target` của nhau. KHÔNG sửa `G:\ITSS\BE` và không sửa bản sao của agent khác. Chỉ tạo/sửa file trong gói của mình, migration trong dải của mình, test trong gói test của mình.
- **File dùng chung** (`pom.xml`, `SecurityConfig`, `application*.yml`, `docs/*`): hạn chế tối đa; nếu buộc phải sửa, sửa nhỏ + liệt kê chính xác trong mục "SHARED-EDITS" của báo cáo cuối để điều phối viên gộp. BE-A là người duy nhất sửa `SecurityConfig` (mở public `GET /api/v1/invites/{code}`, `GET /api/v1/auth/config`, `GET /api/v1/demo/accounts`); BE-B1/B2 không sửa nó.
- Module khác ghi dữ liệu của task: chỉ **BE-B1** sở hữu ghi vào bảng task/vòng/revision/nộp/duyệt; BE-B2 (bàn giao hỗ trợ, tạo revision `TL_SUPPORT_HANDOFF`, đổi người có quyền nhận điểm) ghi trong gói `help`/`points` bằng SQL của chính nó và phải giữ nguyên các bất biến của DB. `dashboard` (BE-B1) đọc bảng của module khác bằng SQL chỉ-đọc, không gọi service của họ.
- `docs/api-types.ts` đã khớp hợp đồng. Còn thiếu ở DB (đã nêu ở `SPEC_ISSUES.md` dòng `[API→DB]`): BE-B1 thêm `task_plan_revisions.change_reason`, bảng `task_progress_notes`, `checkpoint_report_items.note/expected_completion_at` (V20+); BE-B2 thêm `support_requests.expected_date/round_id`, `help_requests.estimated_effort_minutes/skill_notes`, `risk_reports.affected_task_ids` (V30+).
- Mỗi endpoint phải có test (MockMvc/`@HanareTest` + `TestAuth`) cho đường chính + 1–2 lỗi quyền/trạng thái; nghiệp vụ nhạy cảm (đúng hạn/màu/điểm/idempotency) phải có test theo ví dụ ở spec 12.10.

## Quy tắc làm việc cho mọi agent
1. Đọc spec **chỉ các khoảng dòng được giao** (dùng Read với offset/limit). Không đọc cả file.
2. Dùng tiếng Việt cho nội dung hiển thị/tài liệu, tiếng Anh cho định danh code.
3. Viết gọn, không thêm tính năng ngoài spec ("Chưa đưa vào phạm vi": spec mục 10). Không tạo file README/tài liệu thừa ngoài phần được giao.
4. Kiểm chứng bằng cách chạy thật (build/test) trước khi báo xong; **không báo "xong" nếu chưa chạy hoặc test đỏ** — nói thẳng phần nào chưa chạy được.
5. Không `git init`, không commit (thư mục không phải repo).
6. Báo cáo cuối **≤ 200 từ**: file/gói đã tạo, quyết định lệch brief (nếu có), việc còn dở/rủi ro. Không dán lại code.
7. Cài thư viện: chỉ thêm khi cần, ghi phiên bản; ưu tiên bản ổn định mới nhất đang tương thích với JDK 21+/Boot 4.1 hoặc React 19.

## Bản đồ spec (số dòng trong `HANARE_UXUI_Screen_Spec.md`)
0 Tóm tắt/quy tắc nghiệp vụ 13–144 · 1 Màn hình & điều hướng 146–231 · 2 Layout/design tokens/component 233–290 · 3 Đặc tả S00–S13 292–673 (S00 294, S01 315, S02 338, S03 356, S04 385, S05 403, S06 425, S07 441, S08 451, S09 484, S10 516, S11 545, S12 593, S13 629) · 4 User flow 675–756 · 5 Ma trận chuyển màn hình 757–797 · 6 Trạng thái nghiệp vụ 798–873 · 7 Thông báo/UX writing 874–911 · 8 Phi chức năng 912–941 · 9 Figma (bỏ qua) 942–1008 · 10 MVP 1009–1021 · 12 DB & API 1038–1367 (12.8 điểm 1211–1327, 12.9 migration/API 1328–1367) · 12.10 nghiệm thu 1368–1390 · 13 quyết định 1393–1414.
