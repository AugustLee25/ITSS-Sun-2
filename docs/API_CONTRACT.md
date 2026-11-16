# HANARE — Hợp đồng API REST `/api/v1` (v1.0)

Hợp đồng giữa **BE Spring Boot** và **FE React**. Nguồn nghiệp vụ: `HANARE_UXUI_Screen_Spec.md` v1.2 (spec thắng khi lệch). Kiểu TypeScript khớp 1-1: [`api-types.ts`](./api-types.ts) — **mỗi endpoint dưới đây có đúng một khóa `"METHOD /path"` trong `ApiEndpoints`**. Đổi hợp đồng = sửa cả hai file. Các điểm spec mơ hồ đã quyết được ghi ở `docs/SPEC_ISSUES.md` (tiền tố `[API]`).

> Mục lục: §1 Quy ước chung · §2 Enum chính tắc · §3 Thông báo · §4 Quy tắc do server tính · §5 Endpoint theo module (A Auth · B Group/Member/Invite · C Schedule & Cycle · D Dashboard · E Notification · F Task/Plan/Round · G Submission & Decision · H Checkpoint Report · I Support riêng & Risk · J Help công khai · K Points · L Demo) · §6 Ánh xạ màn hình → endpoint · §7 Sở hữu module BE-A/BE-B · §8 Nghiệm thu 12.10 → hợp đồng · §9 Bảng mã lỗi · §10 Phụ lục (route FE gợi ý, ví dụ dùng chung).

---

## 1. Quy ước chung

### 1.1 Cơ bản
- Base URL `/api/v1` (dev: FE proxy `/api` → `http://localhost:8080`). JSON UTF-8, `Content-Type: application/json`. Tên trường **camelCase**.
- **Id** là UUID chuỗi (`Uuid`). Trong ví dụ, `<taskId>`, `<groupId>`… là placeholder của một UUID.
- Response **luôn có đủ khóa** (giá trị rỗng là `null` hoặc `[]`, không bỏ khóa). Request: trường tùy chọn có thể bỏ hoặc gửi `null`.
- Không bao giờ trả `password`, `passwordHash`, email của người khác (`UserRef` chỉ có `userId`, `name`).

### 1.2 Xác thực
- `Authorization: Bearer <JWT HS256>` cho mọi endpoint **trừ** các endpoint `PUBLIC`: `GET /auth/config`, `POST /auth/register`, `POST /auth/login`, `POST /auth/google`, `GET /invites/{code}`, `GET /demo/accounts`.
- Không có refresh token ở MVP: token hết hạn → `401 TOKEN_EXPIRED` → FE chuyển S00 (giữ lại URL/mã mời để quay lại).
- Mọi request đã xác thực đều được server kiểm tra lại **membership + vai trò + trạng thái** (BR12). Ẩn nút ở FE không phải biện pháp bảo mật.

### 1.3 Chú giải vai trò (cột "Vai trò" ở từng endpoint)
| Ký hiệu | Nghĩa |
|---|---|
| `PUBLIC` | Không cần token |
| `AUTH` | Người dùng đã đăng nhập (chưa cần thuộc nhóm) |
| `MEMBER` | Thành viên đang hoạt động của nhóm (kể cả TL) — `GroupRole` = `LEAD` hoặc `MEMBER` |
| `LEAD` | Trưởng nhóm (TL/Leader), `GroupRole.LEAD`. **Chuỗi chính tắc là `LEAD`** (spec 12.2), không phải `LEADER` |
| `ASSIGNEE` | Người thực hiện **hiện tại** của task (người phụ trách gốc, hoặc Helper sau bàn giao) |
| `SENDER` | Người gửi đề nghị hỗ trợ riêng |
| `REQUESTER` | Người đăng yêu cầu hỗ trợ công khai (luôn là `LEAD`) |
| `HELPER` | Người được chọn làm thay (theo từng yêu cầu) |
| `BENEFICIARY` | Người cần hỗ trợ (người phụ trách gốc của task) |
| `REVIEWER` | Người có quyền duyệt kết quả của task đó: `LEAD`; **nếu người thực hiện là `LEAD` và task có điểm** thì chỉ *người duyệt độc lập* đã được chỉ định (12.8.7) |
| `RECIPIENT` | Người nhận thông báo |
`GroupRole` chỉ có `LEAD`/`MEMBER`; `REQUESTER/HELPER/BENEFICIARY/ASSIGNEE/SENDER` là vai trò **theo từng đối tượng** (0.3), không phải loại tài khoản.

### 1.4 Thời gian & múi giờ
- Mọi **thời điểm** (`IsoInstant`) là ISO-8601 **UTC có `Z`**, vd `2026-11-20T15:00:00Z` (server dùng `Clock`; được phép có phần giây lẻ ≤ 3 chữ số). FE **không** gửi thời điểm nào khác ngoài các trường deadline/startAt/expectedCompletionAt do người dùng chọn — `submittedAt`, `reviewedAt`, `createdAt`… luôn do server cấp (BR16, 12.4).
- **Ngày lịch** (`IsoDate`, `YYYY-MM-DD`) chỉ dùng cho `Group.startDate`, `Group.deadline`, `expectedDate` (hỗ trợ riêng), tính theo múi giờ nhóm. Deadline chung của nhóm hết hiệu lực lúc **23:59:59.999 của ngày đó theo múi giờ nhóm**.
- Lịch checkpoint lưu giờ địa phương: `weekday` (ISO 1=Thứ Hai … 7=Chủ nhật; **5 = Thứ Sáu**), `cutoffTime` (`"HH:mm"`), `timezone` (IANA, gợi ý `Asia/Ho_Chi_Minh`). Server sinh các **thời điểm UTC** cụ thể cho từng kỳ; FE chỉ hiển thị chúng theo `timezone` của nhóm (`Intl.DateTimeFormat`), không tự cộng trừ giờ.
- Kỳ checkpoint = **`(periodStartAt, closesAt]`** (mở bên trái, đóng bên phải; C01). Ví dụ nhóm chốt 22:00 thứ Sáu giờ VN: `periodStartAt = 2026-11-13T15:00:00Z`, `closesAt = 2026-11-20T15:00:00Z`, `reminderAt = 2026-11-20T08:00:00Z` (15:00 VN = chốt − 7 giờ).
- **Đúng hạn** ⇔ `submittedAt <= deadline` (server tính, FE chỉ đọc `isOnTime`/`timeliness`).

### 1.5 Điểm: số nguyên `pointUnits` + `percentLabel`  *(quyết định đã chốt)*
- Theo spec 12.8: quỹ dự án = **1.000.000 units = 100%**; **1 điểm % = 10.000 units**; 1 unit = 0,0001 điểm %. DB lưu `bigint point_units`.
- **API không trả số thập phân.** Mọi lượng điểm là đối tượng `PointAmount`:
  ```json
  { "pointUnits": 100000, "percentLabel": "10,0000" }
  ```
  - `pointUnits`: số nguyên (an toàn trong `number`; |giá trị| ≤ 1.000.000).
  - `percentLabel`: chuỗi **server định dạng sẵn** (vi-VN, dấu phẩy, đúng 4 chữ số thập phân, không dấu phân nhóm, không kèm `%`/`+`), vd `"3,3333"`, `"0,0000"`, `"100,0000"`.
  - Biến động có dấu (`PointDelta`, cùng hình dạng): `percentLabel` **luôn kèm dấu** `"+4,0000"`, `"-4,0000"`, `"0,0000"`; `pointUnits` có thể âm.
- **FE không tự chia/làm tròn/định dạng lại.** Chỉ hiển thị `percentLabel` (có thể ghép "điểm %"/"%" bằng i18n). Nếu cần cộng/so sánh, dùng `pointUnits` (số nguyên) — nhưng các phép nghiệp vụ (tổng, còn lại sau chuyển, mô phỏng) đều đã do server trả.
- **Request** nhận điểm dạng số nguyên units với hậu tố `Units` (vd `reservePointUnits`, query `minPointUnits`). Không có endpoint nào nhận số thập phân.
- Chia quỹ bằng **phương pháp phần dư lớn nhất** (tie-break theo id ổn định) trên cả giỏ → Σ luôn đúng quỹ (xem §4.6). Ví dụ giỏ 1/2/3 của người 20%: `33333 / 66667 / 100000` units = `"3,3333" / "6,6667" / "10,0000"`.
- Nhãn câu (đã gồm điểm) do server dựng sẵn, vd `pointLabel = "Khó · +10,0000 điểm % khi TL duyệt"`; copy tuân thủ 7.2 (không nói "đã cộng" khi chưa duyệt).

### 1.6 Phân trang, lọc, sắp xếp
- Danh sách có thể lớn dùng **offset**: query `page` (bắt đầu **0**, mặc định 0) và `size` (mặc định 20, tối đa 100). Response `Page<T>`:
  ```json
  { "items": [], "page": 0, "size": 20, "totalItems": 0, "totalPages": 0 }
  ```
- Danh sách nhỏ bị chặn bởi nghiệp vụ (nhóm của tôi, thành viên, lịch…) trả `ItemsResponse<T>` = `{ "items": [...] }` không phân trang.
- Thứ tự sắp xếp **cố định theo từng endpoint** (ghi ở mô tả), trừ `sort` của danh sách kỳ. Lọc bằng query-string đơn giản (giá trị enum chính xác theo §2; thời điểm lọc là ISO UTC). Tham số không hợp lệ → `400 VALIDATION_FAILED` với `fieldErrors`.

### 1.7 Lỗi chuẩn
Mọi lỗi (4xx/5xx) trả **đúng một dạng**:
```json
{
  "code": "DEADLINE_OUTSIDE_CYCLE",
  "message": "Deadline phải nằm trong kỳ 13/11 22:00 → 20/11 22:00.",
  "fieldErrors": [{ "field": "deadline", "message": "Nằm ngoài kỳ đã chọn" }],
  "traceId": "9f2c1e5a7b3d4a60"
}
```
`fieldErrors` luôn có mặt (rỗng nếu không gắn với trường); `field` là đường dẫn (`deadline`, `items[2].note`, `evidence[0].url`). `message` tiếng Việt, hiển thị được. FE dựa vào `code` (enum `ErrorCode`) để rẽ nhánh; `message` chỉ để hiển thị.

| HTTP | Dùng khi |
|---|---|
| 200 / 201 / 204 | Thành công / đã tạo (kèm đối tượng) / xóa không nội dung (`DELETE …/invite`) |
| 400 | Sai cú pháp/kiểu/độ dài/định dạng (`VALIDATION_FAILED`, `MALFORMED_REQUEST`, thiếu `Idempotency-Key`) |
| 401 | Chưa/hết hạn đăng nhập, sai thông tin đăng nhập |
| 403 | Đã là thành viên nhưng không đủ quyền (không phải TL, không phải người thực hiện/người duyệt…) |
| 404 | Không có tài nguyên (`NOT_FOUND`, `INVITE_NOT_FOUND`) **và** người ngoài nhóm (`NOT_A_GROUP_MEMBER`, không lộ nhóm) |
| 409 | **Xung đột trạng thái/phiên bản** (state sai, `VERSION_CONFLICT`, đã có người nhận, trùng) — UI tải lại dữ liệu |
| 410 | Lời mời hết hạn/thu hồi |
| 422 | **Vi phạm quy tắc nghiệp vụ** trên dữ liệu hợp lệ (deadline ngoài kỳ, báo cáo thiếu task, mô phỏng sai…) |
| 429 / 500 / 501 | Quá tải / lỗi server / chưa cấu hình (Google OAuth) |

### 1.8 Đồng thời & phiên bản
- Tài nguyên có thể bị sửa đồng thời mang **`version: number`** (task, plan revision, group, support request, help request, support work, risk report, báo cáo của tôi). Request ghi nhận `version` **trong body** (không dùng `ETag/If-Match`). Lệch → `409 VERSION_CONFLICT`; FE tải lại bản mới rồi cho người dùng thử lại.
- Duyệt/chuyển kỳ/chọn Helper khóa dòng gốc (`SELECT … FOR UPDATE`) và kiểm tra lại trạng thái trong transaction (BR09, 12.9). Submit và job chốt dùng cùng khóa kỳ: **`submittedAt` do server cấp sau khi lấy khóa**; giao dịch chờ khóa mà quá `closesAt` thì bị từ chối `409 SUBMISSION_WINDOW_CLOSED`.

### 1.9 Idempotency
- Header `Idempotency-Key: <chuỗi 8–64 ký tự, nên là UUID>`.
- **REQUIRED** (thiếu → `400 IDEMPOTENCY_KEY_REQUIRED`): các thao tác ghi nhạy cảm — duyệt kế hoạch, nộp kết quả, duyệt kết quả/ghi điểm, chuyển kỳ, gửi báo cáo checkpoint, đăng yêu cầu hỗ trợ, chọn Helper, Helper nộp kết quả, giải quyết bất đồng, khóa phương án điểm, đảo khoản điểm. Danh sách chính xác = hằng `IDEMPOTENCY_REQUIRED` trong `api-types.ts` (và dòng **Idempotency** của từng endpoint).
- **RECOMMENDED**: tạo nhóm/task/risk/offer…; server tôn trọng khóa nếu có (hằng `IDEMPOTENCY_RECOMMENDED`).
- Ngữ nghĩa: khóa được ghi theo `(userId, method, đường dẫn đã thay tham số)` trong **24 giờ**. Lặp lại **cùng khóa + cùng body** → trả **nguyên response đã lưu** (cùng mã HTTP) kèm header `Idempotent-Replayed: true`, không có tác dụng phụ lần hai. Cùng khóa nhưng body khác → `422 IDEMPOTENCY_KEY_REUSED`; request đầu còn đang chạy → `409 IDEMPOTENCY_IN_PROGRESS`.
- **Idempotent tự nhiên** (kể cả khi không gửi khóa): lặp cùng quyết định trên bản đã quyết (duyệt cùng `decision`, chọn cùng offer, chuyển kỳ cùng tham số) trả **200 + kết quả hiện có**, không tạo bản ghi/điểm thứ hai (7.3, S11). Khác quyết định → `409` (`SUBMISSION_ALREADY_REVIEWED`, `INVALID_PLAN_STATE`, `CARRYOVER_ALREADY_DONE`, `HELP_ALREADY_MATCHED`…). Ràng buộc DB (unique) là chốt chặn cuối.

### 1.10 Nhãn do server dựng & `allowedActions`
- Trường có hậu tố `Label`, `reason`, `message`, `headline`, `statusLabel`, `notice` là **câu tiếng Việt đã dựng sẵn** (kèm số lượng, tên, lý do). FE hiển thị nguyên văn. Chip trạng thái vẫn do FE map từ **enum** qua `i18n` (nhưng nhãn server luôn khớp enum). Sau này thêm `ja` qua `Accept-Language` mà không đổi contract.
- `allowedActions` (task, help request, support request, support work) là **tập hành động người gọi được làm lúc này**, server tính theo vai trò + trạng thái + thời điểm. FE dựng CTA từ đây (không suy luận lại, không dựa vào màu).
- Màu luôn kèm `colorLabel` + `statusLabel` + `reason` (không chỉ màu).

### 1.11 Quyền riêng tư (cố định)
- **Đề nghị hỗ trợ riêng** (`support_requests`): chỉ `SENDER` và `LEAD` đọc được (`GET /support-requests/{id}` → 403 `FORBIDDEN` với người khác). Nội dung riêng **không bao giờ** xuất hiện trong dashboard, thông báo, `HelpRequest`, hay `RiskReport.publicSummary`.
- Yêu cầu công khai chỉ sao chép phần TL tự viết. `HelpRequestDetail.supportRequestId` chỉ TL thấy.
- Danh sách `offers` của một yêu cầu chỉ `REQUESTER` thấy; Helper chỉ thấy `myOffer`. Không có bản ghi "từ chối" khi ai đó bỏ qua yêu cầu (BR06): **không có endpoint** cho việc đó.
- `reportNote` (ghi chú trong báo cáo checkpoint) chỉ TL và chính chủ thấy.

### 1.12 Điều hướng từ server (`ScreenTarget`)
`{ "screen": "S05", "params": { "groupId": "<groupId>", "taskId": "<taskId>" } }`. Dùng cho thông báo, `myActions`, `recentActivity`. FE tự ánh xạ `screen+params` → route (gợi ý ở §10.1). Bảng tham số bắt buộc theo màn hình:

| `screen` | `params` |
|---|---|
| S03 | `groupId` (+ `cycleId` khi mở khu TL kiểm tra kỳ) |
| S04 | `groupId` |
| S05 | `groupId`, `taskId` (+ `planId` mở bản đề xuất) |
| S06 | `groupId`, `cycleId` |
| S07 | `groupId` + (`supportRequestId` xem/xử lý) hoặc (`taskId` tạo mới) |
| S08 | `groupId` |
| S09 | `groupId`, `helpRequestId` |
| S10 / S11 | `groupId`, `workId` |
| S12 | `groupId` (+ `ledgerEntryId` mở chi tiết khoản điểm, `taskId`, `tab`) |
| S13 | — |

### 1.13 Ngoài phạm vi MVP (không có endpoint)
Chat riêng; upload tệp (minh chứng chỉ **URL**, `EvidenceKind = URL`); rời nhóm/xóa thành viên/chuyển TL; bỏ phiếu lịch; push/email; bảng xếp hạng; `CompensationAgreement`/nợ công; endpoint kích hoạt scheduler công khai. Job (nhắc −7h, chốt kỳ, tóm tắt TL) chạy nội bộ qua `Clock`.

---

## 2. Enum chính tắc (chuỗi chính xác)

Khớp mục 6 và 12 của spec; `api-types.ts` có `const` + union cùng tên. **Mọi giá trị viết HOA, đúng như bảng.**

### 2.1 Vai trò & nhóm
| Enum | Giá trị | Ghi chú |
|---|---|---|
| `GroupRole` | `LEAD`, `MEMBER` | 12.2 `project_members.role` |
| `InviteStatus` | `ACTIVE`, `EXPIRED`, `REVOKED` | Mã mời |

### 2.2 Kế hoạch, task, vòng xử lý (6.1, 12.4)
| Enum | Giá trị | Ghi chú |
|---|---|---|
| `PlanStatus` | `DRAFT`, `PENDING_APPROVAL`, `CHANGES_REQUESTED`, `APPROVED` | Trạng thái **revision kế hoạch** |
| `PlanApprovalSource` | `MEMBER_PLAN`, `TL_CARRYOVER`, `TL_SUPPORT_HANDOFF` | `approval_source` |
| `Difficulty` | `EASY`, `MEDIUM`, `HARD` | Dễ/Trung bình/Khó; trọng số 1/2/3 |
| `TaskStatus` | `TODO`, `IN_PROGRESS`, `SUBMITTED`, `FIX_REQUIRED`, `UPDATE_REQUIRED`, `DONE` | **Trạng thái thực hiện** — server dựng từ vòng+lần nộp+quyết định |
| `RiskStatus` | `NORMAL`, `UNKNOWN`, `AT_RISK`, `BLOCKED` | Rủi ro của task. "Nguy cơ bỏ việc" **không** nằm ở đây (xem `RiskType`) |
| `RoundState` | `DRAFT`, `ACTIVE`, `AWAITING_REVIEW`, `ACCEPTED`, `CARRYOVER_REQUIRED`, `CARRIED_OVER` | `task_review_rounds.state` |
| `RoundReason` | `INITIAL`, `NOT_SUBMITTED`, `FIX_REQUIRED`, `UPDATE_REQUIRED` | Lý do mở vòng |
| `TaskReviewDecision` | `ACCEPTED`, `FIX_REQUIRED`, `UPDATE_REQUIRED` | Quyết định TL trên một lần nộp |
| `TaskTimeliness` | `NOT_DUE`, `ON_TIME_PENDING_REVIEW`, `ON_TIME_ACCEPTED`, `LATE_PENDING_REVIEW`, `LATE_ACCEPTED`, `OVERDUE_NO_RESULT`, `NEEDS_REWORK` | Đúng hạn/trễ **do server tính** (§4.1). `LATE_ACCEPTED` = `completed_late` |
| `TaskAction` | `EDIT_PLAN`, `SUBMIT_PLAN`, `WITHDRAW_PLAN`, `REVIEW_PLAN`, `PROPOSE_CHANGE`, `START`, `UPDATE_PROGRESS`, `REPORT_CHECKPOINT`, `REPORT_DIFFICULTY`, `SUBMIT_RESULT`, `REVIEW_RESULT`, `SET_CARRYOVER`, `DESIGNATE_REVIEWER`, `VIEW_RESULT` | `allowedActions` của task |
| `TaskListTab` | `ALL`, `MINE`, `PENDING_APPROVAL`, `NEEDS_EDIT`, `NEEDS_ATTENTION` | Tab S04 |
| `TaskHistoryKind` | `TASK_CREATED`, `PLAN_SAVED`, `PLAN_SUBMITTED`, `PLAN_WITHDRAWN`, `PLAN_APPROVED`, `PLAN_CHANGES_REQUESTED`, `TASK_STARTED`, `PROGRESS_NOTE`, `RESULT_SUBMITTED`, `RESULT_ACCEPTED`, `RESULT_FIX_REQUIRED`, `RESULT_UPDATE_REQUIRED`, `CARRIED_OVER`, `SUPPORT_HANDOFF`, `POINTS_AWARDED`, `POINTS_REVERSED` | Dòng lịch sử task |
| `EvidenceKind` | `URL` | MVP chỉ link |

### 2.3 Checkpoint & dashboard (6.1, 0.6)
| Enum | Giá trị | Ghi chú |
|---|---|---|
| `CheckpointStatus` | `SCHEDULED`, `OPEN`, `CLOSED`, `FINALIZED`, `CANCELLED` | `CLOSED` = khóa nộp, TL kiểm tra; `FINALIZED` chỉ khi mọi task đã có quyết định và việc cần chuyển kỳ đã có deadline TL chốt |
| `CyclePhase` | `TENTATIVE`, `AWAITING_LEAD_REVIEW`, `FINALIZED` | Nhãn "Tạm tính" / "Chờ TL kiểm tra" / "Đã chốt đánh giá" (0.6) |
| `CheckpointReportStatus` | `DRAFT`, `SUBMITTED` | Trạng thái một bản báo cáo |
| `MemberReportState` | `NOT_RESPONDED`, `DRAFT`, `SUBMITTED` | `NOT_RESPONDED` = thiếu bản gửi, **không phải** trạng thái task/không phải "trễ" |
| `CheckpointTaskResult` | `RESULT_SUBMITTED`, `RESULT_MISSING` | Member khai |
| `ReportRiskLevel` | `ON_TRACK`, `AT_RISK`, `NEEDS_LEAD_HELP` | 3 lựa chọn S06: Đúng kế hoạch / Có nguy cơ trễ / Cần TL hỗ trợ |
| `DashboardColor` | `GREEN`, `YELLOW`, `RED`, `GRAY` | Xanh / Vàng / Đỏ / Xám (C02) |
| `DashboardReasonCode` | `ALL_ACCEPTED`, `ALL_SUBMITTED_PENDING_REVIEW`, `LATE_1_2`, `LATE_3_PLUS`, `DROPOUT_RISK_CONFIRMED`, `NO_TASK_DUE`, `INSUFFICIENT_DATA` | Lý do màu |
| `GroupHealth` | `ON_TRACK`, `NEEDS_ATTENTION`, `INSUFFICIENT_INFO` | "Theo kế hoạch" / "Cần xem xét" / "Chưa đủ thông tin" |
| `ActionKind` | `CHECKPOINT_REPORT`, `COMPLETE_PLAN`, `REVIEW_PLAN`, `REVIEW_RESULT`, `SET_CARRYOVER`, `FIX_TASK`, `REVIEW_SUPPORT_REQUEST`, `REVIEW_SUPPORT_OFFERS`, `REVIEW_SUPPORT_RESULT`, `CONTINUE_SUPPORT_WORK` | Khối "Hành động của tôi" S03 |
| `ScheduleWarningCode` | `TRANSITION_CYCLE_LONG`, `TRANSITION_CYCLE_SHORT` | Cảnh báo kỳ chuyển tiếp khi đổi lịch |

### 2.4 Hỗ trợ riêng, risk, hỗ trợ công khai (6.2, 12.6)
| Enum | Giá trị | Ghi chú |
|---|---|---|
| `SupportRequestStatus` | `OPEN`, `IN_REVIEW`, `RESOLVED` | `support_requests` (riêng Member→TL) |
| `SupportRequestDecision` | `RESOLVED_PRIVATELY`, `HELP_PUBLISHED` | Kết luận lưu trên đề nghị |
| `SupportRequestLeadAction` | `RESOLVED_PRIVATELY`, `NEED_MORE_DISCUSSION` | Body của `…/decision` ("Đã xử lý…" / "Cần trao đổi thêm"). "Đăng task cần người làm thay" = `POST …/help-requests` |
| `SupportRequestAction` | `DECIDE`, `PUBLISH_HELP` | `allowedActions` của đề nghị riêng |
| `ObstacleType` | `TECHNICAL`, `TIME`, `SCOPE`, `SKILL`, `PERSONAL`, `OTHER` | Loại trở ngại (spec không liệt kê — xem SPEC_ISSUES) |
| `RiskType` | `DELAY_RISK`, `BLOCKED`, `DROPOUT_RISK` | `risk_reports.risk_type` |
| `RiskReportStatus` | `OPEN`, `CONFIRMED`, `RESOLVED` | `CONFIRMED` chỉ dùng cho `DROPOUT_RISK` (TL xác nhận) |
| `HelpRequestStatus` | `OPEN`, `MATCHED`, `IN_PROGRESS`, `SUBMITTED`, `COMPLETED`, `CANCELLED` | `help_requests` công khai. `MATCHED` chỉ gặp ở dữ liệu legacy `CLAIMED`; chọn Helper đặt thẳng `IN_PROGRESS` |
| `HelpRoleInRequest` | `REQUESTER`, `BENEFICIARY`, `HELPER`, `VIEWER` | Vai trò của người gọi trong một yêu cầu |
| `HelpRequestAction` | `OFFER_HELP`, `WITHDRAW_OFFER`, `RECONFIRM_OFFER`, `ACCEPT_OFFER`, `EDIT`, `CANCEL`, `OPEN_WORK` | `allowedActions` (không có "từ chối/bỏ qua") |
| `HelpRequestTab` | `OPEN`, `MY_OFFERS`, `MY_WORK`, `COMPLETED` | Tab S08 |
| `SupportOfferStatus` | `PENDING`, `ACCEPTED`, `NOT_SELECTED`, `WITHDRAWN` | |
| `SupportWorkStatus` | `IN_PROGRESS`, `SUBMITTED`, `REVISION_REQUESTED`, `NEEDS_DISCUSSION`, `APPROVED` | |
| `SupportWorkAction` | `SUBMIT_RESULT`, `REPORT_UNABLE`, `REVIEW_RESULT`, `RESOLVE_DISCUSSION`, `VIEW_CONTRIBUTION` | |
| `SupportWorkResolution` | `RESUME`, `REOPEN_REQUEST`, `CANCEL_REQUEST` | TL xử lý `NEEDS_DISCUSSION` |
| `SupportTimelineKind` | `OFFER_ACCEPTED`, `RESULT_SUBMITTED`, `REVISION_REQUESTED`, `UNABLE_REPORTED`, `DISCUSSION_RESOLVED`, `APPROVED` | Timeline S10 |
| `ContributionStatus` | `PENDING_CONFIRMATION`, `CONFIRMED` | |

### 2.5 Điểm (12.8)
| Enum | Giá trị | Ghi chú |
|---|---|---|
| `PointPolicyStatus` | `DRAFT`, `ACTIVE`, `ARCHIVED` | `point_policies.status` |
| `PointPolicyBasis` | `INITIAL`, `ADDITIONAL` | Lần khóa đầu / định giá thêm từ quỹ dự phòng |
| `TaskPointBudgetState` | `FUNDED`, `RETIRED` | **Chỉ DB** (`task_point_budgets.state`) |
| `PointReservationState` | `HELD`, `CONSUMED`, `RELEASED` | **Chỉ DB** (`task_point_reservations.state`) |
| `PointAssignmentStatus` | `CURRENT`, `SUPERSEDED` | **Chỉ DB** (`task_point_assignments.status`) |
| `LedgerEntryType` | `AWARD`, `REVERSAL` | `point_ledger_entries.entry_type` |
| `PointTaskStatus` | `NOT_STARTED`, `IN_PROGRESS`, `AWAITING_REVIEW`, `NEEDS_REVISION`, `AWARDED`, `TRANSFERRED_OUT` | S12: Chưa làm/Đang làm/Chờ duyệt/Cần sửa/Đã ghi điểm/Đã chuyển đi |
| `PointTaskSource` | `OWN`, `TAKEN_OVER` | Tự làm / nhận làm thay |
| `PointHistoryGroup` | `POINTS_RECORDED`, `ASSIGNMENT` | "Điểm đã ghi" tách khỏi "Phân công" |
| `PointHistoryKind` | `AWARD`, `REVERSAL`, `ASSIGNMENT_OUT`, `ASSIGNMENT_IN`, `RESERVATION_HELD`, `RESERVATION_RELEASED` | |
| `SimulationStepKind` | `START`, `TRANSFER_OUT`, `TAKE_ON` | |

### 2.6 Thông báo & màn hình
| Enum | Giá trị |
|---|---|
| `NotificationType` | `TASK_ASSIGNED`, `PLAN_SUBMITTED`, `PLAN_CHANGE_SUBMITTED`, `PLAN_APPROVED`, `PLAN_CHANGES_REQUESTED`, `CHECKPOINT_REMINDER`, `CHECKPOINT_REVIEW_REQUIRED`, `CHECKPOINT_SCHEDULE_CHANGED`, `NEED_HELP`, `SUPPORT_DECISION`, `HELP_PUBLISHED`, `RISK_RELEVANT`, `SUPPORT_OFFERED`, `SUPPORT_ACCEPTED`, `SUPPORT_SUBMITTED`, `REVISION_REQUESTED`, `CONTRIBUTION_CONFIRMED`, `POINTS_AWARDED`, `POINT_ASSIGNMENT_CHANGED` (19 loại của spec 13) **+ mở rộng:** `TASK_REVIEWED`, `TASK_CARRIED_OVER`, `SUPPORT_NOT_SELECTED`, `SUPPORT_WORK_ISSUE`, `POINTS_ADJUSTED`, `POINT_POLICY_ACTIVATED` |
| `NotificationPriority` | `ACTION_REQUIRED`, `RELEVANT_CHANGE`, `INFORMATIONAL` (7.1) |
| `NotificationEntityType` | `GROUP`, `TASK`, `PLAN_REVISION`, `CHECKPOINT_CYCLE`, `SUPPORT_REQUEST`, `HELP_REQUEST`, `SUPPORT_WORK`, `LEDGER_ENTRY`, `RISK_REPORT`, `POINT_POLICY` |
| `NotificationTab` | `ACTION_REQUIRED`, `INFO`, `ALL` |
| `ScreenId` | `S00` … `S13` |

---

## 3. Thông báo — loại, người nhận, đích, ưu tiên

Mọi thông báo là **in-app**, tạo cùng transaction với sự kiện nghiệp vụ, **khóa chống trùng** `dedup_key` (sự kiện + người nhận; unique `(event_id, recipient_id)`), payload không chứa nội dung riêng tư. `GET /notifications` trả `target` đã dựng; hằng `NOTIFICATION_TARGET_SCREEN` và `NOTIFICATION_PRIORITY` trong `api-types.ts` là bảng dưới đây dạng máy đọc.

| Loại | Người nhận | Sinh ra khi | `entityType` / `entityId` | Đích | Ưu tiên (tab) |
|---|---|---|---|---|---|
| `TASK_ASSIGNED` | Người phụ trách | TL tạo task | TASK | S05 | ACTION_REQUIRED |
| `PLAN_SUBMITTED` | LEAD | Member gửi kế hoạch lần đầu | PLAN_REVISION | S05 (`planId`) | ACTION_REQUIRED |
| `PLAN_CHANGE_SUBMITTED` | LEAD | Member gửi bản sửa của kế hoạch đã duyệt (có `changes` cũ/mới) | PLAN_REVISION | S05 (`planId`) | ACTION_REQUIRED |
| `PLAN_APPROVED` | Người phụ trách | TL duyệt | PLAN_REVISION | S05 | RELEVANT_CHANGE |
| `PLAN_CHANGES_REQUESTED` | Người phụ trách | TL trả lại + lý do | PLAN_REVISION | S05 | ACTION_REQUIRED |
| `CHECKPOINT_REMINDER` | **Mọi thành viên đang hoạt động, gồm TL**, tại `reminderAt` (gửi một lần/kỳ; kể cả người đã báo cáo) | `closesAt − 7h` (bù một lần nếu job khởi động lại trong [reminderAt, closesAt)) | CHECKPOINT_CYCLE | S06 (`cycleId`) | ACTION_REQUIRED |
| `CHECKPOINT_REVIEW_REQUIRED` | LEAD — **một lần/kỳ** | Sau `closesAt`, kể cả khi mọi người đã báo cáo; có tóm tắt (`LeadCycleSummary`) | CHECKPOINT_CYCLE | S03 (`cycleId`) | ACTION_REQUIRED |
| `CHECKPOINT_SCHEDULE_CHANGED` | Mọi thành viên | TL lưu lịch mới | CHECKPOINT_CYCLE | S03 | RELEVANT_CHANGE |
| `NEED_HELP` | **Chỉ LEAD** | Member gửi đề nghị hỗ trợ riêng | SUPPORT_REQUEST | S07 (chế độ TL) | ACTION_REQUIRED |
| `SUPPORT_DECISION` | Người gửi | TL phản hồi/xử lý/đăng công khai | SUPPORT_REQUEST | S07 | RELEVANT_CHANGE |
| `HELP_PUBLISHED` | Mọi thành viên trừ người đăng | TL đăng yêu cầu; nội dung `Task T · Khó · +10,0000 điểm % khi TL duyệt` | HELP_REQUEST | S09 | RELEVANT_CHANGE |
| `RISK_RELEVANT` | Người thực hiện của task bị ảnh hưởng (không lộ nội dung riêng) | Tạo `RiskReport` có `affectedTaskIds` | RISK_REPORT | S05 (`taskId`) | RELEVANT_CHANGE |
| `SUPPORT_OFFERED` | REQUESTER (LEAD) | Có người đề nghị / xác nhận lại đề nghị | HELP_REQUEST | S09 | ACTION_REQUIRED |
| `SUPPORT_ACCEPTED` | Helper | TL chấp thuận đề nghị | SUPPORT_WORK | S10 | RELEVANT_CHANGE |
| `SUPPORT_SUBMITTED` | Người duyệt đã chỉ định | Helper nộp kết quả | SUPPORT_WORK | S11 | ACTION_REQUIRED |
| `REVISION_REQUESTED` | Helper | TL `FIX_REQUIRED`/`UPDATE_REQUIRED` trên bản của Helper | SUPPORT_WORK | S10 | ACTION_REQUIRED |
| `CONTRIBUTION_CONFIRMED` | Helper | Contribution `CONFIRMED` **mà không có khoản điểm** (không có phương án điểm) | SUPPORT_WORK | S12 | INFORMATIONAL |
| `POINTS_AWARDED` | Người nhận điểm | Ghi khoản `AWARD`. **Gộp**: duyệt hỗ trợ có điểm chỉ phát **một** `POINTS_AWARDED` (đã nêu contribution), không phát thêm `CONTRIBUTION_CONFIRMED` | LEDGER_ENTRY | S12 (`ledgerEntryId`) | INFORMATIONAL |
| `POINT_ASSIGNMENT_CHANGED` | Người gốc + Helper (+ khi mở lại/đóng yêu cầu) | Chọn Helper / hoàn trả quyền nhận; **không phải điểm đã kiếm** | HELP_REQUEST | S12 (`taskId`) | RELEVANT_CHANGE |
| `TASK_REVIEWED` *(mở rộng)* | Người thực hiện | TL quyết định `ACCEPTED`/`FIX_REQUIRED`/`UPDATE_REQUIRED` (kèm mô tả lỗi) | TASK | S05 | RELEVANT_CHANGE |
| `TASK_CARRIED_OVER` *(mở rộng)* | Người thực hiện | TL chốt deadline xử lý tiếp | TASK | S05 | ACTION_REQUIRED |
| `SUPPORT_NOT_SELECTED` *(mở rộng)* | Người đề nghị không được chọn / yêu cầu bị hủy | Lịch sự, không xếp hạng, không lý do | HELP_REQUEST | S09 | RELEVANT_CHANGE |
| `SUPPORT_WORK_ISSUE` *(mở rộng)* | LEAD | Helper báo không thể hoàn thành (`NEEDS_DISCUSSION`) | SUPPORT_WORK | S10 | ACTION_REQUIRED |
| `POINTS_ADJUSTED` *(mở rộng)* | Các bên bị ảnh hưởng | Đảo/hiệu chỉnh khoản điểm sau xác nhận (BR11) | LEDGER_ENTRY | S12 | INFORMATIONAL |
| `POINT_POLICY_ACTIVATED` *(mở rộng)* | Mọi thành viên | TL khóa phương án điểm | POINT_POLICY | S12 | RELEVANT_CHANGE |

Quy ước dedup (`dedup_key`): `checkpoint:{cycleId}:reminder`, `checkpoint:{cycleId}:review-required`, `plan:{revisionId}:submitted|approved|changes-requested`, … Chạy job lặp/đồng thời **không** tạo bản thứ hai (12.10).

---

## 4. Quy tắc nhạy cảm do SERVER tính (FE chỉ hiển thị)

### 4.1 Đúng hạn / trễ (`TaskTimeliness`, `isLate`)
Với một task trong một vòng xử lý (deadline = deadline của bản kế hoạch có hiệu lực của vòng đó), tại thời điểm `now`:

| Điều kiện | `timeliness` | `isLate` |
|---|---|---|
| Chưa nộp, `now <= deadline` | `NOT_DUE` | false |
| Chưa nộp, `now > deadline` | `OVERDUE_NO_RESULT` | **true** |
| Bản nộp chốt có `submittedAt <= deadline`, TL chưa quyết | `ON_TIME_PENDING_REVIEW` | false (TL duyệt chậm **không** làm trễ) |
| Như trên, TL `ACCEPTED` | `ON_TIME_ACCEPTED` | false |
| Bản nộp chốt có `submittedAt > deadline`, TL chưa quyết | `LATE_PENDING_REVIEW` | **true** |
| Như trên, TL `ACCEPTED` | `LATE_ACCEPTED` (`completedLate = true`; giữ trong lịch sử kỳ) | **true** |
| TL quyết định `FIX_REQUIRED`/`UPDATE_REQUIRED` | `NEEDS_REWORK` | **true** (chỉ xác lập khi TL quyết định) |

- `submittedAt` do server cấp; `completedAt = submittedAt` của bản được `ACCEPTED`; `acceptedAt = reviewedAt` (riêng, **không** dùng để phạt).
- Một task chỉ đếm **một lần** trong cùng kỳ. Vòng sửa ở kỳ sau không đổi kết quả vòng cũ.
- Biên: deadline đúng `closesAt` thuộc kỳ đó; deadline sau `closesAt` thuộc kỳ kế tiếp (task thuộc kỳ ⇔ `periodStartAt < deadline <= closesAt`).

### 4.2 Màu khung tên (`MemberCycleEvaluation`)
`dueTasks` = task thuộc kỳ có `deadline <= now` (kỳ đã đóng: mọi task của kỳ). `lateTasks` = số task `isLate` trong `dueTasks`.

| Điều kiện (ưu tiên từ trên xuống) | `color` | `reasonCode` | `statusLabel` mẫu |
|---|---|---|---|
| Có `RiskReport` `DROPOUT_RISK` ở `CONFIRMED` cho người này (TL xác nhận) | `RED` | `DROPOUT_RISK_CONFIRMED` | "Đỏ — TL xác nhận nguy cơ bỏ việc" (kèm người/lúc xác nhận) |
| `lateTasks >= 3` | `RED` | `LATE_3_PLUS` | "Trễ 3 task" |
| `lateTasks` 1–2 | `YELLOW` | `LATE_1_2` | "Trễ 2 task" |
| `dueTasks == 0` | `GRAY` | `NO_TASK_DUE` | "Chưa có task đến hạn" |
| `lateTasks == 0`, mọi task đến hạn đã `ON_TIME_ACCEPTED` | `GREEN` | `ALL_ACCEPTED` | "Đã xác nhận hoàn thành" (`provisional=false`) |
| `lateTasks == 0`, còn task `ON_TIME_PENDING_REVIEW` | `GREEN` | `ALL_SUBMITTED_PENDING_REVIEW` | "Đã nộp đủ — chờ TL duyệt" (`provisional=true`) |
| Không đủ dữ liệu để đánh giá | `GRAY` | `INSUFFICIENT_DATA` | "Chưa đủ dữ liệu" |

- **Im lặng/thiếu báo cáo không suy ra đỏ hay bỏ việc**: `reportState = NOT_RESPONDED` hiển thị riêng ("Chưa có cập nhật"), không tác động `color`.
- `phase`: kỳ `SCHEDULED/OPEN` → `TENTATIVE` ("Tạm tính"); `CLOSED` → `AWAITING_LEAD_REVIEW` ("Chờ TL kiểm tra"); `FINALIZED` → "Đã chốt đánh giá". `cutoffEvaluation` = ảnh chụp **bất biến** tại `closesAt`; `evaluation` = sau các quyết định TL (bổ sung có audit).
- Màu mô tả tình trạng công việc của kỳ, không phải xếp hạng; **không** tự biến thành điểm phạt.

### 4.3 Trạng thái nhóm `GroupHealth` (S03 header)
Ưu tiên: `NEEDS_ATTENTION` (có task `BLOCKED`, hoặc `RiskReport` `OPEN`/`CONFIRMED`, hoặc task `AT_RISK` trong kỳ hiện tại) > `INSUFFICIENT_INFO` (nhóm chưa có task đã duyệt; hoặc kỳ hiện tại đã qua `reminderAt` / kỳ vừa đóng chưa `FINALIZED` mà còn thành viên có task bắt buộc ở `NOT_RESPONDED`) > `ON_TRACK`. `INSUFFICIENT_INFO` **không** đồng nghĩa trễ.

### 4.4 Quyền duyệt kết quả (`REVIEWER`)
Chỉ duyệt sau `closesAt` của kỳ chứa vòng, trên **bản nộp chốt** (bản mới nhất trước/đúng cutoff). Người duyệt = `LEAD`; riêng khi người thực hiện là `LEAD`: task **có điểm** → chỉ *người duyệt độc lập* đã chỉ định (`PUT /tasks/{taskId}/independent-reviewer`), nếu chưa có → `409 INDEPENDENT_REVIEWER_REQUIRED`; task **không có điểm** → `LEAD` tự duyệt được. Không ai được duyệt khoản điểm mình nhận (`403 SELF_ACTION_FORBIDDEN`). Helper không được đồng thời là người duyệt.

### 4.5 Kỳ & lịch
Kỳ N: `periodStartAt = closesAt` của kỳ N−1 (kỳ đầu: 00:00 `startDate` theo múi giờ nhóm); `closesAt` = giờ chốt kế tiếp theo lịch; `reminderAt = closesAt − 420 phút`; `opensAt = periodStartAt` nếu `reportingWindowMinutes == null`, ngược lại `max(periodStartAt, closesAt − reportingWindowMinutes)`. Kỳ không chồng lấn, `UNIQUE(group, sequenceNo)`, `UNIQUE(group, closesAt)`. Server luôn sinh sẵn ≥ 4 kỳ tương lai. Đổi lịch **chỉ áp dụng kỳ chưa mở** và nối tiếp ranh giới kỳ trước (có thể sinh kỳ chuyển tiếp dài/ngắn hơn 168 giờ → cảnh báo trong preview).

### 4.6 Điểm (12.8)
`B_i = 1.000.000 / N` (phần dư lớn nhất giữa N người, tie-break id người); `P_t = (B_i − R_i) × w(t) / W_i` với `w = 1/2/3`, `W_i` tính trên **mọi task gốc đã duyệt** của người đó (không chỉ task còn lại); dùng phần dư lớn nhất trên **cả giỏ** (tie-break `task.id`), Σ = quỹ. `W_i = 0` → giữ `B_i` làm quỹ chưa phân bổ. P_t lưu khi khóa, **không tính lại khi đọc**, không đổi qua vòng sửa/chuyển kỳ/đổi người.
`allocated` = Σ P_t của task đang thuộc quyền nhận hiện tại; `confirmed` = tổng ròng ledger; `pending = allocated − confirmed`; `potential = allocated`. Chuyển task chưa ghi điểm: `potential` người gốc −P_t, Helper +P_t (khi **được chọn**; yêu cầu mở chưa có Helper chỉ hiện `pendingTransfer`). **Ledger chỉ ghi khi TL duyệt**; một khoản `AWARD` + settlement + contribution + trạng thái task/work/request commit nguyên tử; idempotent theo khóa.

---
## 5. Endpoint theo module

Mỗi endpoint: **Vai trò · Màn hình · Owner (BE-A/BE-B, §7) · Idempotency**, rồi Request/Response/Lỗi. Tiền tố `/api/v1` được lược ở tiêu đề. Kiểu (`TênKiểu`) có trong `api-types.ts`. Mọi endpoint (trừ `PUBLIC`) còn có thể trả `401 UNAUTHENTICATED/TOKEN_EXPIRED`, `404 NOT_A_GROUP_MEMBER` (tài nguyên/đường dẫn thuộc nhóm mà người gọi không (còn) là thành viên — trả 404 để không lộ sự tồn tại của nhóm; Member gọi việc của TL thì `403 LEAD_ONLY`), `500 INTERNAL_ERROR` — không lặp lại bên dưới.

### A. Auth *(BE-A · `auth`)*

#### `GET /auth/config`
**Vai trò:** PUBLIC · **Màn hình:** S00 · **Owner:** BE-A · **Idempotency:** —
Response `200` `AuthConfigResponse`:
```json
{ "googleEnabled": false, "passwordMinLength": 8, "demoMode": true }
```
`googleEnabled=false` → FE hiển thị nút "Tiếp tục với Google" ở trạng thái disabled kèm tooltip.

#### `POST /auth/register`
**Vai trò:** PUBLIC · **Màn hình:** S00 · **Owner:** BE-A · **Idempotency:** —
Request `RegisterRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `name` | string | ✔ | 1–80 ký tự |
| `email` | string | ✔ | email hợp lệ ≤254; chuẩn hóa chữ thường; unique |
| `password` | string | ✔ | 8–72 ký tự |

Response `201` `AuthResponse`:
```json
{ "accessToken": "eyJhbGciOi…", "tokenType": "Bearer", "expiresIn": 28800,
  "expiresAt": "2026-11-15T18:00:00Z",
  "user": { "userId": "<userId>", "name": "Minh", "email": "minh@example.com", "createdAt": "2026-11-15T10:00:00Z" } }
```
Lỗi: `400 VALIDATION_FAILED`, `409 EMAIL_ALREADY_REGISTERED`.

#### `POST /auth/login`
**Vai trò:** PUBLIC · **Màn hình:** S00 · **Owner:** BE-A · **Idempotency:** —
Request `LoginRequest`: `email` (string ✔), `password` (string ✔). Response `200` `AuthResponse` (như trên).
Lỗi: `400 VALIDATION_FAILED`, `401 INVALID_CREDENTIALS` (không tiết lộ email có tồn tại hay không).

#### `POST /auth/google`
**Vai trò:** PUBLIC · **Màn hình:** S00 · **Owner:** BE-A · **Idempotency:** —
Request `GoogleLoginRequest`: `idToken` (string ✔). Response `200` `AuthResponse`.
Lỗi: `501 OAUTH_NOT_CONFIGURED` (khi `googleEnabled=false`), `401 INVALID_CREDENTIALS`.

#### `GET /auth/me`
**Vai trò:** AUTH · **Màn hình:** shell (mọi màn) · **Owner:** BE-A · **Idempotency:** —
Response `200` `UserProfile`: `{ "userId", "name", "email", "createdAt" }`.

---

### B. Nhóm, thành viên, lời mời *(BE-A · `group`)*

#### `GET /groups`
**Vai trò:** AUTH · **Màn hình:** S01 · **Owner:** BE-A · **Idempotency:** —
Nhóm của tôi, sắp theo `lastActivityAt` giảm dần (null cuối) rồi tên. Response `200` `ItemsResponse<GroupCard>`:
```json
{ "items": [ {
  "id": "<groupId>", "name": "Bài tập lớn CSDL", "courseName": "Cơ sở dữ liệu", "memberCount": 5,
  "deadline": "2026-12-20", "myRole": "MEMBER",
  "attention": { "count": 1, "label": "1 checkpoint cần phản hồi" },
  "unreadNotifications": 3, "lastActivityAt": "2026-11-15T09:12:00Z" } ] }
```
`attention.label` ∈ "Không có việc cần chú ý" · "1 checkpoint cần phản hồi" · "Có N việc liên quan đến bạn". Không có biểu đồ % tiến độ trên card.

#### `POST /groups`
**Vai trò:** AUTH (người tạo thành `LEAD`) · **Màn hình:** S02 (tạo) · **Owner:** BE-A · **Idempotency:** RECOMMENDED
Request `CreateGroupRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `name` | string | ✔ | 3–80 ký tự |
| `courseName` | string\|null | | ≤120 |
| `startDate` | IsoDate | | mặc định hôm nay (theo múi giờ lịch); không trước hôm nay |
| `deadline` | IsoDate | ✔ | không trước `startDate` |
| `schedule.weekday` | int | ✔ | 1–7 (ISO) |
| `schedule.cutoffTime` | "HH:mm" | ✔ | giờ chốt địa phương |
| `schedule.timezone` | string | ✔ | IANA hợp lệ (gợi ý `Asia/Ho_Chi_Minh`) |
| `schedule.reportingWindowMinutes` | int\|null | | null = mở cả kỳ; hoặc 60–20160 |
| `scheduleAgreed` | `true` | ✔ | TL xác nhận lịch đã được nhóm thống nhất (ghi `agreedAt`, `recordedBy`) |

Response `201` `CreateGroupResponse` = `{ group, schedule, currentCycle, invite }`:
```json
{ "group": { "id": "<groupId>", "name": "Bài tập lớn CSDL", "courseName": null, "startDate": "2026-11-15",
    "deadline": "2026-12-20", "timezone": "Asia/Ho_Chi_Minh", "memberCount": 1,
    "leader": { "userId": "<userMinh>", "name": "Minh" }, "myRole": "LEAD",
    "createdAt": "2026-11-15T10:00:00Z", "version": 0 },
  "schedule": { "id": "<scheduleId>", "versionNo": 1, "weekday": 5, "cutoffTime": "22:00",
    "timezone": "Asia/Ho_Chi_Minh", "reminderBeforeMinutes": 420, "reportingWindowMinutes": null,
    "effectiveFrom": "2026-11-14T17:00:00Z", "agreedAt": "2026-11-15T10:00:00Z",
    "recordedBy": { "userId": "<userMinh>", "name": "Minh" }, "isCurrent": true,
    "summaryLabel": "Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00" },
  "currentCycle": { "id": "<cycleId>", "groupId": "<groupId>", "scheduleId": "<scheduleId>", "sequenceNo": 1,
    "periodStartAt": "2026-11-14T17:00:00Z", "opensAt": "2026-11-14T17:00:00Z",
    "reminderAt": "2026-11-20T08:00:00Z", "closesAt": "2026-11-20T15:00:00Z",
    "status": "OPEN", "phase": "TENTATIVE", "phaseLabel": "Tạm tính", "finalizedAt": null,
    "isCurrent": true, "reportWindowOpen": true, "timezone": "Asia/Ho_Chi_Minh",
    "label": "Kỳ 1 · 15/11 00:00 → 20/11 22:00" },
  "invite": { "code": "K7P4Q9XD", "status": "ACTIVE", "sharePath": "/join/K7P4Q9XD",
    "createdBy": { "userId": "<userMinh>", "name": "Minh" }, "createdAt": "2026-11-15T10:00:00Z",
    "expiresAt": "2026-11-29T10:00:00Z" } }
```
Lỗi: `400 VALIDATION_FAILED`, `422 GROUP_DEADLINE_INVALID` (deadline < startDate), `422 SCHEDULE_INVALID` (weekday/giờ/timezone/khung mở sai). Sau khi tạo FE hiển thị modal link/mã + `Sao chép`, rồi `Đi tới nhóm` (S03). Server sinh ≥4 kỳ.

#### `GET /groups/{groupId}`
**Vai trò:** MEMBER · **Màn hình:** S03 header, shell · **Owner:** BE-A · **Idempotency:** —
Response `200` `Group` (như `group` ở trên, `myRole` theo người gọi).

#### `PATCH /groups/{groupId}`
**Vai trò:** LEAD · **Màn hình:** S02 (chỉnh sửa/cài đặt nhóm) · **Owner:** BE-A · **Idempotency:** —
Request `UpdateGroupRequest`: `name?` (3–80), `courseName?`, `deadline?` (IsoDate, không trước `startDate`), `version` (number ✔). Response `200` `Group`.
Lỗi: `403 LEAD_ONLY`, `409 VERSION_CONFLICT`, `422 GROUP_DEADLINE_INVALID`.

#### `GET /groups/{groupId}/members`
**Vai trò:** MEMBER · **Màn hình:** S04 (bộ lọc người phụ trách), S03, S08 · **Owner:** BE-A · **Idempotency:** —
Thành viên đang hoạt động (`left_at IS NULL`), LEAD trước rồi theo `joinedAt`. Response `200` `ItemsResponse<Member>`:
```json
{ "items": [ { "userId": "<userMinh>", "name": "Minh", "role": "LEAD", "joinedAt": "2026-11-15T10:00:00Z" },
             { "userId": "<userAn>", "name": "An", "role": "MEMBER", "joinedAt": "2026-11-15T18:30:00Z" } ] }
```
Không có email.

#### `GET /groups/{groupId}/invite`
**Vai trò:** LEAD · **Màn hình:** S02 (sau tạo), cài đặt nhóm · **Owner:** BE-A · **Idempotency:** —
Response `200` `Invite`. Lỗi: `404 INVITE_NOT_FOUND` (đã thu hồi, chưa tạo lại).

#### `POST /groups/{groupId}/invite`
**Vai trò:** LEAD · **Màn hình:** S02, cài đặt nhóm · **Owner:** BE-A · **Idempotency:** —
Tạo mã mới (thu hồi mã cũ nếu có; hiệu lực mặc định 14 ngày). Không body. Response `201` `Invite`.

#### `DELETE /groups/{groupId}/invite`
**Vai trò:** LEAD · **Màn hình:** cài đặt nhóm · **Owner:** BE-A · **Idempotency:** — (tự nhiên)
Thu hồi mã hiện tại. Response `204`, không body. Lỗi: `404 INVITE_NOT_FOUND`.

#### `GET /invites/{code}`
**Vai trò:** PUBLIC (nếu có token: thêm `alreadyMember`/`groupId`) · **Màn hình:** S00 (nhãn "Bạn đang được mời vào nhóm …"), S02 (Kiểm tra lời mời) · **Owner:** BE-A · **Idempotency:** —
Response `200` `InvitePreview` (chỉ khi mã còn hiệu lực nên `status` luôn `ACTIVE`):
```json
{ "code": "K7P4Q9XD", "status": "ACTIVE", "groupName": "Bài tập lớn CSDL", "courseName": "Cơ sở dữ liệu",
  "leader": { "userId": "<userMinh>", "name": "Minh" }, "memberCount": 5,
  "expiresAt": "2026-11-29T10:00:00Z", "alreadyMember": null, "groupId": null }
```
Lỗi: `404 INVITE_NOT_FOUND`, `410 INVITE_EXPIRED`, `410 INVITE_REVOKED` (FE hiển thị lý do + cách xử lý).

#### `POST /invites/{code}/accept`
**Vai trò:** AUTH · **Màn hình:** S02 (Tham gia), S00→S02 · **Owner:** BE-A · **Idempotency:** — (tự nhiên: đã là thành viên → 409)
Không body. Response `200` `AcceptInviteResponse` = `{ "group": Group }` (người gọi thành `MEMBER`). → S03.
Lỗi: `404 INVITE_NOT_FOUND`, `410 INVITE_EXPIRED/INVITE_REVOKED`, `409 ALREADY_MEMBER`.

---

### C. Lịch checkpoint & kỳ *(BE-A · `schedule`)*

#### `POST /checkpoint-schedules/preview`
**Vai trò:** AUTH (nếu có `groupId`: LEAD của nhóm) · **Màn hình:** S02 bước 2, đổi lịch · **Owner:** BE-A · **Idempotency:** — (không ghi)
Xem trước **không ghi DB**; server là nơi duy nhất sinh giờ UTC (FE không tự tính). Request `SchedulePreviewRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `groupId` | Uuid\|null | | có → xem trước đổi lịch của nhóm (nối ranh giới kỳ hiện có); không có → nhóm sắp tạo |
| `startDate` | IsoDate\|null | | chỉ khi không có `groupId`; mặc định hôm nay |
| `weekday`, `cutoffTime`, `timezone`, `reportingWindowMinutes` | như `ScheduleInput` | ✔/✔/✔/– | |

Response `200` `SchedulePreviewResponse`:
```json
{ "summaryLabel": "Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00", "reminderBeforeMinutes": 420,
  "cycles": [ { "sequenceNo": 4, "periodStartAt": "2026-11-20T15:00:00Z", "opensAt": "2026-11-20T15:00:00Z",
                "reminderAt": "2026-11-27T08:00:00Z", "closesAt": "2026-11-27T15:00:00Z",
                "periodHours": 168, "isTransition": false, "label": "Kỳ 4 · 20/11 22:00 → 27/11 22:00" } ],
  "warnings": [ { "code": "TRANSITION_CYCLE_SHORT", "message": "Kỳ 5 chỉ dài 46 giờ vì đổi sang Chủ nhật 20:00." } ] }
```
Ví dụ nhóm B: `weekday=7`, `cutoffTime="20:00"` → `reminderAt` là **Chủ nhật 13:00** giờ nhóm. Lỗi: `422 SCHEDULE_INVALID`, `403 LEAD_ONLY`.

#### `GET /groups/{groupId}/checkpoint-schedules`
**Vai trò:** MEMBER · **Màn hình:** S02/S03 (lịch hiện hành, lịch sử) · **Owner:** BE-A · **Idempotency:** —
Phiên bản lịch, `versionNo` giảm dần. Response `200` `ItemsResponse<CheckpointSchedule>`.

#### `POST /groups/{groupId}/checkpoint-schedules`
**Vai trò:** LEAD · **Màn hình:** S02/S03 (đổi lịch) · **Owner:** BE-A · **Idempotency:** RECOMMENDED
Lưu lịch **mới** (phiên bản bất biến) thay mặt nhóm. Request `SaveScheduleRequest` = `ScheduleInput` + `scheduleAgreed: true`. Chỉ áp dụng cho kỳ chưa mở; kỳ đã mở/đóng giữ lịch cũ; kỳ đầu của lịch mới nối ranh giới kỳ trước. Phát `CHECKPOINT_SCHEDULE_CHANGED` tới cả nhóm. Response `201` `SaveScheduleResponse`:
```json
{ "schedule": { "id": "<scheduleId2>", "versionNo": 2, "weekday": 7, "cutoffTime": "20:00", "timezone": "Asia/Ho_Chi_Minh",
    "reminderBeforeMinutes": 420, "reportingWindowMinutes": null, "effectiveFrom": "2026-11-20T15:00:00Z",
    "agreedAt": "2026-11-16T03:00:00Z", "recordedBy": { "userId": "<userMinh>", "name": "Minh" },
    "isCurrent": true, "summaryLabel": "Chốt Chủ nhật 20:00 → nhắc Chủ nhật 13:00" },
  "affectedCycles": [ { "…": "CheckpointCycle đã sinh lại, status SCHEDULED" } ] }
```
Lỗi: `403 LEAD_ONLY`, `422 SCHEDULE_INVALID`, `400 VALIDATION_FAILED` (`scheduleAgreed` ≠ true).

#### `GET /groups/{groupId}/checkpoint-cycles`
**Vai trò:** MEMBER · **Màn hình:** S03 (chọn kỳ), S04 (chọn "tuần"), S05 (chọn kỳ chuyển) · **Owner:** BE-A · **Idempotency:** —
Query `ListCyclesQuery`: `status?` (`CheckpointStatus`), `sort?` (`asc`|`desc`, mặc định `desc`), `page`, `size`. Response `200` `Page<CheckpointCycle>`.

#### `GET /groups/{groupId}/checkpoint-cycles/current`
**Vai trò:** MEMBER · **Màn hình:** S03, S06 · **Owner:** BE-A · **Idempotency:** —
Kỳ chứa thời điểm hiện tại (`periodStartAt < now <= closesAt`). Response `200` `CheckpointCycleDetail`.

#### `GET /groups/{groupId}/checkpoint-cycles/{cycleId}`
**Vai trò:** MEMBER (`leadSummary` chỉ LEAD) · **Màn hình:** S03, S06 · **Owner:** BE-A · **Idempotency:** —
Response `200` `CheckpointCycleDetail` = `CheckpointCycle` + `serverTime`, `previousCycleId`, `nextCycleId`, `activeMemberCount`, `reportedMemberCount`, `notRespondedMemberCount`, `leadSummary`:
```json
{ "id": "<cycleId3>", "groupId": "<groupId>", "scheduleId": "<scheduleId>", "sequenceNo": 3,
  "periodStartAt": "2026-11-13T15:00:00Z", "opensAt": "2026-11-13T15:00:00Z",
  "reminderAt": "2026-11-20T08:00:00Z", "closesAt": "2026-11-20T15:00:00Z",
  "status": "OPEN", "phase": "TENTATIVE", "phaseLabel": "Tạm tính", "finalizedAt": null,
  "isCurrent": true, "reportWindowOpen": true, "timezone": "Asia/Ho_Chi_Minh",
  "label": "Kỳ 3 · 13/11 22:00 → 20/11 22:00",
  "serverTime": "2026-11-18T03:00:00Z", "previousCycleId": "<cycleId2>", "nextCycleId": "<cycleId4>",
  "activeMemberCount": 5, "reportedMemberCount": 2, "notRespondedMemberCount": 3, "leadSummary": null }
```
Lỗi: `404 NOT_FOUND`.

#### `GET /groups/{groupId}/checkpoint-cycles/{cycleId}/lead-summary`
**Vai trò:** LEAD · **Màn hình:** S03 (khu TL kiểm tra kỳ), đích của `CHECKPOINT_REVIEW_REQUIRED` · **Owner:** BE-A · **Idempotency:** —
Query: `view?` = `CURRENT` (mặc định) | `AT_CUTOFF` (bản đóng băng tại `closesAt`, bất biến). Response `200` `LeadCycleSummary`:
```json
{ "cycleId": "<cycleId3>", "phase": "AWAITING_LEAD_REVIEW", "asOf": "2026-11-20T15:00:01Z", "isAtCutoff": true,
  "members": { "total": 5, "reported": 4, "notResponded": 1 },
  "tasks": { "total": 9, "accepted": 0, "submittedPendingReview": 6, "late": 2, "needsRework": 0, "noResult": 2, "carryoverRequired": 0 },
  "openRisks": 1, "openPrivateSupportRequests": 1, "openHelpRequests": 1,
  "notRespondedMembers": [ { "userId": "<userDung>", "name": "Dung" } ],
  "headline": "Kỳ 3 đã chốt: 4/5 thành viên đã báo cáo, 6 task chờ bạn duyệt, 2 task chưa có kết quả." }
```
`notRespondedMembers` dùng nhãn trung lập "Chưa có cập nhật" (không phải trễ/bỏ việc). Lỗi: `403 LEAD_ONLY`.

---

### D. Dashboard *(BE-A · `dashboard`)*

#### `GET /groups/{groupId}/dashboard`
**Vai trò:** MEMBER (`leadPanel` chỉ LEAD) · **Màn hình:** S03 · **Owner:** BE-A (dữ liệu task/help/points lấy qua service công khai của BE-B, §7.2) · **Idempotency:** —
Query: `cycleId?` (Uuid, mặc định kỳ hiện tại; dùng để lọc theo kỳ). Một request đủ dựng S03 theo thứ tự ưu tiên của spec. Response `200` `DashboardResponse` (rút gọn):
```json
{
  "serverTime": "2026-11-18T03:00:00Z",
  "group": { "id": "<groupId>", "name": "Bài tập lớn CSDL", "courseName": "Cơ sở dữ liệu", "deadline": "2026-12-20",
             "memberCount": 5, "leader": { "userId": "<userMinh>", "name": "Minh" },
             "health": "NEEDS_ATTENTION", "healthLabel": "Cần xem xét",
             "healthReason": "1 risk đang mở ảnh hưởng tiến độ; chưa phải kết luận về năng suất cá nhân." },
  "myRole": "MEMBER",
  "cycle": { "…": "CheckpointCycle (Kỳ 3)" }, "previousCycleId": "<cycleId2>", "nextCycleId": "<cycleId4>",
  "myActions": [ { "kind": "CHECKPOINT_REPORT", "title": "Cập nhật checkpoint Kỳ 3",
                   "subtitle": "Chốt 22:00 Thứ Sáu 20/11", "dueAt": "2026-11-20T15:00:00Z", "count": 3,
                   "target": { "screen": "S06", "params": { "groupId": "<groupId>", "cycleId": "<cycleId3>" } } } ],
  "myTasks": [ { "…": "TaskSummary (2–5 task sắp tới)" } ],
  "nextCheckpoint": { "cycle": { "…": "CheckpointCycle" }, "myRequiredTaskCount": 3, "myRespondedTaskCount": 0,
                      "myReportState": "NOT_RESPONDED", "myReportLabel": "Chưa có cập nhật", "canReportNow": true,
                      "reportedMemberCount": 2, "notRespondedMemberCount": 3 },
  "memberProgress": [ { "member": { "userId": "<userAn>", "name": "An" }, "role": "MEMBER",
      "evaluation": { "color": "GREEN", "colorLabel": "Xanh", "statusLabel": "Đã nộp đủ — chờ TL duyệt",
        "reasonCode": "ALL_SUBMITTED_PENDING_REVIEW", "reason": "3/3 task đến hạn đã nộp đúng hạn, đang chờ TL duyệt.",
        "provisional": true, "phase": "TENTATIVE", "phaseLabel": "Tạm tính",
        "counts": { "totalTasks": 3, "dueTasks": 3, "acceptedTasks": 0, "pendingReviewTasks": 3, "lateTasks": 0,
                    "needsReworkTasks": 0, "noResultTasks": 0, "reportedTasks": 3, "missingReportTasks": 0 } },
      "cutoffEvaluation": null, "reportState": "SUBMITTED", "reportLabel": "Đã gửi đầy đủ",
      "reportSubmittedAt": "2026-11-17T14:00:00Z", "dropoutRisk": null } ],
  "leadPanel": null,
  "sharedRisks": [ { "id": "<riskId>", "riskType": "DELAY_RISK", "taskId": "<taskId>", "taskTitle": "Thiết kế API",
                     "publicSummary": "Phần API có thể cần thêm 1 ngày", "createdAt": "2026-11-17T09:00:00Z" } ],
  "openHelp": [ { "…": "HelpRequestCard (1–3)" } ],
  "myPoints": { "policyActive": true, "confirmedPoints": { "pointUnits": 0, "percentLabel": "0,0000" },
                "potentialPoints": { "pointUnits": 200000, "percentLabel": "20,0000" },
                "label": "Đã xác nhận 0,0000% / Có thể đạt 20,0000%" },
  "recentActivity": [ { "id": "<id>", "at": "2026-11-17T14:00:00Z", "text": "An đã gửi báo cáo checkpoint", "target": null } ],
  "flags": { "groupHasAnyTask": true, "iHaveAnyTask": true }
}
```
Với **LEAD**, `leadPanel` = `{ pendingPlans:{count,items:PlanQueueItem[]}, privateSupportRequests:{count,items:SupportRequestSummary[]}, reviewCycle: LeadCycleSummary|null, awaitingReviewCount, carryoverRequiredCount }`. `myActions` chỉ chứa CTA có thật (`ActionKind` §2.3). Màu/nhãn/lý do tính theo §4.2; `recentActivity` ≤ 5; không lộ nội dung hỗ trợ riêng. Empty state: `flags.groupHasAnyTask=false` → "Lập kế hoạch đầu tiên"; `iHaveAnyTask=false` → "Bạn chưa có nhiệm vụ được giao". Lỗi: `404 NOT_FOUND` (cycleId không thuộc nhóm).

#### `GET /groups/{groupId}/dashboard/members/{userId}`
**Vai trò:** MEMBER (`reportNote` chỉ LEAD hoặc chính chủ) · **Màn hình:** S03 (click khung tên) · **Owner:** BE-A · **Idempotency:** —
Query `cycleId?`. Response `200` `MemberProgressDetail` = `{ cycle, progress: MemberProgress, tasks: CycleTaskRow[] }`. Mỗi `CycleTaskRow` có deadline, trạng thái nộp (`submittedAt`, `timeliness`, `isLate`), kết quả duyệt (`review`), `completedAt`/`acceptedAt`/`completedLate`, báo cáo (`reported`, `declaredResult`, `riskLevel`), `handedOverTo`, `carryover`; dựng từ snapshot/vòng xử lý của kỳ (không dựng lại từ trạng thái hiện tại). Ví dụ một phần tử `tasks[]` (`CycleTaskRow`):
```json
{ "taskId": "<taskId>", "roundId": "<roundId>", "roundNo": 1, "title": "Thiết kế API",
  "assignee": { "userId": "<userAn>", "name": "An" }, "handedOverTo": null,
  "deadline": "2026-11-20T13:00:00Z", "difficulty": "HARD", "taskStatus": "SUBMITTED",
  "timeliness": "ON_TIME_PENDING_REVIEW", "timelinessLabel": "Nộp đúng hạn — chờ TL duyệt", "isLate": false,
  "submittedAt": "2026-11-20T12:30:00Z", "review": null, "completedAt": null, "acceptedAt": null, "completedLate": false,
  "reported": true, "declaredResult": "RESULT_SUBMITTED", "riskLevel": "ON_TRACK", "reportNote": null,
  "carryover": { "required": false, "reason": null, "targetRoundId": null } }
```
Lỗi: `404 NOT_FOUND` (userId không thuộc nhóm).

---

### E. Thông báo *(BE-A · `notification`)*

#### `GET /notifications`
**Vai trò:** RECIPIENT (AUTH) · **Màn hình:** S13, S01 · **Owner:** BE-A · **Idempotency:** —
Query `ListNotificationsQuery`: `groupId?`, `tab?` (`ACTION_REQUIRED`|`INFO`|`ALL`, mặc định `ALL`; `ACTION_REQUIRED` = ưu tiên `ACTION_REQUIRED`, `INFO` = còn lại), `unreadOnly?` (bool), `page`, `size`. Sắp: chưa đọc trước, rồi `createdAt` giảm dần. Response `200` `Page<NotificationItem>`:
```json
{ "items": [ {
  "id": "<notifId>", "type": "CHECKPOINT_REMINDER", "priority": "ACTION_REQUIRED",
  "groupId": "<groupId>", "groupName": "Bài tập lớn CSDL",
  "title": "Đến lúc cập nhật ngắn về công việc rồi!",
  "message": "Kỳ 3 chốt lúc 22:00 Thứ Sáu 20/11 (còn 7 giờ).", "subjectLabel": "Kỳ 3",
  "entityType": "CHECKPOINT_CYCLE", "entityId": "<cycleId3>", "cycleId": "<cycleId3>",
  "target": { "screen": "S06", "params": { "groupId": "<groupId>", "cycleId": "<cycleId3>" } },
  "isRead": false, "readAt": null, "createdAt": "2026-11-20T08:00:00Z" } ],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1 }
```

#### `GET /notifications/unread-count`
**Vai trò:** AUTH · **Màn hình:** header chuông, S01 · **Owner:** BE-A · **Idempotency:** —
Query `groupId?`. Response `200` `UnreadCountResponse`: `{ "total": 4, "actionRequired": 2, "byGroup": [ { "groupId": "<groupId>", "count": 4 } ] }`. FE poll/refetch (in-app MVP).

#### `PATCH /notifications/{notificationId}/read`
**Vai trò:** RECIPIENT · **Màn hình:** S13 · **Owner:** BE-A · **Idempotency:** — (tự nhiên)
Không body. Đánh dấu đã đọc (lặp lại không đổi). Response `200` `NotificationItem`. **Chỉ đổi trạng thái đọc**, không đồng nghĩa việc đã xử lý. Lỗi: `404 NOT_FOUND` (không phải của mình).

#### `POST /notifications/{notificationId}/open`
**Vai trò:** RECIPIENT · **Màn hình:** S13 (click thông báo) · **Owner:** BE-A · **Idempotency:** — (tự nhiên)
Không body. Đánh dấu đã đọc **và** kiểm tra lại quyền/sự tồn tại của đích. Response `200` `OpenNotificationResponse`:
```json
{ "notification": { "…": "NotificationItem, isRead=true" }, "available": false,
  "target": { "screen": "S05", "params": { "groupId": "<groupId>", "taskId": "<taskId>" } },
  "unavailableMessage": "Nội dung này không còn khả dụng",
  "fallback": { "screen": "S04", "params": { "groupId": "<groupId>" } } }
```
`available=false` → FE hiện `unavailableMessage` rồi đi `fallback` (danh sách tương ứng).

#### `POST /notifications/read-all`
**Vai trò:** RECIPIENT (AUTH) · **Màn hình:** S13 · **Owner:** BE-A · **Idempotency:** — (tự nhiên)
Request `MarkAllReadRequest`: `groupId?` (Uuid|null), `tab?` (`NotificationTab`). Response `200` `{ "updated": 7 }` (`MarkAllReadResponse`). Chỉ đổi trạng thái đọc.

---
### F. Task, kế hoạch, vòng xử lý *(BE-B · `task`)*

Một **task** có nhiều **vòng xử lý** (`TaskRound`); mỗi vòng có các **revision kế hoạch** (`PlanRevision`) và nhiều **lần nộp**. Nguồn deadline/phạm vi/độ khó chính thức = `effectivePlan` của vòng hiện tại; bản đang chờ duyệt **không** đổi deadline/dashboard (BR02). Hai nhãn độc lập: `planStatus` (kế hoạch) và `taskStatus` (thực hiện).

**`TaskSummary`** (danh sách/thẻ) — trường chính: `id, groupId, title, difficulty, originalAssignee, currentExecutor, cycle, roundId, roundNo, startAt, deadline, originalDeadline, committed, planStatus, pendingRevisionStatus, pendingRevisionId, taskStatus, riskStatus, timeliness, timelinessLabel, isLate, needsCarryover, carryoverReason, latestSubmittedAt, points (TaskPointsBrief|null), hasOpenHelpRequest, version, allowedActions`.
```json
{ "id": "<taskId>", "groupId": "<groupId>", "title": "Thiết kế API", "difficulty": "HARD",
  "originalAssignee": { "userId": "<userAn>", "name": "An" }, "currentExecutor": { "userId": "<userAn>", "name": "An" },
  "cycle": { "id": "<cycleId3>", "sequenceNo": 3, "periodStartAt": "2026-11-13T15:00:00Z", "closesAt": "2026-11-20T15:00:00Z",
             "label": "Kỳ 3 · 13/11 22:00 → 20/11 22:00" },
  "roundId": "<roundId>", "roundNo": 1, "startAt": "2026-11-14T01:00:00Z",
  "deadline": "2026-11-20T13:00:00Z", "originalDeadline": "2026-11-20T13:00:00Z", "committed": true,
  "planStatus": "APPROVED", "pendingRevisionStatus": null, "pendingRevisionId": null,
  "taskStatus": "SUBMITTED", "riskStatus": "NORMAL",
  "timeliness": "ON_TIME_PENDING_REVIEW", "timelinessLabel": "Nộp đúng hạn — chờ TL duyệt", "isLate": false,
  "needsCarryover": false, "carryoverReason": null, "latestSubmittedAt": "2026-11-20T12:30:00Z",
  "points": { "amount": { "pointUnits": 100000, "percentLabel": "10,0000" }, "status": "AWAITING_REVIEW",
              "originalAssignee": { "userId": "<userAn>", "name": "An" }, "currentRecipient": { "userId": "<userAn>", "name": "An" },
              "heldForHelpRequest": false, "awarded": false },
  "hasOpenHelpRequest": false, "version": 5, "allowedActions": ["VIEW_RESULT", "REPORT_CHECKPOINT", "REPORT_DIFFICULTY"] }
```
Ví dụ spec: nộp **19:30 VN** (`12:30Z`), deadline **20:00 VN** (`13:00Z`) → `isOnTime=true`; TL duyệt ngày sau vẫn là đúng hạn (4.1). `planStatus`: nếu vòng đã có bản hiệu lực → `APPROVED`; chưa có → trạng thái bản đang soạn/chờ. `pendingRevisionStatus`: bản sửa song song (≠ null chỉ khi đã có bản hiệu lực). `committed=false` → `deadline` chỉ là **dự kiến**.

**`TaskDetail`** = `TaskSummary` + `description`, `expectedOutput`, `effectivePlan`, `pendingRevision`, `currentRound`, `rounds[]`, `submissions[]` (vòng hiện tại), `acceptedSubmissionId`, `completedAt`, `acceptedAt`, `completedLate`, `independentReviewer`, `lastProgress`, `helpRequestId`, `checkpoint`.

#### `GET /groups/{groupId}/tasks`
**Vai trò:** MEMBER · **Màn hình:** S04, S03 (danh sách) · **Owner:** BE-B · **Idempotency:** —
Query `ListTasksQuery`:
| Tham số | Kiểu | Ghi chú |
|---|---|---|
| `tab` | `TaskListTab` | `ALL` (mặc định); `MINE` = `currentExecutor` là tôi; `PENDING_APPROVAL` = có revision `PENDING_APPROVAL`; `NEEDS_EDIT` = có revision `DRAFT`/`CHANGES_REQUESTED`; `NEEDS_ATTENTION` = `isLate` ∨ `riskStatus ∈ {AT_RISK,BLOCKED}` ∨ `needsCarryover` ∨ `taskStatus ∈ {FIX_REQUIRED,UPDATE_REQUIRED}` |
| `cycleId` | Uuid | "Tuần" = kỳ checkpoint (không phải tuần lịch) |
| `assigneeId` | Uuid | người thực hiện hiện tại |
| `planStatus` | `PlanStatus` | khớp `planStatus` **hoặc** `pendingRevisionStatus` |
| `taskStatus` | `TaskStatus` | |
| `difficulty` | `Difficulty` | |
| `deadlineFrom`, `deadlineTo` | IsoInstant | lọc `deadline` |
| `q` | string | tìm theo tiêu đề |
| `page`, `size` | int | |

Sắp: `deadline` tăng dần (null cuối), rồi `createdAt`. Response `200` `Page<TaskSummary>`.

#### `POST /groups/{groupId}/tasks`
**Vai trò:** LEAD · **Màn hình:** S04 (`+ Giao task`) · **Owner:** BE-B · **Idempotency:** RECOMMENDED
Tạo task + vòng 1 + revision #1 `DRAFT` (`approvalSource = MEMBER_PLAN`); phát `TASK_ASSIGNED`. Request `CreateTaskRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `title` | string | ✔ | 3–120 |
| `assigneeId` | Uuid | ✔ | thành viên đang hoạt động (có thể là TL) |
| `cycleId` | Uuid | ✔ | kỳ ("tuần") chưa đóng; cùng nhóm |
| `description` | string | ✔ | phạm vi, 1–4000 |
| `expectedOutput` | string | ✔ | 1–2000 |
| `startAt` | IsoInstant | ✔ | ≤ `deadline` |
| `deadline` | IsoInstant | ✔ | `periodStartAt < deadline <= closesAt` của kỳ; ≤ cuối ngày `Group.deadline` |
| `difficulty` | `Difficulty` | ✔ | `EASY`/`MEDIUM`/`HARD` |

Response `201` `TaskDetail` (`planStatus=DRAFT`, `committed=false`).
Lỗi: `400 VALIDATION_FAILED`, `403 LEAD_ONLY`, `409 CYCLE_CLOSED`, `422 ASSIGNEE_NOT_IN_GROUP | START_AFTER_DEADLINE | DEADLINE_OUTSIDE_CYCLE | DEADLINE_AFTER_GROUP_DEADLINE`.

#### `GET /tasks/{taskId}`
**Vai trò:** MEMBER (của nhóm chứa task) · **Màn hình:** S05 (và S06/S10/S11 tham chiếu) · **Owner:** BE-B · **Idempotency:** —
Response `200` `TaskDetail`. Phần chính:
```json
{ "…": "TaskSummary",
  "description": "API cho module nhóm và task", "expectedOutput": "OpenAPI + 12 endpoint chạy được",
  "effectivePlan": { "id": "<planId1>", "revisionNo": 1, "status": "APPROVED", "isEffective": true,
                     "approvalSource": "MEMBER_PLAN", "deadline": "2026-11-20T13:00:00Z", "difficulty": "HARD",
                     "reviewedBy": { "userId": "<userMinh>", "name": "Minh" }, "reviewedAt": "2026-11-14T02:00:00Z", "changes": [], "…": "PlanRevision" },
  "pendingRevision": { "id": "<planId2>", "revisionNo": 2, "status": "PENDING_APPROVAL", "baseRevisionId": "<planId1>",
                       "changeReason": "Cần thêm 1 ngày do phụ thuộc dữ liệu",
                       "changes": [ { "field": "deadline", "oldValue": "2026-11-20T13:00:00Z", "newValue": "2026-11-21T13:00:00Z",
                                      "oldLabel": "20:00 Thứ Sáu 20/11", "newLabel": "20:00 Thứ Bảy 21/11" } ], "…": "PlanRevision" },
  "currentRound": { "id": "<roundId>", "taskId": "<taskId>", "roundNo": 1, "reason": "INITIAL", "state": "ACTIVE",
                    "cycle": { "…": "CycleRef" }, "previousRoundId": null, "effectivePlanId": "<planId1>",
                    "deadline": "2026-11-20T13:00:00Z", "decision": null, "startedAt": "2026-11-14T02:00:00Z",
                    "closedAt": null, "carryover": null },
  "rounds": [ { "…": "TaskRound" } ],
  "submissions": [ { "id": "<submissionId>", "roundId": "<roundId>", "taskId": "<taskId>", "planRevisionId": "<planId1>",
                     "submissionNo": 1, "submittedBy": { "userId": "<userAn>", "name": "An" },
                     "resultNote": "Đã xong 12 endpoint", "evidence": [ { "type": "URL", "url": "https://github.com/x/y/pull/7", "label": "PR #7" } ],
                     "submittedAt": "2026-11-20T12:30:00Z", "deadline": "2026-11-20T13:00:00Z", "isOnTime": true,
                     "isReviewTarget": true, "review": null, "supportSubmissionId": null } ],
  "acceptedSubmissionId": null, "completedAt": null, "acceptedAt": null, "completedLate": false,
  "independentReviewer": null, "lastProgress": null, "helpRequestId": null,
  "checkpoint": { "cycle": { "…": "CycleRef" }, "myReportState": "NOT_RESPONDED", "reportWindowOpen": true } }
```
S05 hiển thị riêng `submittedAt`, `acceptedAt`, `originalDeadline` và `deadline` vòng hiện tại. Task chuyển cho Helper: `originalAssignee` ≠ `currentExecutor`; `points.currentRecipient` = Helper. Lỗi: `404 NOT_FOUND`.

#### `GET /tasks/{taskId}/history`
**Vai trò:** MEMBER · **Màn hình:** S05 ("Xem lịch sử") · **Owner:** BE-B · **Idempotency:** —
Query `page`,`size`. Mới nhất trước. Response `200` `Page<TaskHistoryEntry>`:
```json
{ "items": [ { "id": "<id>", "at": "2026-11-20T12:30:00Z", "actor": { "userId": "<userAn>", "name": "An" },
               "kind": "RESULT_SUBMITTED", "roundNo": 1, "summary": "An nộp kết quả lần 1 (đúng hạn)", "refId": "<submissionId>" } ],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1 }
```
Nội dung hỗ trợ riêng không xuất hiện ở đây.

#### `PATCH /plans/{planId}`
**Vai trò:** ASSIGNEE của revision (status `DRAFT` hoặc `CHANGES_REQUESTED`); LEAD (chỉ khi `DRAFT`) · **Màn hình:** S05 (`Sửa kế hoạch`, `Lưu nháp`) · **Owner:** BE-B · **Idempotency:** — (kiểm tra `version`)
Request `UpdatePlanRequest` (chỉ gửi trường đổi): `title?`, `description?`, `expectedOutput?`, `cycleId?`, `assigneeId?` (chỉ LEAD, chỉ khi chưa có bản hiệu lực), `startAt?`, `deadline?`, `difficulty?`, `version` ✔. Không đổi trạng thái (vẫn `DRAFT`/`CHANGES_REQUESTED`). Response `200` `PlanMutationResponse` = `{ plan: PlanRevision, task: TaskSummary }`.
Lỗi: `409 PLAN_NOT_EDITABLE`, `409 VERSION_CONFLICT`, `403 NOT_ASSIGNEE`, `422 START_AFTER_DEADLINE | DEADLINE_OUTSIDE_CYCLE | DEADLINE_AFTER_GROUP_DEADLINE | ASSIGNEE_NOT_IN_GROUP`.

#### `POST /plans/{planId}/submit`
**Vai trò:** ASSIGNEE của revision (xác nhận cam kết của chính mình) · **Màn hình:** S05 (`Gửi TL duyệt`) · **Owner:** BE-B · **Idempotency:** RECOMMENDED
Request `VersionedRequest`: `{ "version": 3 }`. `DRAFT`/`CHANGES_REQUESTED` → `PENDING_APPROVAL`, ghi `submittedBy/At`; phát `PLAN_SUBMITTED` (lần đầu) hoặc `PLAN_CHANGE_SUBMITTED` (bản sửa của kế hoạch đã duyệt) cho LEAD (không tự thông báo khi người gửi chính là LEAD). Response `200` `PlanMutationResponse`.
Lỗi: `403 NOT_ASSIGNEE`, `409 INVALID_PLAN_STATE`, `409 VERSION_CONFLICT`, `422 PLAN_INCOMPLETE` (thiếu người phụ trách/kỳ/phạm vi/đầu ra/giờ/độ khó — `fieldErrors` chỉ rõ trường).

#### `POST /plans/{planId}/withdraw`
**Vai trò:** người đã gửi (ASSIGNEE) · **Màn hình:** S05 · **Owner:** BE-B · **Idempotency:** — (tự nhiên)
Request `VersionedRequest`. `PENDING_APPROVAL` → `DRAFT` (có audit) để sửa tiếp. Response `200` `PlanMutationResponse`. Lỗi: `409 INVALID_PLAN_STATE`, `409 VERSION_CONFLICT`, `403 NOT_ASSIGNEE`.

#### `POST /plans/{planId}/review`
**Vai trò:** LEAD · **Màn hình:** S05 (`Duyệt kế hoạch`/`Yêu cầu chỉnh sửa`) · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Request `ReviewPlanRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `decision` | `"APPROVED"`\|`"CHANGES_REQUESTED"` | ✔ | |
| `note` | string\|null | khi `CHANGES_REQUESTED` | 5–1000 (lý do trả lại) |
| `version` | number | ✔ | version của revision |

Một transaction: khóa vòng → kiểm tra `version` + bản nền còn hiệu lực → `APPROVED` (đặt `effectivePlanId`, `isEffective`, round `ACTIVE`; nếu đổi kỳ khi kỳ nguồn còn mở thì cập nhật `cycle` của vòng) hoặc `CHANGES_REQUESTED` → audit + `PLAN_APPROVED`/`PLAN_CHANGES_REQUESTED` cho người phụ trách. Bản cũ vẫn hiệu lực cho đến lúc duyệt bản mới. Response `200` `ReviewPlanResponse` = `{ plan, task }`.
Lỗi: `403 LEAD_ONLY`, `409 INVALID_PLAN_STATE` (không còn `PENDING_APPROVAL`; lặp lại cùng quyết định → 200 replay), `409 VERSION_CONFLICT`, `409 CYCLE_CLOSED` (đổi kỳ ngược vào kỳ đã đóng), `400 VALIDATION_FAILED` (thiếu `note`). Người ngoài nhóm → `404 NOT_A_GROUP_MEMBER`.

#### `POST /tasks/{taskId}/plan-revisions`
**Vai trò:** ASSIGNEE (người thực hiện hiện tại) · **Màn hình:** S05 (`Đề xuất thay đổi`) · **Owner:** BE-B · **Idempotency:** RECOMMENDED
Tạo revision mới từ bản hiệu lực khi cần đổi tuần/phạm vi/đầu ra/người phụ trách/mốc thời gian/độ khó (BR02). Request `ProposeChangeRequest`: `changeReason` (5–1000 ✔), các trường đề xuất tùy chọn (`title, description, expectedOutput, cycleId, assigneeId, startAt, deadline, difficulty`; phải đổi ít nhất một), `submitNow?` (true → gửi TL luôn, phát `PLAN_CHANGE_SUBMITTED`). Response `201` `PlanMutationResponse` (`status=DRAFT` hoặc `PENDING_APPROVAL`; `changes[]` có so sánh cũ/mới). Bản hiệu lực **giữ nguyên** trong lúc chờ.
Lỗi: `403 NOT_ASSIGNEE`, `409 NO_EFFECTIVE_PLAN`, `409 REVISION_ALREADY_IN_FLIGHT` (tối đa một bản đang soạn/chờ mỗi vòng), `409 CYCLE_CLOSED` (kỳ của vòng đã đóng → dùng chuyển kỳ), `422 NO_CHANGES_PROPOSED | DEADLINE_OUTSIDE_CYCLE | DEADLINE_AFTER_GROUP_DEADLINE | START_AFTER_DEADLINE | ASSIGNEE_NOT_IN_GROUP`.

#### `POST /tasks/{taskId}/start`
**Vai trò:** ASSIGNEE · **Màn hình:** S05 (`Bắt đầu`) · **Owner:** BE-B · **Idempotency:** — (tự nhiên)
Request `VersionedRequest`. `TODO` → `IN_PROGRESS` (đã `IN_PROGRESS` → 200 không đổi). Response `200` `TaskSummary`. Lỗi: `409 NO_EFFECTIVE_PLAN`, `409 INVALID_STATE` (đã `SUBMITTED`/`DONE`/…), `409 VERSION_CONFLICT`, `403 NOT_ASSIGNEE`.

#### `POST /tasks/{taskId}/progress`
**Vai trò:** ASSIGNEE · **Màn hình:** S05 (`Cập nhật tiến độ`) · **Owner:** BE-B · **Idempotency:** —
Ghi một dòng tiến độ; **không** đổi deadline/cam kết (BR04). Request `ProgressUpdateRequest`: `note` (1–1000 ✔), `riskStatus?` (`RiskStatus`, cập nhật `task.riskStatus`), `expectedCompletionAt?` (chỉ thông tin). Response `201` `ProgressNote`:
```json
{ "id": "<id>", "taskId": "<taskId>", "by": { "userId": "<userAn>", "name": "An" }, "note": "Đang chờ dữ liệu mẫu",
  "riskStatus": "AT_RISK", "expectedCompletionAt": "2026-11-21T13:00:00Z", "createdAt": "2026-11-18T03:00:00Z" }
```
Lỗi: `403 NOT_ASSIGNEE`, `409 NO_EFFECTIVE_PLAN`, `400 VALIDATION_FAILED`.

#### `PUT /tasks/{taskId}/independent-reviewer`
**Vai trò:** LEAD · **Màn hình:** S05 (cài đặt trên task của TL) · **Owner:** BE-B · **Idempotency:** — (PUT)
Chỉ định (hoặc bỏ với `null`) người duyệt độc lập cho **task do chính LEAD thực hiện** (12.8.7.6); chỉ hiệu lực đúng task đó, có audit, phải chỉ định **trước** khi duyệt. Request `SetIndependentReviewerRequest`: `{ "reviewerUserId": "<userBinh>" }` (thành viên đang hoạt động, khác LEAD). Response `200` `TaskDetail`.
Lỗi: `403 LEAD_ONLY`, `400 VALIDATION_FAILED` (người thực hiện không phải LEAD / reviewer không hợp lệ), `409 TASK_ALREADY_DONE`.

---

### G. Nộp minh chứng & quyết định TL, chuyển kỳ *(BE-B · `task`)*

#### `POST /task-rounds/{roundId}/submissions`
**Vai trò:** ASSIGNEE (người thực hiện hiện tại trong kế hoạch/bàn giao hiệu lực) · **Màn hình:** S05 (`Gửi kết quả`), S06 (nộp trước khi xác nhận) · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Nộp minh chứng → task `SUBMITTED` (**không** `DONE`). Cho nộp **nhiều lần** trước `closesAt` của kỳ chứa vòng (mọi bản cũ được giữ; bản mới nhất là bản TL xét). Request `SubmitResultRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `planRevisionId` | Uuid | ✔ | phải đúng bản hiệu lực hiện tại (lệch → `409 PLAN_CHANGED`) |
| `resultNote` | string\|null | | ≤2000 |
| `evidence` | `EvidenceItem[]` | ✔ | 1–5 mục `{type:"URL", url (https, ≤2048), label?}` (khớp `evidence jsonb` ở DB) |

`submittedAt` do server cấp sau khi lấy khóa kỳ; `isOnTime = submittedAt <= deadline`. Response `201` `SubmitResultResponse` = `{ submission: TaskSubmission, task: TaskSummary }`.
Lỗi: `403 NOT_ASSIGNEE`, `409 ROUND_NOT_ACTIVE`, `409 NO_EFFECTIVE_PLAN`, `409 PLAN_CHANGED`, `409 SUBMISSION_WINDOW_CLOSED` (đã quá `closesAt`; chờ TL chốt vòng sau), `422 EVIDENCE_REQUIRED`, `400 VALIDATION_FAILED` (URL sai).

#### `GET /task-rounds/{roundId}/submissions`
**Vai trò:** MEMBER · **Màn hình:** S05 (lịch sử vòng cũ), S11 · **Owner:** BE-B · **Idempotency:** —
Mọi lần nộp của vòng (cũ → mới). Response `200` `ItemsResponse<TaskSubmission>`.

#### `GET /task-submissions/{submissionId}/review-preview`
**Vai trò:** MEMBER (xem); `canReview` chỉ đúng cho REVIEWER · **Màn hình:** S05, S11 (preview "Điểm sẽ ghi") · **Owner:** BE-B · **Idempotency:** —
Response `200` `ReviewPreviewResponse`:
```json
{ "submission": { "…": "TaskSubmission (isReviewTarget=true)" }, "task": { "…": "TaskSummary" },
  "cycle": { "…": "CycleRef Kỳ 3" }, "cutoffAt": "2026-11-20T15:00:00Z",
  "canReview": false,
  "cannotReviewReason": { "code": "REVIEW_BEFORE_CUTOFF", "message": "Chưa đến giờ chốt của kỳ — TL duyệt sau 22:00 Thứ Sáu 20/11." },
  "authorizedReviewer": { "userId": "<userMinh>", "name": "Minh" }, "alreadyReviewed": false,
  "awardPreview": {
    "recipient": { "userId": "<userBinh>", "name": "Bình" }, "originalAssignee": { "userId": "<userAn>", "name": "An" },
    "taskTitle": "Thiết kế API",
    "points": { "pointUnits": 100000, "percentLabel": "+10,0000" },
    "confirmedBefore": { "pointUnits": 200000, "percentLabel": "20,0000" },
    "confirmedAfter": { "pointUnits": 300000, "percentLabel": "30,0000" },
    "scoreLabel": "+10,0000 điểm % cho Bình từ task Thiết kế API của An",
    "effectLabel": "An không nhận điểm task này; Bình nhận toàn bộ quỹ của task",
    "notice": "Điểm chỉ được ghi sau khi kết quả được chấp nhận" },
  "supportWork": { "…": "SupportWorkSummary|null" } }
```
`awardPreview = null` nếu task không có điểm. Số "sau" là **dự kiến**, lấy tổng ledger thật (không cộng dự kiến như điểm đã có). Lỗi: `404 NOT_FOUND`.

#### `POST /task-submissions/{submissionId}/review`
**Vai trò:** REVIEWER (§4.4) · **Màn hình:** S05 (TL), S11 · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Đường **duy nhất** để duyệt kết quả (cả S05 lẫn S11 — task thường và task làm thay), nên hai đường UI không thể cấp hai khoản điểm. Request `ReviewResultRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `decision` | `TaskReviewDecision` | ✔ | `ACCEPTED` \| `FIX_REQUIRED` \| `UPDATE_REQUIRED` |
| `issueDescription` | string\|null | khi ≠ `ACCEPTED` | 10–2000: mô tả lỗi/yêu cầu |

Điều kiện server: `now > closesAt` của kỳ chứa vòng; submission là **bản nộp chốt** (mới nhất ≤ cutoff); quyền duyệt theo §4.4.
- `ACCEPTED`: **một transaction** — review `ACCEPTED`, task `DONE`, round `ACCEPTED`, `completedAt = submittedAt` (bản được chấp nhận), `acceptedAt = reviewedAt`; nếu task có điểm: settlement + **đúng một** `AWARD +P_t` cho người nhận theo assignment `CURRENT`; nếu bản nộp thuộc SupportWork: work `APPROVED`, help request `COMPLETED`, contribution `CONFIRMED`; audit; `POINTS_AWARDED` (hoặc `CONTRIBUTION_CONFIRMED` nếu không có điểm) + `TASK_REVIEWED`. Lỗi giữa chừng → rollback toàn bộ.
- `FIX_REQUIRED`/`UPDATE_REQUIRED`: lưu quyết định + mô tả, task `FIX_REQUIRED`/`UPDATE_REQUIRED`, round `CARRYOVER_REQUIRED`, **không** ghi điểm, không xóa bản nộp cũ; `TASK_REVIEWED` (Member) hoặc `REVISION_REQUESTED` (Helper; work `REVISION_REQUESTED`). TL tiếp tục `POST /task-rounds/{roundId}/carryover`.

Response `200` `ReviewResultResponse`:
```json
{ "review": { "id": "<reviewId>", "submissionId": "<submissionId>", "decision": "ACCEPTED",
              "reviewer": { "userId": "<userMinh>", "name": "Minh" }, "issueDescription": null, "reviewedAt": "2026-11-21T02:00:00Z" },
  "submission": { "…": "TaskSubmission, isOnTime=true" }, "task": { "…": "TaskSummary, taskStatus=DONE, timeliness=ON_TIME_ACCEPTED" },
  "award": { "ledgerEntryId": "<ledgerId>", "settlementId": "<settlementId>", "recipient": { "userId": "<userAn>", "name": "An" },
             "taskId": "<taskId>", "points": { "pointUnits": 100000, "percentLabel": "+10,0000" },
             "confirmedBefore": { "pointUnits": 0, "percentLabel": "0,0000" },
             "confirmedAfter": { "pointUnits": 100000, "percentLabel": "10,0000" }, "source": "OWN" },
  "supportWork": null }
```
Lặp cùng `decision` trên bản đã duyệt → `200` kết quả hiện có (`Idempotent-Replayed: true` nếu cùng khóa). Lỗi: `403 NOT_AUTHORIZED_REVIEWER`, `403 SELF_ACTION_FORBIDDEN`, `409 REVIEW_BEFORE_CUTOFF`, `409 STALE_SUBMISSION` (có bản nộp mới hơn/quyền nhận vừa đổi → tải lại preview), `409 SUBMISSION_ALREADY_REVIEWED` (khác quyết định), `409 INDEPENDENT_REVIEWER_REQUIRED`, `409 HELP_REQUEST_BLOCKS_REVIEW` (còn yêu cầu hỗ trợ `OPEN` giữ quỹ task — TL đóng yêu cầu trước, 12.8.4), `400 VALIDATION_FAILED` (thiếu `issueDescription`).

#### `POST /task-rounds/{roundId}/carryover`
**Vai trò:** LEAD · **Màn hình:** S05 (`Chốt deadline xử lý tiếp`), S03 (hàng đợi) · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Chuyển **chỉ** task đã bị `FIX_REQUIRED`/`UPDATE_REQUIRED` hoặc **thiếu kết quả** khi đóng kỳ sang một vòng mới cùng `taskId`, liên kết vòng cũ (BR18). Request `CarryoverRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `targetCycleId` | Uuid | ✔ | kỳ **sau** kỳ nguồn, chưa đóng, cùng nhóm |
| `deadline` | IsoInstant | ✔ | `> now` và `periodStartAt < deadline <= closesAt` của kỳ đích |
| `startAt` | IsoInstant\|null | | mặc định `max(now, periodStartAt)` |
| `scopeNote` | string\|null | | ≤2000, phạm vi sửa/cập nhật |
| `decisionReason` | string\|null | **bắt buộc (≥5)** nếu kỳ đích không phải kỳ liền sau | ≤1000 |

Một transaction: tạo round đích (`reason` = quyết định nguồn hoặc `NOT_SUBMITTED`) + revision `APPROVED` `approvalSource = TL_CARRYOVER` (giữ người thực hiện & độ khó; `P_t` **không đổi**), đóng nguồn `CARRIED_OVER`, ghi `task_carryovers`; task trở lại `IN_PROGRESS` ở vòng mới; phát `TASK_CARRIED_OVER`; kỳ nguồn tự `FINALIZED` khi mọi task đã có quyết định và việc cần chuyển đã có deadline. **Task chỉ chờ TL duyệt không được chuyển** (không tự chuyển, không bị tính lỗi do TL chậm). Response `201` `CarryoverResponse` = `{ sourceRound, targetRound, plan, task }`.
Lỗi: `403 LEAD_ONLY`, `409 CARRYOVER_NOT_ALLOWED` (kỳ nguồn chưa đóng / task đã `DONE`), `409 CARRYOVER_AWAITING_REVIEW`, `409 CARRYOVER_ALREADY_DONE` (khác tham số; cùng tham số → 200 replay), `422 CARRYOVER_DEADLINE_INVALID`, `422 CARRYOVER_REASON_REQUIRED`.

#### `GET /groups/{groupId}/checkpoint-cycles/{cycleId}/review-queue`
**Vai trò:** LEAD (và người duyệt độc lập — chỉ thấy task được chỉ định) · **Màn hình:** S03 (khu TL kiểm tra), S05 · **Owner:** BE-B · **Idempotency:** —
Hàng đợi sau checkpoint. Response `200` `ReviewQueueResponse`:
```json
{ "cycle": { "…": "CheckpointCycle" }, "serverTime": "2026-11-20T15:05:00Z", "canReviewNow": true,
  "awaitingReview":   [ { "row": { "…": "CycleTaskRow" }, "submission": { "…": "TaskSubmission" }, "canReviewNow": true } ],
  "carryoverRequired":[ { "row": { "…": "CycleTaskRow, carryover.required=true" }, "submission": null, "canReviewNow": false } ],
  "reviewed":         [ { "row": { "…": "CycleTaskRow" }, "submission": { "…": "TaskSubmission" }, "canReviewNow": false } ] }
```
`awaitingReview` = bản nộp chốt chưa có quyết định (giữ `SUBMITTED`, thuộc hàng đợi **kỳ cũ**); `carryoverRequired` = cần TL chốt deadline ("Cần TL chốt lịch xử lý tiếp"). Lỗi: `403 LEAD_ONLY`.

---

### H. Báo cáo checkpoint hàng loạt *(BE-B · `task`)*

Phạm vi bắt buộc của một Member trong kỳ = **mọi** task có kế hoạch đã duyệt, người thực hiện = tôi, `deadline ∈ (periodStartAt, closesAt]`, **kể cả task đã nộp/hoàn thành** (điền sẵn minh chứng). Tồn đọng chưa được TL chốt deadline mới và task chỉ chờ duyệt ở kỳ trước hiện ở `backlog` (không bắt buộc, không thành cam kết kỳ này). Khung nhập: `opensAt <= now <= closesAt`; sau `closesAt` bản gửi bị khóa (không sửa ngược). Một bản báo cáo có nhiều revision; bản `SUBMITTED` bất biến; gửi lại trước cutoff tạo revision mới.

#### `GET /groups/{groupId}/checkpoint-cycles/{cycleId}/my-report`
**Vai trò:** MEMBER (kể cả TL; của chính mình) · **Màn hình:** S06, S03 (số liệu) · **Owner:** BE-B · **Idempotency:** —
Response `200` `MyReportResponse` (điền sẵn bởi server):
```json
{ "serverTime": "2026-11-18T03:00:00Z", "cycle": { "…": "CheckpointCycle Kỳ 3" },
  "state": "NOT_RESPONDED", "revisionNo": null, "status": null, "submittedAt": null,
  "canEdit": true, "lockReason": null,
  "items": [ { "taskId": "<taskId>", "roundId": "<roundId>", "roundNo": 1, "title": "Thiết kế API",
               "expectedOutput": "OpenAPI + 12 endpoint chạy được", "deadline": "2026-11-20T13:00:00Z", "difficulty": "HARD",
               "taskStatus": "SUBMITTED", "timeliness": "ON_TIME_PENDING_REVIEW", "timelinessLabel": "Nộp đúng hạn — chờ TL duyệt", "isLate": false,
               "submission": { "id": "<submissionId>", "submissionNo": 1, "submittedAt": "2026-11-18T02:30:00Z", "isOnTime": true,
                               "evidence": [ { "type": "URL", "url": "https://github.com/x/y/pull/7", "label": "PR #7" } ], "reviewDecision": null },
               "declaredResult": "RESULT_SUBMITTED", "prefilled": true, "riskLevel": "ON_TRACK", "note": null, "expectedCompletionAt": null } ],
  "backlog": [ { "taskId": "<taskId9>", "roundId": "<roundId9>", "title": "Viết báo cáo", "fromCycle": { "…": "CycleRef Kỳ 2" },
                 "state": "CARRYOVER_REQUIRED", "message": "Cần TL chốt lịch xử lý tiếp" } ],
  "completeness": { "requiredCount": 3, "respondedCount": 0, "missingTaskIds": [ "<taskId>", "<taskId2>", "<taskId3>" ], "isComplete": false },
  "submittedLabel": null, "version": null }
```
`prefilled=true` = server điền (task đã nộp thật → `RESULT_SUBMITTED` + `ON_TRACK`), chưa tính là Member đã phản hồi cho tới khi lưu/gửi. `submittedLabel = "Đã gửi đầy đủ"` chỉ khi đã `SUBMITTED` và `completeness.isComplete`. Trước `opensAt` → `canEdit=false`, `lockReason.code = REPORT_WINDOW_NOT_OPEN`; sau `closesAt` → `CYCLE_CLOSED` (trả bản đã chốt, chỉ đọc). Kỳ không có task bắt buộc → `items=[]` (FE giải thích, không mở form rỗng). Lỗi: `404 NOT_FOUND`.

#### `PUT /groups/{groupId}/checkpoint-cycles/{cycleId}/my-report`
**Vai trò:** MEMBER (của chính mình) · **Màn hình:** S06 (`Lưu nháp`) · **Owner:** BE-B · **Idempotency:** — (ghi đè nháp, kiểm tra `version`)
Lưu bản **nháp** (không cần đủ task). Request `SaveReportRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `items[].taskId` | Uuid | ✔ | thuộc phạm vi bắt buộc của tôi trong kỳ |
| `items[].declaredResult` | `CheckpointTaskResult` | ✔ | `RESULT_SUBMITTED` chỉ khi **có lần nộp thật**; `RESULT_MISSING` chỉ khi **chưa** có |
| `items[].riskLevel` | `ReportRiskLevel` | ✔ | `ON_TRACK`/`AT_RISK`/`NEEDS_LEAD_HELP` |
| `items[].note` | string\|null | | ≤1000 (tiến độ/trở ngại) |
| `items[].expectedCompletionAt` | IsoInstant\|null | | chỉ thông tin, **không đổi deadline** |
| `version` | number\|null | ✔ | từ `MyReportResponse.version` (null nếu chưa có báo cáo) |

Response `200` `MyReportResponse` (`status=DRAFT`). Lỗi: `409 REPORT_WINDOW_NOT_OPEN`, `409 CYCLE_CLOSED`, `409 VERSION_CONFLICT`, `422 RESULT_DECLARATION_MISMATCH`, `400 VALIDATION_FAILED`. `NEEDS_LEAD_HELP` không tự đăng công khai; FE gợi ý mở S07.

#### `POST /groups/{groupId}/checkpoint-cycles/{cycleId}/my-report/submit`
**Vai trò:** MEMBER (của chính mình) · **Màn hình:** S06 (`Gửi báo cáo tuần`) · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Request `SubmitReportRequest`: `items?` (như trên; nếu có → lưu rồi gửi trong một thao tác, hỗ trợ **xác nhận hàng loạt**: FE chỉ cần gửi các item đã điền sẵn), `version` (number|null ✔). Điều kiện: **mọi task bắt buộc** đều có item hợp lệ. Thành công: bản `SUBMITTED` bất biến, `submittedAt` do server cấp, cập nhật số liệu dashboard. Response `200` `MyReportResponse` (`state=SUBMITTED`, `submittedLabel="Đã gửi đầy đủ"`).
Lỗi: `422 REPORT_INCOMPLETE` (nháp vẫn được lưu; `fieldErrors` = `items[<taskId>]` còn thiếu), `422 RESULT_DECLARATION_MISMATCH`, `409 REPORT_WINDOW_NOT_OPEN`, `409 CYCLE_CLOSED`, `409 VERSION_CONFLICT`.
**Lưu ý:** thiếu báo cáo ≠ task trễ (task đã nộp thật mà quên báo cáo hiện `Thiếu báo cáo` + `Có kết quả, chờ TL duyệt`, không bị đổi thành "chưa nộp").

---
### I. Hỗ trợ riêng & risk *(BE-B · `support`)*

`support_requests` = đề nghị **riêng Member → TL**; chỉ `SENDER` và `LEAD` đọc được. Tạo đề nghị + `NEED_HELP` cho TL **cùng transaction**. Dùng bất kỳ lúc nào, kể cả khi vòng đã khóa (không sửa báo cáo cũ).

#### `POST /tasks/{taskId}/support-requests`
**Vai trò:** ASSIGNEE (người thực hiện hiện tại; LEAD không gửi cho chính mình) · **Màn hình:** S07 (Member), S06 (`Cần TL hỗ trợ`) · **Owner:** BE-B · **Idempotency:** RECOMMENDED
Request `CreateSupportRequestRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `obstacleType` | `ObstacleType` | ✔ | `TECHNICAL`/`TIME`/`SCOPE`/`SKILL`/`PERSONAL`/`OTHER` |
| `description` | string | ✔ | 10–2000 |
| `neededSupport` | string | ✔ | 5–1000 (phần cần hỗ trợ) |
| `impact` | string\|null | | ≤1000 |
| `expectedDate` | IsoDate\|null | | ngày dự kiến nếu biết; **không** đổi deadline |
| `roundId` | Uuid\|null | | vòng đang làm (ngữ cảnh) |

Response `201` `SupportRequest`:
```json
{ "id": "<supportRequestId>", "taskId": "<taskId>", "taskTitle": "Thiết kế API",
  "sender": { "userId": "<userAn>", "name": "An" }, "obstacleType": "TECHNICAL", "status": "OPEN",
  "createdAt": "2026-11-18T03:00:00Z", "updatedAt": "2026-11-18T03:00:00Z",
  "groupId": "<groupId>", "roundId": "<roundId>", "description": "Chưa kết nối được DB test",
  "neededSupport": "Cần người xem giúp cấu hình", "impact": "Có thể trễ 1 ngày", "expectedDate": "2026-11-21",
  "decision": null, "leadResponse": null, "reviewedBy": null, "reviewedAt": null,
  "publishedHelpRequestIds": [], "version": 0, "allowedActions": [] }
```
Phát `NEED_HELP` (chỉ LEAD). Lỗi: `403 NOT_ASSIGNEE`, `403 SELF_ACTION_FORBIDDEN` (người gửi là LEAD), `400 VALIDATION_FAILED`.

#### `GET /groups/{groupId}/support-requests`
**Vai trò:** MEMBER — Member chỉ thấy **của mình** (`scope` bị ép `MINE`); LEAD thấy tất cả (`scope` mặc định `ALL`) · **Màn hình:** S07 (theo dõi), S03 (hàng đợi TL) · **Owner:** BE-B · **Idempotency:** —
Query `ListSupportRequestsQuery`: `status?`, `taskId?`, `scope?` (`ALL`|`MINE`), `page`, `size`. Sắp `createdAt` giảm dần. Response `200` `Page<SupportRequestSummary>` (`id, taskId, taskTitle, sender, obstacleType, status, createdAt, updatedAt`).

#### `GET /support-requests/{supportRequestId}`
**Vai trò:** SENDER hoặc LEAD · **Màn hình:** S07 · **Owner:** BE-B · **Idempotency:** —
Response `200` `SupportRequest` (như trên). Người khác → `403 FORBIDDEN`. `allowedActions` cho LEAD: `["DECIDE","PUBLISH_HELP"]` khi chưa `RESOLVED`. Lỗi: `404 NOT_FOUND`.

#### `POST /support-requests/{supportRequestId}/decision`
**Vai trò:** LEAD · **Màn hình:** S07 (chế độ TL) · **Owner:** BE-B · **Idempotency:** RECOMMENDED
Request `SupportRequestDecisionRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `action` | `SupportRequestLeadAction` | ✔ | `RESOLVED_PRIVATELY` ("Đã xử lý, không cần đăng hỗ trợ") → status `RESOLVED`, `decision=RESOLVED_PRIVATELY`; `NEED_MORE_DISCUSSION` ("Cần trao đổi thêm") → status `IN_REVIEW` |
| `response` | string | ✔ | 1–2000, người gửi đọc được |
| `version` | number | ✔ | |

Chọn "Đăng task cần người làm thay" **không** gọi endpoint này: TL mở form S08 và gọi `POST /groups/{groupId}/help-requests` với `supportRequestId` (server đặt đề nghị `RESOLVED` + `decision=HELP_PUBLISHED`, ghi liên kết). Phát `SUPPORT_DECISION` tới người gửi. Response `200` `SupportRequest`.
Lỗi: `403 LEAD_ONLY`, `409 SUPPORT_REQUEST_CLOSED` (đã `RESOLVED`), `409 VERSION_CONFLICT`.

#### `GET /groups/{groupId}/risk-reports`
**Vai trò:** MEMBER · **Màn hình:** S03 ("Việc có thể ảnh hưởng kế hoạch"), S05 · **Owner:** BE-B · **Idempotency:** —
Query `ListRiskReportsQuery`: `status?`, `taskId?`, `page`, `size`. Chỉ trường **chia sẻ được** (`publicSummary`); không lộ nội dung hỗ trợ riêng. Response `200` `Page<RiskReport>`:
```json
{ "items": [ { "id": "<riskId>", "groupId": "<groupId>", "taskId": "<taskId>", "taskTitle": "Thiết kế API",
  "subject": { "userId": "<userAn>", "name": "An" }, "reportedBy": { "userId": "<userAn>", "name": "An" },
  "riskType": "DELAY_RISK", "publicSummary": "Phần API có thể cần thêm 1 ngày", "status": "OPEN",
  "confirmedBy": null, "confirmedAt": null, "resolvedAt": null, "affectedTaskIds": [ "<taskId2>" ],
  "createdAt": "2026-11-17T09:00:00Z", "version": 0 } ], "page": 0, "size": 20, "totalItems": 1, "totalPages": 1 }
```

#### `POST /groups/{groupId}/risk-reports`
**Vai trò:** `DELAY_RISK`/`BLOCKED`: ASSIGNEE của task hoặc LEAD · `DROPOUT_RISK`: **LEAD** · **Màn hình:** S05/S07/S03 (TL ghi nguy cơ) · **Owner:** BE-B · **Idempotency:** RECOMMENDED
Request `CreateRiskReportRequest`: `riskType` (`RiskType` ✔), `taskId?`, `subjectUserId?` (mặc định người thực hiện của task; **bắt buộc** nếu không có `taskId` hoặc `riskType=DROPOUT_RISK`), `publicSummary` (5–500 ✔, không chứa nội dung riêng), `affectedTaskIds?` (người thực hiện các task này nhận `RISK_RELEVANT`). `DROPOUT_RISK` do LEAD tạo → `status=CONFIRMED` ngay (`confirmedBy/At` = LEAD, có audit) và là điều kiện tô **Đỏ** (§4.2); `DELAY_RISK`/`BLOCKED` → `OPEN`. Báo risk **không** tự sửa deadline, không tự đánh dấu bỏ việc, không tự điều phối người khác. Response `201` `RiskReport`.
Lỗi: `403 LEAD_ONLY` (DROPOUT_RISK bởi người khác), `403 NOT_ASSIGNEE`, `400 VALIDATION_FAILED`.

#### `POST /risk-reports/{riskReportId}/resolve`
**Vai trò:** LEAD hoặc người đã tạo · **Màn hình:** S03/S05 · **Owner:** BE-B · **Idempotency:** — (tự nhiên)
Request `ResolveRiskRequest`: `note?`, `version` ✔. `OPEN`/`CONFIRMED` → `RESOLVED` (`resolvedAt`). Response `200` `RiskReport`. Lỗi: `409 RISK_ALREADY_RESOLVED`, `409 VERSION_CONFLICT`, `403 FORBIDDEN`.

---

### J. Hỗ trợ công khai, tự nguyện đề nghị, bàn giao *(BE-B · `help`)*

Luồng: TL đăng **toàn bộ task** → thành viên **tự nguyện** `offer` → TL chọn (`accept-offer`) → Helper làm & nộp (S10) → TL duyệt (S11, qua `POST /task-submissions/{id}/review`). Không có phân công tự động, không có endpoint "từ chối/bỏ qua" (BR06), không tạo `CompensationAgreement`. **P_t do server điền**, nhận trong `points` (không có input điểm).

**`HelpRequestCard`** (thẻ S08/S03) — trường chính: `id, groupId, title, descriptionPreview, expectedOutput, task, postedBy, beneficiary, deadline, difficulty, estimatedEffortMinutes, points (PointAmount|null), pointLabel, status, statusLabel, myRoleInRequest, myOfferStatus, workId, createdAt`.
```json
{ "id": "<helpRequestId>", "groupId": "<groupId>", "title": "Thiết kế API (toàn bộ task)",
  "descriptionPreview": "Hoàn thiện 12 endpoint nhóm/task, kèm OpenAPI…", "expectedOutput": "OpenAPI + 12 endpoint chạy được",
  "task": { "id": "<taskId>", "title": "Thiết kế API" },
  "postedBy": { "userId": "<userMinh>", "name": "Minh" }, "beneficiary": { "userId": "<userAn>", "name": "An" },
  "deadline": "2026-11-27T13:00:00Z", "difficulty": "HARD", "estimatedEffortMinutes": 360,
  "points": { "pointUnits": 100000, "percentLabel": "10,0000" },
  "pointLabel": "Khó · +10,0000 điểm % khi TL duyệt",
  "status": "OPEN", "statusLabel": "Đang tìm người nhận làm thay toàn bộ task",
  "myRoleInRequest": "VIEWER", "myOfferStatus": null, "workId": null, "createdAt": "2026-11-21T03:00:00Z" }
```
`points=null`/`pointLabel=null` khi task không có điểm (chưa có phương án điểm hoặc task chưa định giá). Không hiện "đã cộng".

#### `GET /groups/{groupId}/help-requests`
**Vai trò:** MEMBER · **Màn hình:** S08, S03 (khối "Hỗ trợ đang mở") · **Owner:** BE-B · **Idempotency:** —
Query `ListHelpRequestsQuery`:
| Tham số | Kiểu | Ghi chú |
|---|---|---|
| `tab` | `HelpRequestTab` | `OPEN` (mặc định: `status=OPEN`); `MY_OFFERS` (tôi có offer `PENDING/ACCEPTED/NOT_SELECTED/WITHDRAWN`); `MY_WORK` (tôi là Helper, work chưa `APPROVED`); `COMPLETED` (`COMPLETED`/`CANCELLED` của nhóm) |
| `difficulty` | `Difficulty` | |
| `deadlineFrom`, `deadlineTo` | IsoInstant | |
| `minPointUnits`, `maxPointUnits` | int | lọc theo P_t (units) |
| `maxEffortMinutes` | int | ước tính công sức tham khảo |
| `page`, `size` | int | |

Sắp: `deadline` tăng dần. Không lọc/xếp hạng theo phẩm chất người. Response `200` `Page<HelpRequestCard>`. Empty state: "Chưa có phần việc hỗ trợ nào đang mở…".

#### `POST /groups/{groupId}/help-requests`
**Vai trò:** LEAD (REQUESTER) · **Màn hình:** S08 (modal `+ Đăng yêu cầu`) · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Request `CreateHelpRequestRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `taskId` | Uuid | ✔ | task cùng nhóm, **toàn bộ task** chưa được chấp nhận |
| `beneficiaryId` | Uuid | ✔ | phải đúng người thực hiện hiện tại của task |
| `supportRequestId` | Uuid\|null | | liên kết đề nghị riêng (S07) |
| `title` | string | ✔ | 3–120 |
| `description` | string | ✔ | 10–2000; TL tự viết, không lộ lý do riêng tư |
| `expectedOutput` | string\|null | | mặc định từ kế hoạch hiệu lực |
| `estimatedEffortMinutes` | int\|null | | 1–100000, chỉ tham khảo |
| `skillNotes` | string\|null | | ≤1000 |
| `privacyChecked` | `true` | ✔ | TL xác nhận đã kiểm tra không lộ lý do riêng tư |

Server điền: `deadline`/`difficulty`/`expectedOutput`/`points` từ kế hoạch hiệu lực + quỹ task, `resultApprover` (= LEAD, hoặc người duyệt độc lập nếu task của chính LEAD), giữ **toàn bộ P_t** bằng reservation `HELD` (nếu task có quỹ). Chỉ cho task **chưa nộp** hoặc đã bị `FIX_REQUIRED`/`UPDATE_REQUIRED`. Phát `HELP_PUBLISHED` (cả nhóm trừ người đăng) và `SUPPORT_DECISION` cho người gửi đề nghị riêng (nếu có). Response `201` `HelpRequestDetail`.
Lỗi: `403 LEAD_ONLY`, `409 TASK_ALREADY_DONE`, `409 TASK_AWAITING_REVIEW` (bản nộp đang chờ TL duyệt phải được kiểm tra trước), `409 TASK_NOT_TRANSFERABLE`, `409 HELP_REQUEST_ALREADY_OPEN` (mỗi task chỉ một yêu cầu còn hiệu lực), `422 BENEFICIARY_MISMATCH`, `400 VALIDATION_FAILED`.

#### `GET /help-requests/{helpRequestId}`
**Vai trò:** MEMBER · **Màn hình:** S09, S08 · **Owner:** BE-B · **Idempotency:** —
Response `200` `HelpRequestDetail` = `HelpRequestCard` + `description`, `skillNotes`, `supportRequestId` (chỉ LEAD), `resultApprover`, `myOffer`, `offers` (chỉ REQUESTER; người khác `[]`), `work`, `myImpact`, `pointExplanation`, `allowedActions`, `version`:
```json
{ "…": "HelpRequestCard", "description": "Hoàn thiện 12 endpoint…", "skillNotes": "Spring Boot",
  "supportRequestId": null, "resultApprover": { "userId": "<userMinh>", "name": "Minh" },
  "myOffer": null, "offers": [],
  "work": null,
  "myImpact": { "potentialBefore": { "pointUnits": 200000, "percentLabel": "20,0000" },
                "potentialAfter":  { "pointUnits": 300000, "percentLabel": "30,0000" },
                "delta":           { "pointUnits": 100000, "percentLabel": "+10,0000" } },
  "pointExplanation": "Điểm được chuyển toàn bộ từ quỹ của task; chỉ ghi sau khi TL duyệt kết quả.",
  "allowedActions": ["OFFER_HELP"], "version": 2 }
```
`myImpact` = `Có thể đạt Y% → Y+P_t%` từ phân công hiện tại (null nếu không áp dụng); **không** làm tăng ledger. FE dùng cho link "Xem ảnh hưởng đến điểm của tôi" (S12 mô phỏng). Lỗi: `404 NOT_FOUND`.

#### `PATCH /help-requests/{helpRequestId}`
**Vai trò:** LEAD (REQUESTER) · **Màn hình:** S09 (sửa trước khi chọn người) · **Owner:** BE-B · **Idempotency:** — (kiểm tra `version`)
Request `UpdateHelpRequestRequest`: `title?, description?, expectedOutput?, estimatedEffortMinutes?, skillNotes?`, `version` ✔. Chỉ khi `OPEN`. Đổi `description`/`expectedOutput` là thay đổi quan trọng → mọi offer `PENDING` có `needsReconfirmation=true` (Helper phải `reconfirm`). Điểm/deadline/độ khó **không sửa được**. Response `200` `HelpRequestDetail`.
Lỗi: `403 LEAD_ONLY`, `409 HELP_REQUEST_NOT_OPEN`, `409 VERSION_CONFLICT`.

#### `POST /help-requests/{helpRequestId}/cancel`
**Vai trò:** LEAD (REQUESTER) · **Màn hình:** S08/S09 (confirm dialog) · **Owner:** BE-B · **Idempotency:** — (tự nhiên)
Request `CancelHelpRequestRequest`: `reason?`, `version` ✔. Chỉ khi `OPEN`: → `CANCELLED`, giải phóng reservation (`RELEASED`), offer `PENDING` → `NOT_SELECTED` (gửi `SUPPORT_NOT_SELECTED`). Đã có người nhận mà chưa xử lý bàn giao → không hủy im lặng. Response `200` `HelpRequestDetail`.
Lỗi: `409 HELP_REQUEST_HAS_HELPER` (xử lý qua S10: `unable`/`resolve-discussion`), `409 HELP_REQUEST_NOT_OPEN`, `409 VERSION_CONFLICT`, `403 LEAD_ONLY`.

#### `POST /help-requests/{helpRequestId}/offers`
**Vai trò:** MEMBER, **không** phải REQUESTER/BENEFICIARY (và không phải người duyệt độc lập của task) · **Màn hình:** S09 (`Mình có thể hỗ trợ` → `Gửi đề nghị`) · **Owner:** BE-B · **Idempotency:** RECOMMENDED
Tự nguyện, có quyền không tham gia; **không** cộng điểm lúc đề nghị. Request `CreateOfferRequest`: `message?` (≤500), `acknowledgedScope` (`true` ✔: đã xem phạm vi, deadline, P_t và điều kiện "chỉ ghi điểm sau TL duyệt"), `helpRequestVersion` (number ✔). Response `201` `SupportOffer`:
```json
{ "id": "<offerId>", "helpRequestId": "<helpRequestId>", "helper": { "userId": "<userBinh>", "name": "Bình" },
  "message": "Mình rảnh tối nay", "status": "PENDING", "needsReconfirmation": false,
  "createdAt": "2026-11-21T04:00:00Z", "decidedAt": null, "decidedBy": null }
```
Phát `SUPPORT_OFFERED` cho LEAD. UI: `Đã gửi đề nghị — chờ người đăng phản hồi`. Lỗi: `403 SELF_ACTION_FORBIDDEN`, `409 HELP_REQUEST_NOT_OPEN`, `409 HELP_ALREADY_MATCHED` (hiển thị `Công việc đã có người hỗ trợ`), `409 OFFER_ALREADY_EXISTS` (mỗi người một offer đang hoạt động), `409 VERSION_CONFLICT` (yêu cầu vừa đổi → xem lại), `400 VALIDATION_FAILED`.

#### `POST /support-offers/{offerId}/withdraw`
**Vai trò:** HELPER (chủ offer) · **Màn hình:** S09 (`Rút đề nghị`, không cần giải thích) · **Owner:** BE-B · **Idempotency:** — (tự nhiên)
Không body. `PENDING` → `WITHDRAWN`. Response `200` `SupportOffer`. Lỗi: `409 OFFER_NOT_PENDING`, `403 FORBIDDEN`.

#### `POST /support-offers/{offerId}/reconfirm`
**Vai trò:** HELPER (chủ offer) · **Màn hình:** S09 · **Owner:** BE-B · **Idempotency:** — (tự nhiên)
Request `ReconfirmOfferRequest`: `{ "helpRequestVersion": 3 }` (xác nhận lại sau khi phạm vi đổi quan trọng). Response `200` `SupportOffer` (`needsReconfirmation=false`; phát lại `SUPPORT_OFFERED`). Lỗi: `409 OFFER_NOT_PENDING`, `409 VERSION_CONFLICT`.

#### `POST /help-requests/{helpRequestId}/accept-offer`
**Vai trò:** LEAD (REQUESTER) · **Màn hình:** S09 (`Chấp thuận`; "Không chọn" = không có API) · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Request `AcceptOfferRequest`: `offerId` (Uuid ✔), `version` (number ✔, version của yêu cầu). **Một transaction** (khóa dòng yêu cầu): offer → `ACCEPTED`; các offer còn lại → `NOT_SELECTED` (+ `SUPPORT_NOT_SELECTED`, lịch sự, không lý do); tạo `SupportWork` (`IN_PROGRESS`, snapshot phạm vi/deadline/độ khó/điểm); request → `IN_PROGRESS`; reservation `CONSUMED`; assignment điểm mới `CURRENT` = Helper nhận **100% P_t** (người gốc hết quyền nhận); tạo revision hiệu lực `TL_SUPPORT_HANDOFF` với người thực hiện = Helper (nếu kỳ nguồn đã đóng: tạo vòng kế tiếp theo quy tắc chuyển kỳ, snapshot kỳ cũ giữ nguyên); audit; `SUPPORT_ACCEPTED` (Helper), `POINT_ASSIGNMENT_CHANGED` (người gốc + Helper). **Không ghi ledger.**
Response `200` `AcceptOfferResponse` = `{ helpRequest, work, task, notSelectedOfferIds }`.
Lỗi: `403 LEAD_ONLY`, `409 HELP_ALREADY_MATCHED`, `409 HELP_REQUEST_NOT_OPEN`, `409 OFFER_NOT_PENDING`, `409 OFFER_NEEDS_RECONFIRMATION`, `409 VERSION_CONFLICT`. Hai người nhận cùng lúc: người sau nhận `HELP_ALREADY_MATCHED`.

#### `GET /support-works/{workId}`
**Vai trò:** HELPER, REQUESTER (LEAD), BENEFICIARY/người gốc, người duyệt độc lập · **Màn hình:** S10, S11 · **Owner:** BE-B · **Idempotency:** —
Response `200` `SupportWork` = `SupportWorkSummary` + `groupId, task, originalAssignee, resultApprover, scopeSnapshot, expectedOutput, deadlineSnapshot, difficultySnapshot, pointLabel, roundId, submissions[], timeline[], contribution, latestTaskSubmissionId, cutoffAt, canSubmit, cannotSubmitReason, canReviewNow, successorHelpRequestId, allowedActions, version`:
```json
{ "id": "<workId>", "helpRequestId": "<helpRequestId>", "title": "Thiết kế API (toàn bộ task)",
  "helper": { "userId": "<userBinh>", "name": "Bình" }, "requester": { "userId": "<userMinh>", "name": "Minh" },
  "status": "IN_PROGRESS", "helpRequestStatus": "IN_PROGRESS", "statusLabel": "Đang thực hiện",
  "points": { "pointUnits": 100000, "percentLabel": "10,0000" }, "pointRecorded": false,
  "contributionStatus": null, "updatedAt": "2026-11-21T05:00:00Z",
  "groupId": "<groupId>", "task": { "id": "<taskId>", "title": "Thiết kế API" },
  "originalAssignee": { "userId": "<userAn>", "name": "An" }, "resultApprover": { "userId": "<userMinh>", "name": "Minh" },
  "scopeSnapshot": "Hoàn thiện 12 endpoint…", "expectedOutput": "OpenAPI + 12 endpoint chạy được",
  "deadlineSnapshot": "2026-11-27T13:00:00Z", "difficultySnapshot": "HARD", "pointLabel": "+10,0000 điểm % khi TL duyệt (chưa ghi điểm)",
  "roundId": "<roundId2>", "submissions": [], "timeline": [ { "at": "2026-11-21T05:00:00Z", "kind": "OFFER_ACCEPTED", "actor": { "userId": "<userMinh>", "name": "Minh" }, "note": null } ],
  "contribution": null, "latestTaskSubmissionId": null, "cutoffAt": "2026-11-27T15:00:00Z",
  "canSubmit": true, "cannotSubmitReason": null, "canReviewNow": false, "successorHelpRequestId": null,
  "allowedActions": ["SUBMIT_RESULT", "REPORT_UNABLE"], "version": 1 }
```
`pointRecorded=false` ⇒ hiển thị "Chưa ghi điểm". `roundId=null` khi chờ TL chốt deadline xử lý tiếp. Lỗi: `403 FORBIDDEN`, `404 NOT_FOUND`.

#### `POST /support-works/{workId}/submissions`
**Vai trò:** HELPER · **Màn hình:** S10 (`Gửi kết quả`) · **Owner:** BE-B · **Idempotency:** **REQUIRED** (chống bấm đúp)
Request `SubmitSupportResultRequest`:
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `resultNote` | string | ✔ | 10–1000 (tóm tắt phần việc đã làm) |
| `evidence` | `EvidenceItem[]` | ✔ | 1–5 URL (ít nhất một minh chứng) |
| `actualMinutes` | int\|null | | >0, **tự khai** (chỉ ghi nhận công sức, không đổi điểm) |

Server tạo `SupportSubmission` **và** `TaskSubmission` tương ứng (bản chốt của vòng, có `isOnTime`), work → `SUBMITTED`, request → `SUBMITTED`, contribution `PENDING_CONFIRMATION`; phát `SUPPORT_SUBMITTED`. Response `201` `SubmitSupportResultResponse` = `{ submission: SupportSubmission, work: SupportWork }`. UI hiện lời cảm ơn nhưng phân biệt `Đã gửi kết quả — chờ xác nhận` với `Đóng góp đã được công nhận`.
Lỗi: `403 FORBIDDEN`, `409 WORK_NOT_SUBMITTABLE` (đang `SUBMITTED`/`APPROVED`, hoặc chờ TL chốt deadline vòng sau — `message` nêu lý do), `409 SUBMISSION_WINDOW_CLOSED`, `422 EVIDENCE_REQUIRED`, `400 VALIDATION_FAILED`.

#### `POST /support-works/{workId}/unable`
**Vai trò:** HELPER · **Màn hình:** S10 (`Báo không thể hoàn thành`) · **Owner:** BE-B · **Idempotency:** RECOMMENDED
Request `ReportUnableRequest`: `reason` (5–1000 ✔), `version` ✔. `IN_PROGRESS`/`REVISION_REQUESTED` → `NEEDS_DISCUSSION`; phát `SUPPORT_WORK_ISSUE` (LEAD). **Không** tự chuyển cho người khác, không trừ điểm. Response `200` `SupportWork`. Lỗi: `409 WORK_INVALID_STATE`, `409 VERSION_CONFLICT`, `403 FORBIDDEN`.

#### `POST /support-works/{workId}/resolve-discussion`
**Vai trò:** LEAD (REQUESTER) · **Màn hình:** S10/S11 · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Request `ResolveDiscussionRequest`: `resolution` (`SupportWorkResolution` ✔), `note` (5–1000 ✔), `version` ✔. Chỉ khi work `NEEDS_DISCUSSION`:
- `RESUME` → work `IN_PROGRESS`.
- `CANCEL_REQUEST` → request `CANCELLED`; quyền nhận P_t và người thực hiện **hoàn trả** cho người gốc (assignment mới `CURRENT`, revision `TL_SUPPORT_HANDOFF`), reservation giải phóng; offer đã `ACCEPTED` → `WITHDRAWN`.
- `REOPEN_REQUEST` → như `CANCEL_REQUEST` nhưng đồng thời tạo **yêu cầu công khai mới** `OPEN` (giữ lại P_t); id trả ở `successorHelpRequestId` (work giữ `NEEDS_DISCUSSION` để lưu lịch sử, `UNIQUE(help_request_id)` của work vẫn đúng).

Phát `POINT_ASSIGNMENT_CHANGED` (người gốc/Helper) khi quyền nhận thay đổi. Response `200` `SupportWork`. Lỗi: `403 LEAD_ONLY`, `409 WORK_INVALID_STATE`, `409 VERSION_CONFLICT`.

---

### K. Điểm đóng góp *(BE-B · `points`)*

Mọi lượng điểm trả `PointAmount`/`PointDelta` (§1.5). Ledger **chỉ** được ghi bởi `POST /task-submissions/{id}/review` (ACCEPTED) và `POST /point-settlements/{id}/reverse`.

#### `GET /groups/{groupId}/point-policy`
**Vai trò:** MEMBER (`allocation` chỉ LEAD) · **Màn hình:** S04 (bảng W_i / phần quỹ ban đầu cho TL), S12 · **Owner:** BE-B · **Idempotency:** —
Response `200` `PointPolicyResponse`:
```json
{ "policy": { "id": "<policyId>", "versionNo": 1, "status": "ACTIVE",
              "totalPoints": { "pointUnits": 1000000, "percentLabel": "100,0000" },
              "weights": { "EASY": 1, "MEDIUM": 2, "HARD": 3 }, "memberCountAtBaseline": 5,
              "approvedBy": { "userId": "<userMinh>", "name": "Minh" }, "approvedAt": "2026-11-16T02:00:00Z", "createdAt": "2026-11-16T01:50:00Z" },
  "active": true, "statusLabel": null,
  "allocation": { "totalPoints": { "pointUnits": 1000000, "percentLabel": "100,0000" },
    "unallocatedPoints": { "pointUnits": 0, "percentLabel": "0,0000" },
    "members": [ { "user": { "userId": "<userAn>", "name": "An" },
      "initialPoints": { "pointUnits": 200000, "percentLabel": "20,0000" },
      "reservePoints": { "pointUnits": 0, "percentLabel": "0,0000" },
      "pricedPoints":  { "pointUnits": 200000, "percentLabel": "20,0000" },
      "weightSum": 6, "taskCount": 3,
      "tasks": [ { "taskId": "<t1>", "title": "Dễ", "difficulty": "EASY",   "weight": 1, "points": { "pointUnits": 33333,  "percentLabel": "3,3333" } },
                 { "taskId": "<t2>", "title": "TB",  "difficulty": "MEDIUM", "weight": 2, "points": { "pointUnits": 66667,  "percentLabel": "6,6667" } },
                 { "taskId": "<t3>", "title": "Khó", "difficulty": "HARD",   "weight": 3, "points": { "pointUnits": 100000, "percentLabel": "10,0000" } } ],
      "warnings": [] } ] } }
```
Chưa khóa: `policy=null`, `active=false`, `statusLabel="Chưa có phương án điểm đã duyệt"`.

#### `POST /groups/{groupId}/point-policy/preview`
**Vai trò:** LEAD · **Màn hình:** S04 (xem phân công cân đối trước khi khóa) · **Owner:** BE-B · **Idempotency:** — (không ghi)
Request `PointPolicyPreviewRequest`: `participantUserIds?` (mặc định mọi thành viên đang hoạt động, gồm TL), `reserves?` = `[{ "userId", "reservePointUnits": int ≥0 }]`. `basis=INITIAL` nếu chưa có phương án `ACTIVE`; ngược lại `ADDITIONAL` (chỉ định giá task **chưa có quỹ** từ phần dự phòng còn lại của từng người; task đã định giá/đã đăng/đã nộp/đã duyệt **không đổi**). Công thức §4.6: `B_i` = 100/N (phần dư lớn nhất), `P_t = (B_i − R_i) × w / W_i`. Response `200` `PointPolicyPreviewResponse`:
```json
{ "basis": "INITIAL", "nextVersionNo": 1, "memberCount": 5,
  "allocation": { "…": "PointAllocation (như trên, dạng dự kiến)" },
  "sumCheck": { "expectedUnits": 1000000, "actualUnits": 1000000, "ok": true },
  "blockingIssues": [], "canActivate": true }
```
`blockingIssues[]` = `{ code, message, taskId|null, userId|null }` (vd `POLICY_TASKS_NOT_APPROVED`: task chưa có kế hoạch `APPROVED`/thiếu độ khó; `POINT_POLICY_INVALID`: `R_i` vượt phần còn tự do, danh sách người sai). `W_i=0` → `warnings` ở member ("giữ B_i làm quỹ chưa phân bổ"), không chặn. Lỗi: `403 LEAD_ONLY`, `422 POINT_POLICY_INVALID`.

#### `POST /groups/{groupId}/point-policy/activate`
**Vai trò:** LEAD · **Màn hình:** S04 (`Khóa phương án điểm`) · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Request `ActivatePointPolicyRequest` = `PointPolicyPreviewRequest` + `confirmedWithMembers: true`. Một transaction: tạo `point_policies` (phiên bản mới, không thêm quỹ 100%), `member_point_budgets`, `task_point_budgets` (khóa P_t, `FUNDED`), assignment `CURRENT` = người gốc, audit; phát `POINT_POLICY_ACTIVATED`. Bảo toàn `Σ initial = 1.000.000` và `Σ funded + Σ unallocated = 1.000.000`. Task tạo **sau** khi khóa không có điểm (`points=null`) cho đến lần `activate` kế tiếp (`ADDITIONAL`) định giá từ phần dự phòng; không bao giờ tạo điểm ngoài quỹ 100%. Response `201` `ActivatePointPolicyResponse` = `{ policy, allocation, fundedTaskCount }`.
Lỗi: `403 LEAD_ONLY`, `422 POLICY_TASKS_NOT_APPROVED`, `422 POINT_POLICY_INVALID`, `400 VALIDATION_FAILED` (`confirmedWithMembers`).

#### `GET /groups/{groupId}/points/me`
**Vai trò:** MEMBER (của chính mình) · **Màn hình:** S12 (tab Điểm của tôi), S03 (tóm tắt) · **Owner:** BE-B · **Idempotency:** —
Response `200` `MyPointsResponse`:
```json
{ "policyActive": true, "statusLabel": null, "memberCount": 5,
  "initialPoints":   { "pointUnits": 200000, "percentLabel": "20,0000" },
  "confirmedPoints": { "pointUnits": 100000, "percentLabel": "10,0000" },
  "pendingPoints":   { "pointUnits": 40000,  "percentLabel": "4,0000" },
  "potentialPoints": { "pointUnits": 140000, "percentLabel": "14,0000" },
  "transferredOut":  { "points": { "pointUnits": 100000, "percentLabel": "10,0000" }, "taskCount": 1 },
  "takenOver":       { "points": { "pointUnits": 40000,  "percentLabel": "4,0000" },  "taskCount": 1 },
  "pendingTransfer": { "heldPoints": { "pointUnits": 0, "percentLabel": "0,0000" }, "openRequestCount": 0 },
  "unallocatedReservePoints": { "pointUnits": 0, "percentLabel": "0,0000" },
  "goalCompletionPercentLabel": "50,0000" }
```
Ý nghĩa: `confirmed` = tổng ròng ledger; `pending = allocated − confirmed`; `potential = confirmed + pending` (**không** tự chặn ở phần ban đầu); `transferredOut`/`takenOver` là chỉ số cấu thành quyền nhận, **không** cộng tiếp vào `potential`. Chưa khóa phương án: `policyActive=false`, `statusLabel="Chưa có phương án điểm đã duyệt"`, `initialPoints=null`, các điểm khác `0`. `goalCompletionPercentLabel` = `confirmed / initial × 100` (tách khái niệm "đóng góp trong nhóm" và "tỷ lệ hoàn thành phần được giao"; null nếu không có `initial`).

#### `GET /groups/{groupId}/points/me/tasks`
**Vai trò:** MEMBER (của mình) · **Màn hình:** S12 (danh sách task có điểm) · **Owner:** BE-B · **Idempotency:** —
Query `ListPointTasksQuery`: `status?` (`PointTaskStatus`), `source?` (`PointTaskSource`), `page`, `size`. Gồm cả task `TRANSFERRED_OUT` (để giải thích biến động). Response `200` `Page<PointTaskRow>`:
```json
{ "items": [ { "taskId": "<taskId>", "title": "Thiết kế API", "difficulty": "HARD",
  "points": { "pointUnits": 100000, "percentLabel": "10,0000" },
  "originalAssignee": { "userId": "<userAn>", "name": "An" }, "currentRecipient": { "userId": "<userBinh>", "name": "Bình" },
  "source": "OWN", "cycle": { "…": "CycleRef" }, "deadline": "2026-11-27T13:00:00Z",
  "status": "TRANSFERRED_OUT", "statusLabel": "Đã chuyển đi", "transferredTo": { "userId": "<userBinh>", "name": "Bình" },
  "heldForRequest": false, "helpRequestId": "<helpRequestId>", "workId": "<workId>" } ],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1 }
```

#### `GET /groups/{groupId}/points/me/ledger`
**Vai trò:** MEMBER (của mình) · **Màn hình:** S12 (Lịch sử đóng góp) · **Owner:** BE-B · **Idempotency:** —
Query `ListPointHistoryQuery`: `group?` (`POINTS_RECORDED`|`ASSIGNMENT`|`ALL`, mặc định `ALL`), `page`, `size`. Mới nhất trước. `POINTS_RECORDED` = khoản ledger (`AWARD`/`REVERSAL`) có `delta`, `runningTotal`; `ASSIGNMENT` = sự kiện chuyển quyền/đăng yêu cầu (không phải điểm đã kiếm, `delta=null`). Response `200` `Page<PointHistoryItem>`:
```json
{ "items": [ { "id": "<ledgerId>", "group": "POINTS_RECORDED", "kind": "AWARD", "occurredAt": "2026-11-21T02:00:00Z",
  "task": { "id": "<taskId>", "title": "Thiết kế API" },
  "delta": { "pointUnits": 100000, "percentLabel": "+10,0000" }, "runningTotal": { "pointUnits": 300000, "percentLabel": "30,0000" },
  "source": "TAKEN_OVER", "approvedBy": { "userId": "<userMinh>", "name": "Minh" },
  "label": "Đã ghi nhận +10,0000 điểm % từ task Thiết kế API", "reason": null, "ledgerEntryId": "<ledgerId>", "workId": "<workId>" } ],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1 }
```

#### `GET /groups/{groupId}/points/ledger/{entryId}`
**Vai trò:** người nhận khoản điểm, người phụ trách gốc, LEAD · **Màn hình:** S12 chi tiết, đích `POINTS_AWARDED`/`POINTS_ADJUSTED` · **Owner:** BE-B · **Idempotency:** —
Response `200` `LedgerEntryDetail` = `{ entry, task, budgetPoints, originalAssignee, submission, review, contribution, settlement, workId, entryType, reversesEntryId, reversedByEntryId }` (đường tới S05/S10; minh chứng chỉ hiện khi được phép). Thời gian tự khai (nếu hiện) gắn nhãn "Tự khai". Lỗi: `403 FORBIDDEN`, `404 NOT_FOUND`.

#### `GET /groups/{groupId}/points/me/simulation-candidates`
**Vai trò:** MEMBER (của mình) · **Màn hình:** S12 (mô phỏng), S09 (link) · **Owner:** BE-B · **Idempotency:** —
Response `200` `SimulationCandidatesResponse` = `{ transferable: SimulationCandidate[], takeable: SimulationCandidate[] }`. `transferable` = task của tôi chưa ghi điểm, chưa có người khác nhận; `takeable` = task người khác đang có yêu cầu hỗ trợ `OPEN` (chưa ghi điểm, chưa có người nhận). Lỗi: `409 POINT_POLICY_NOT_ACTIVE`.

#### `POST /groups/{groupId}/points/me/simulate`
**Vai trò:** MEMBER (của mình) · **Màn hình:** S12 (mô phỏng `mất task này còn bao nhiêu / nhận task kia đạt bao nhiêu`) · **Owner:** BE-B · **Idempotency:** — (**read-only**, không ghi DB/điểm)
Request `SimulatePointsRequest`: `transferOutTaskIds?: Uuid[]`, `takeOnTaskIds?: Uuid[]` (ít nhất một). Không cho chọn task đã có người khác nhận/đã ghi điểm; không mô phỏng cộng lặp cùng task. Response `200` `SimulatePointsResponse`:
```json
{ "confirmedPoints": { "pointUnits": 0, "percentLabel": "0,0000" },
  "startPotentialPoints": { "pointUnits": 200000, "percentLabel": "20,0000" },
  "endPotentialPoints":   { "pointUnits": 140000, "percentLabel": "14,0000" },
  "steps": [
    { "kind": "START", "label": "Hiện có thể đạt 20,0000%", "taskId": null, "delta": null, "potentialAfter": { "pointUnits": 200000, "percentLabel": "20,0000" } },
    { "kind": "TRANSFER_OUT", "label": "Chuyển task 10,0000 điểm % → còn 10,0000%", "taskId": "<t3>",
      "delta": { "pointUnits": -100000, "percentLabel": "-10,0000" }, "potentialAfter": { "pointUnits": 100000, "percentLabel": "10,0000" } },
    { "kind": "TAKE_ON", "label": "Nhận task 4,0000 điểm % → có thể đạt 14,0000%", "taskId": "<t9>",
      "delta": { "pointUnits": 40000, "percentLabel": "+4,0000" }, "potentialAfter": { "pointUnits": 140000, "percentLabel": "14,0000" } } ],
  "notice": "Đây là mô phỏng, không ghi điểm" }
```
Lỗi: `409 POINT_POLICY_NOT_ACTIVE`, `422 SIMULATION_INVALID_TASK` (`fieldErrors`: `transferOutTaskIds[i]`/`takeOnTaskIds[i]`), `400 VALIDATION_FAILED`.

#### `GET /groups/{groupId}/points/summary`
**Vai trò:** MEMBER · **Màn hình:** S12 (tab Quỹ điểm nhóm) · **Owner:** BE-B · **Idempotency:** —
Response `200` `GroupPointsSummary`:
```json
{ "policyActive": true, "statusLabel": null,
  "totalPoints":      { "pointUnits": 1000000, "percentLabel": "100,0000" },
  "confirmedPoints":  { "pointUnits": 300000,  "percentLabel": "30,0000" },
  "pendingPoints":    { "pointUnits": 700000,  "percentLabel": "70,0000" },
  "unallocatedPoints":{ "pointUnits": 0,       "percentLabel": "0,0000" } }
```
`X + Y + Z = 100%`; quỹ request đang giữ nằm trong `pending` (không cộng thêm); X **không** chuẩn hóa lại khi dự án chưa hoàn thành. Không lộ lý do riêng tư, không xếp hạng thành viên.

#### `POST /point-settlements/{settlementId}/reverse`
**Vai trò:** REVIEWER của task (§4.4; không ai tự đảo khoản mình nhận) · **Màn hình:** S11/S12 (hiệu chỉnh sau xác nhận, BR11) · **Owner:** BE-B · **Idempotency:** **REQUIRED**
Sửa sai điểm: **không** sửa/xóa ledger cũ; thêm `REVERSAL −P_t` (unique theo khoản gốc) và thu hồi settlement; nếu `reissueRecipientUserId` có giá trị thì tạo settlement + `AWARD` mới đúng người trong cùng transaction (phải là người thực hiện hợp lệ của bản đã duyệt). Tổng ròng theo task luôn ∈ [0, P_t]. Request `ReverseSettlementRequest`: `reason` (10–1000 ✔), `reissueRecipientUserId?`. Response `200` `ReverseSettlementResponse` = `{ settlementId, reversalEntryId, newSettlementId, newLedgerEntryId, task }`. Phát `POINTS_ADJUSTED`.
Lỗi: `403 NOT_AUTHORIZED_REVIEWER | SELF_ACTION_FORBIDDEN`, `409 SETTLEMENT_ALREADY_REVOKED`, `404 NOT_FOUND`, `400 VALIDATION_FAILED`.

---

### L. Demo *(BE-B · `demo`; **chỉ tồn tại khi chạy profile `demo`** — profile khác trả `404`)*

#### `GET /demo/accounts`
**Vai trò:** PUBLIC · **Màn hình:** S00 (gợi ý đăng nhập demo) · **Owner:** BE-B · **Idempotency:** —
Response `200` `DemoAccountsResponse` = `{ "groupId": "<groupId>", "items": [ { "userId", "name", "email", "password", "role": "LEAD"|"MEMBER", "note" } ] }` — 5 người (TL + 4 thành viên) của nhóm mẫu.

#### `GET /demo/clock`
**Vai trò:** AUTH · **Màn hình:** (công cụ demo) · **Owner:** BE-B · **Idempotency:** —
Response `200` `DemoClockResponse` = `{ "now": "2026-11-20T08:00:00Z", "fixed": true }`.

#### `POST /demo/clock`
**Vai trò:** AUTH · **Màn hình:** (công cụ demo) · **Owner:** BE-B · **Idempotency:** —
Request `SetDemoClockRequest`: đúng một trong `now` (IsoInstant) / `advanceMinutes` (int ≥1); `runDueJobs?` (mặc định true: chạy ngay job nhắc/chốt kỳ/tóm tắt TL tới hạn qua `Clock`). Response `200` `DemoClockResponse`. Không thuộc nghiệm thu 12.10 (nghiệm thu dùng `Clock` trong test); FE ẩn khi `AuthConfigResponse.demoMode=false`. Lỗi: `400 VALIDATION_FAILED`.

---
## 6. Ánh xạ màn hình → endpoint

Ký hiệu: **R** = đọc, **W** = ghi. Mọi màn trong nhóm còn dùng `GET /auth/me`, `GET /notifications/unread-count` (chuông header) và `GET /groups/{groupId}` (header nhóm).

| Màn hình | Đọc (R) | Ghi (W) |
|---|---|---|
| **S00** Đăng nhập | `GET /auth/config`, `GET /invites/{code}` (khi vào từ link mời), `GET /demo/accounts` (demo) | `POST /auth/login`, `POST /auth/register`, `POST /auth/google` |
| **S01** Nhóm của tôi | `GET /groups`, `GET /notifications` | — |
| **S02** Tạo / Tham gia nhóm | `GET /invites/{code}` (Kiểm tra lời mời), `GET /groups/{groupId}/invite`, `GET /groups/{groupId}/checkpoint-schedules` | `POST /checkpoint-schedules/preview`, `POST /groups`, `POST /invites/{code}/accept`; cài đặt nhóm (TL): `PATCH /groups/{groupId}`, `POST/DELETE /groups/{groupId}/invite`, `POST /groups/{groupId}/checkpoint-schedules` |
| **S03** Tổng quan nhóm | `GET /groups/{groupId}/dashboard`, `GET /groups/{groupId}/dashboard/members/{userId}`, `GET /groups/{groupId}/checkpoint-cycles` (+`/current`, `/{cycleId}`), TL: `GET …/lead-summary`, `GET …/review-queue`, `GET /groups/{groupId}/tasks?tab=PENDING_APPROVAL`, `GET /groups/{groupId}/support-requests`; `GET /groups/{groupId}/help-requests`, `GET /groups/{groupId}/risk-reports`, `GET /groups/{groupId}/points/me` | CTA điều hướng: S06/S04/S05/S07/S08/S12/S13 (không ghi trực tiếp) |
| **S04** Công việc & Kế hoạch | `GET /groups/{groupId}/tasks`, `GET /groups/{groupId}/members`, `GET /groups/{groupId}/checkpoint-cycles`, TL: `GET /groups/{groupId}/point-policy` | TL: `POST /groups/{groupId}/tasks`; TL (bảng W_i): `POST …/point-policy/preview`, `POST …/point-policy/activate` |
| **S05** Chi tiết công việc | `GET /tasks/{taskId}`, `GET /tasks/{taskId}/history`, `GET /task-rounds/{roundId}/submissions`, `GET /task-submissions/{submissionId}/review-preview`, `GET /groups/{groupId}/checkpoint-cycles` (chọn kỳ chuyển), `GET /groups/{groupId}/risk-reports?taskId=` | Kế hoạch: `PATCH /plans/{planId}`, `POST …/submit`, `POST …/withdraw`, `POST …/review` (TL), `POST /tasks/{taskId}/plan-revisions`; thực hiện: `POST /tasks/{taskId}/start`, `POST /tasks/{taskId}/progress`, `POST /task-rounds/{roundId}/submissions`; TL: `POST /task-submissions/{submissionId}/review`, `POST /task-rounds/{roundId}/carryover`, `PUT /tasks/{taskId}/independent-reviewer`; nguy cơ: `POST /groups/{groupId}/risk-reports` |
| **S06** Cập nhật checkpoint | `GET /groups/{groupId}/checkpoint-cycles/{cycleId}/my-report` (+ `GET …/checkpoint-cycles/{cycleId}`) | `PUT …/my-report` (nháp), `POST …/my-report/submit`; `POST /task-rounds/{roundId}/submissions` (nộp minh chứng thật); `NEEDS_LEAD_HELP` → S07 |
| **S07** Báo khó khăn / TL xử lý | `GET /groups/{groupId}/support-requests`, `GET /support-requests/{supportRequestId}`, `GET /tasks/{taskId}`, `GET /groups/{groupId}/risk-reports` | Member: `POST /tasks/{taskId}/support-requests`; TL: `POST /support-requests/{supportRequestId}/decision`, `POST /groups/{groupId}/help-requests` (nhánh "Đăng task cần người làm thay"), `POST /groups/{groupId}/risk-reports`, `POST /risk-reports/{riskReportId}/resolve` |
| **S08** Bảng hỗ trợ | `GET /groups/{groupId}/help-requests` (4 tab) | TL: `POST /groups/{groupId}/help-requests`, `POST /help-requests/{helpRequestId}/cancel` |
| **S09** Chi tiết yêu cầu hỗ trợ | `GET /help-requests/{helpRequestId}`, `GET /groups/{groupId}/points/me/simulation-candidates` | Member: `POST /help-requests/{helpRequestId}/offers`, `POST /support-offers/{offerId}/withdraw`, `POST /support-offers/{offerId}/reconfirm`; TL: `POST /help-requests/{helpRequestId}/accept-offer`, `PATCH /help-requests/{helpRequestId}`, `POST …/cancel` |
| **S10** Thực hiện & Bàn giao | `GET /support-works/{workId}` | Helper: `POST /support-works/{workId}/submissions`, `POST /support-works/{workId}/unable`; TL: `POST /support-works/{workId}/resolve-discussion` |
| **S11** Xác nhận & Ghi điểm | `GET /support-works/{workId}`, `GET /task-submissions/{submissionId}/review-preview` | TL/REVIEWER: `POST /task-submissions/{submissionId}/review` (`ACCEPTED`/`FIX_REQUIRED`/`UPDATE_REQUIRED`), rồi `POST /task-rounds/{roundId}/carryover` (khi cần sửa); hiệu chỉnh: `POST /point-settlements/{settlementId}/reverse` |
| **S12** Điểm của tôi & Sổ đóng góp | `GET /groups/{groupId}/points/me`, `…/points/me/tasks`, `…/points/me/ledger`, `…/points/ledger/{entryId}`, `…/points/me/simulation-candidates`, `…/points/summary`, `GET …/point-policy` | `POST …/points/me/simulate` (read-only, không ghi) |
| **S13** Thông báo | `GET /notifications`, `GET /notifications/unread-count` | `PATCH /notifications/{notificationId}/read`, `POST /notifications/{notificationId}/open`, `POST /notifications/read-all` |

---

## 7. Sở hữu module

### 7.1 Bảng sở hữu (84 endpoint: BE-A 29 · BE-B 55)

| Owner | Module (`app.hanare.<module>`) | Mục | Số endpoint | Endpoint |
|---|---|---|---|---|
| **BE-A** | `auth` | A | 5 | `GET /auth/config` · `POST /auth/register` · `POST /auth/login` · `POST /auth/google` · `GET /auth/me` |
| **BE-A** | `group` | B | 10 | `GET /groups` · `POST /groups` · `GET /groups/{groupId}` · `PATCH /groups/{groupId}` · `GET /groups/{groupId}/members` · `GET/POST/DELETE /groups/{groupId}/invite` · `GET /invites/{code}` · `POST /invites/{code}/accept` |
| **BE-A** | `schedule` | C | 7 | `POST /checkpoint-schedules/preview` · `GET/POST /groups/{groupId}/checkpoint-schedules` · `GET /groups/{groupId}/checkpoint-cycles` · `…/current` · `…/{cycleId}` · `…/{cycleId}/lead-summary` |
| **BE-A** | `dashboard` | D | 2 | `GET /groups/{groupId}/dashboard` · `GET /groups/{groupId}/dashboard/members/{userId}` |
| **BE-A** | `notification` | E | 5 | `GET /notifications` · `GET /notifications/unread-count` · `PATCH /notifications/{notificationId}/read` · `POST /notifications/{notificationId}/open` · `POST /notifications/read-all` |
| **BE-B** | `task` | F | 12 | `GET/POST /groups/{groupId}/tasks` · `GET /tasks/{taskId}` · `GET /tasks/{taskId}/history` · `PATCH /plans/{planId}` · `POST /plans/{planId}/submit|withdraw|review` · `POST /tasks/{taskId}/plan-revisions` · `POST /tasks/{taskId}/start` · `POST /tasks/{taskId}/progress` · `PUT /tasks/{taskId}/independent-reviewer` |
| **BE-B** | `task` | G | 6 | `POST/GET /task-rounds/{roundId}/submissions` · `GET /task-submissions/{submissionId}/review-preview` · `POST /task-submissions/{submissionId}/review` · `POST /task-rounds/{roundId}/carryover` · `GET …/checkpoint-cycles/{cycleId}/review-queue` |
| **BE-B** | `task` | H | 3 | `GET/PUT …/checkpoint-cycles/{cycleId}/my-report` · `POST …/my-report/submit` |
| **BE-B** | `support` | I | 7 | `POST /tasks/{taskId}/support-requests` · `GET /groups/{groupId}/support-requests` · `GET /support-requests/{supportRequestId}` · `POST …/decision` · `GET/POST /groups/{groupId}/risk-reports` · `POST /risk-reports/{riskReportId}/resolve` |
| **BE-B** | `help` | J | 13 | `GET/POST /groups/{groupId}/help-requests` · `GET/PATCH /help-requests/{helpRequestId}` · `POST …/cancel` · `POST …/offers` · `POST /support-offers/{offerId}/withdraw|reconfirm` · `POST …/accept-offer` · `GET /support-works/{workId}` · `POST …/submissions` · `POST …/unable` · `POST …/resolve-discussion` |
| **BE-B** | `points` | K | 11 | `GET /groups/{groupId}/point-policy` · `POST …/preview` · `POST …/activate` · `GET …/points/me` · `…/me/tasks` · `…/me/ledger` · `…/ledger/{entryId}` · `…/me/simulation-candidates` · `POST …/me/simulate` · `GET …/points/summary` · `POST /point-settlements/{settlementId}/reverse` |
| **BE-B** | `demo` | L | 3 | `GET /demo/accounts` · `GET/POST /demo/clock` |

Đếm: A=5, B=10, C=7, D=2, E=5 → **29**; F=12, G=6, H=3, I=7, J=13, K=11, L=3 → **55**; tổng **84** (= số khóa trong `ApiEndpoints`).

### 7.2 Phụ thuộc chéo module (qua interface/service công khai, không sửa file module khác)

| Bên dùng | Cần từ | Nội dung (tên gợi ý) |
|---|---|---|
| BE-A `dashboard` | BE-B `task` | `CycleTaskQuery`: danh sách `CycleTaskRow` theo `(groupId, cycleId, asOf)` (kèm `timeliness`, review, snapshot), trạng thái báo cáo thành viên, hàng đợi kế hoạch chờ duyệt, task của tôi |
| BE-A `dashboard` | BE-B `support` / `help` / `points` | số đề nghị riêng mở + `SupportRequestSummary`; `RiskReport` mở; `HelpRequestCard` đang mở; `confirmed/potential` của tôi |
| BE-A `schedule` (job) | BE-B `task` | `CheckpointSnapshotService.freeze(cycleId)` lúc chốt (snapshot task + bản nộp chốt + báo cáo gần nhất đúng hạn); `finalizeIfDone(cycleId)` |
| BE-A `schedule` (job) | BE-A `notification` | `NotificationService.publish(event)` — `CHECKPOINT_REMINDER`, `CHECKPOINT_REVIEW_REQUIRED` (dedup `checkpoint:{cycleId}:…`) |
| BE-B `task`/`help`/`points`/`support` | BE-A `group`/`schedule` | `GroupAccess.requireMember/requireLead(groupId, userId)`; `CycleService.findContaining(groupId, instant)`, `findById`, `nextAfter`; `Clock` |
| BE-B (mọi module) | BE-A `notification` | `NotificationService.publish(type, entity, recipients, payload)` cùng transaction với sự kiện |
| BE-B `help` | BE-B `task`/`points` | bàn giao: tạo revision `TL_SUPPORT_HANDOFF`, assignment điểm, reservation (cùng transaction) |
| BE-B `task` (duyệt) | BE-B `points`/`help` | `PointSettlementService.award(...)`, `HelpWorkService.markApproved(...)` trong **cùng transaction** của `POST /task-submissions/{id}/review` |

---

## 8. Nghiệm thu 12.10 → hợp đồng (không cần endpoint nào khác)

Nghiệm thu chạy bằng test tích hợp (Zonky + `Clock` cố định) gọi **đúng các endpoint trên** và đọc **đúng các trường** dưới đây; job nội bộ được kích bằng cách đặt `Clock` rồi chạy job.

| # | Tình huống 12.10 | Gọi / kiểm tra |
|---|---|---|
| 1 | Nhóm A chốt Sáu 22:00 → nhắc 15:00; nhóm B chốt CN 20:00 → nhắc 13:00 | `POST /groups` với `schedule` A/B → `currentCycle.reminderAt` (A: `…T08:00:00Z` = 15:00 VN; B: 13:00 VN); đặt `Clock` ≥ `reminderAt` → `GET /notifications` của mọi thành viên (gồm TL) có đúng một `CHECKPOINT_REMINDER`; không phụ thuộc cron thứ Sáu |
| 2 | Một kỳ không thiếu báo cáo vẫn một tóm tắt TL; job chạy lại/đồng thời không trùng | Sau `closesAt`: TL có đúng **một** `CHECKPOINT_REVIEW_REQUIRED` (`cycleId`) — chạy job N lần/đồng thời vẫn một; `GET …/lead-summary` (`members.notResponded=0`) |
| 3 | Deadline đúng cutoff thuộc kỳ đó; deadline CN thuộc kỳ sau | `POST /groups/{groupId}/tasks`: `deadline = closesAt` → hợp lệ ở kỳ đó; `deadline` = CN sau cutoff với `cycleId` kỳ đó → `422 DEADLINE_OUTSIDE_CYCLE`; ở kỳ kế → hợp lệ |
| 4 | Gửi kế hoạch & bản sửa đều noti TL; bản sửa chờ duyệt không đổi deadline hiệu lực; người nhóm khác không duyệt được | `POST /plans/{id}/submit` → TL có `PLAN_SUBMITTED`; `POST /tasks/{id}/plan-revisions` + `submitNow` → `PLAN_CHANGE_SUBMITTED`; `GET /tasks/{id}`: `deadline`/`effectivePlan` giữ nguyên, `pendingRevision.changes`; người nhóm khác `POST /plans/{id}/review` → `404 NOT_A_GROUP_MEMBER`; Member cùng nhóm → `403 LEAD_ONLY` |
| 5 | Nộp minh chứng chỉ `SUBMITTED`; sau cutoff TL chấp nhận mới `DONE`; nộp 19:30, deadline 20:00, duyệt ngày sau vẫn đúng hạn | `POST /task-rounds/{id}/submissions` → `task.taskStatus=SUBMITTED`, `submission.isOnTime=true`; `POST /task-submissions/{id}/review` trước cutoff → `409 REVIEW_BEFORE_CUTOFF`; sau cutoff `ACCEPTED` → `DONE`, `completedAt = submittedAt`, `acceptedAt = reviewedAt`, `timeliness=ON_TIME_ACCEPTED` |
| 6 | `FIX_REQUIRED/UPDATE_REQUIRED` bắt buộc mô tả; Member được thông báo; TL chốt deadline kỳ kế, cùng task ID, giữ vòng cũ | review thiếu `issueDescription` → `400 VALIDATION_FAILED`; hợp lệ → `TASK_REVIEWED`; `POST /task-rounds/{id}/carryover` → `CarryoverResponse` (cùng `taskId`, `roundNo+1`, `previousRoundId`, vòng cũ `CARRIED_OVER` còn nguyên submissions) |
| 7 | Task chưa nộp chỉ chuyển khi TL chốt; task còn chờ duyệt không tự chuyển, không tính trễ do TL chậm | Hết kỳ: `GET …/review-queue` → `carryoverRequired` (chưa nộp) / `awaitingReview` (đã nộp); `carryover` trên task chờ duyệt → `409 CARRYOVER_AWAITING_REVIEW`; `CycleTaskRow.isLate=false` cho bản `ON_TIME_PENDING_REVIEW` |
| 8 | Nhóm 5 người 20%/người; giỏ 1/2/3 = 3,3333/6,6667/10,0000; tổng đúng 20% | `POST …/point-policy/activate` → `allocation.members[].tasks[].points.pointUnits = 33333/66667/100000` (`percentLabel` `"3,3333"/"6,6667"/"10,0000"`), `pricedPoints=200000`; `sumCheck.ok=true` |
| 9 | A chuyển task 10% cho B → A 10%, B 30%; chỉ khi TL duyệt mới đổi ledger | `accept-offer` → `GET /groups/{g}/points/me` (A: `potentialPoints=100000`, `confirmedPoints=0`; B: `potentialPoints=300000`); `GET …/points/me/ledger` chưa có `AWARD` |
| 10 | A nhận task 4% từ C → A 14%, C 16%; A..E vẫn chia quỹ 100% | `GET …/points/me` (A `140000`, C `160000`), `GET …/points/summary`: `confirmed+pending+unallocated = 1000000` |
| 11 | Sửa/nộp lại/chuyển tuần không sinh quỹ mới; duyệt nhiều lần/qua S05 & S11 không cấp hai khoản | Lặp `POST …/review` (cùng/khác khóa) → đúng **một** `AWARD`; `P_t` không đổi qua `carryover`/`plan-revisions`; cả hai đường trả cùng settlement |
| 12 | S08/S09/noti hiện đúng P_t đã khóa; không đổi theo giỏ Helper/giờ tự khai | `HelpRequestCard.points`, `HELP_PUBLISHED` message, `SupportWork.points` = `P_t` của task gốc; `actualMinutes` không ảnh hưởng |
| 13 | S12 phân biệt 0% đã nhận với 14% có thể đạt; biến động tuần không reset | `MyPointsResponse`: `confirmedPoints=0` và `potentialPoints=140000` cùng lúc; sau qua kỳ mới giá trị cộng dồn |
| 14 | Dự án còn task chưa đạt → tổng đã nhận < 100%, không chuẩn hóa lại | `GET …/points/summary`: `confirmedPoints < totalPoints`, `pendingPoints` giữ nguyên |
| 15 | Đổi độ khó/phát sinh/hủy request/hiệu chỉnh ledger không phá tổng quỹ; task đã công bố giá không đổi giá | `plan-revisions` đổi `difficulty` → `points.amount` **không đổi**; `cancel` giải phóng reservation; `POST /point-settlements/{id}/reverse` thêm `REVERSAL`, `sumCheck`/`summary` vẫn ≤ 1.000.000. *(Tách/gộp task chưa có endpoint ở MVP — bất biến Σ kiểm bằng test service, xem SPEC_ISSUES.)* |
| 16 | Dashboard xanh / vàng 1–2 / đỏ ≥3 hoặc TL xác nhận nguy cơ / xám; rõ tạm tính/chờ duyệt/chính thức | `MemberProgress.evaluation`: `color`, `statusLabel`, `reasonCode`, `provisional`, `phase/phaseLabel`; `cutoffEvaluation` sau chốt (§4.2) |
| 17 | Quên report nhưng đã nộp minh chứng → "Thiếu báo cáo" riêng, không thành "chưa nộp" | `MemberProgress.reportState=NOT_RESPONDED` + `evaluation.counts.missingReportTasks>0` đồng thời `pendingReviewTasks>0`; `CycleTaskRow.reported=false`, `timeliness=ON_TIME_PENDING_REVIEW` |
| 18 | Member không đăng public request qua API; private reason không rò | `POST /groups/{g}/help-requests` bởi Member → `403 LEAD_ONLY`; quét `DashboardResponse`, `NotificationItem`, `HelpRequestDetail`, `RiskReport` không chứa `description/neededSupport` của đề nghị riêng |
| 19 | Duyệt hỗ trợ chỉ một contribution; người bỏ qua không bị trừ/ghi tiêu cực | `ACCEPTED` trên bản của Helper → đúng một `Contribution` (`work` unique); không có endpoint/bản ghi "từ chối"; `offers` chỉ chứa người thực sự đề nghị |
| 20 | Đổi deadline/hoàn thành ở kỳ sau không đổi snapshot nộp & kết quả duyệt kỳ cũ | Sau `carryover`/duyệt: `GET …/dashboard/members/{userId}?cycleId=<kỳ cũ>` → `CycleTaskRow` (deadline cũ, `submittedAt`, `review`) và `cutoffEvaluation` **không đổi** |

---

## 9. Bảng mã lỗi nghiệp vụ

`code` ∈ enum `ErrorCode` (`api-types.ts`). Một số mã dùng được ở nhiều endpoint; cột cuối nêu endpoint tiêu biểu.

| Code | HTTP | Khi nào | Tiêu biểu |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | Sai kiểu/độ dài/định dạng/thiếu trường (có `fieldErrors`) | mọi POST/PATCH/PUT |
| `MALFORMED_REQUEST` | 400 | JSON hỏng / enum lạ | mọi endpoint có body |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Thiếu `Idempotency-Key` ở endpoint REQUIRED | §1.9 |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Cùng khóa, body khác | endpoint REQUIRED/RECOMMENDED |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | Request cùng khóa đang xử lý | như trên |
| `UNAUTHENTICATED` | 401 | Thiếu/sai token | mọi endpoint không PUBLIC |
| `TOKEN_EXPIRED` | 401 | Token hết hạn | như trên |
| `INVALID_CREDENTIALS` | 401 | Sai email/mật khẩu/Google token | `POST /auth/login` |
| `FORBIDDEN` | 403 | Không có quyền đọc/ghi tài nguyên | `GET /support-requests/{id}` |
| `NOT_A_GROUP_MEMBER` | **404** | Không (còn) là thành viên nhóm — dùng 404 (cùng dạng "không có nhóm") để không lộ nhóm; FE hiển thị trang "Không tìm thấy nội dung" | mọi đường dẫn nhóm |
| `LEAD_ONLY` | 403 | Chỉ TL được làm | `POST /plans/{id}/review`, `POST …/help-requests` |
| `NOT_ASSIGNEE` | 403 | Không phải người thực hiện | `POST /task-rounds/{id}/submissions` |
| `NOT_AUTHORIZED_REVIEWER` | 403 | Không có quyền duyệt kết quả đó | `POST /task-submissions/{id}/review` |
| `SELF_ACTION_FORBIDDEN` | 403 | Tự duyệt điểm mình nhận; TL/beneficiary tự đề nghị phần của mình | `POST …/offers`, `…/review` |
| `NOT_FOUND` | 404 | Không có tài nguyên | mọi GET theo id |
| `VERSION_CONFLICT` | 409 | `version` cũ / bản nền đã đổi | mọi PATCH/POST có `version` |
| `INVALID_STATE` | 409 | Trạng thái hiện tại không cho phép hành động chung | `POST /tasks/{id}/start` |
| `RATE_LIMITED` | 429 | Gửi quá nhanh | — |
| `INTERNAL_ERROR` | 500 | Lỗi không lường | — |
| `EMAIL_ALREADY_REGISTERED` | 409 | Email đã tồn tại | `POST /auth/register` |
| `OAUTH_NOT_CONFIGURED` | 501 | Chưa cấu hình Google | `POST /auth/google` |
| `INVITE_NOT_FOUND` | 404 | Mã mời không có | `GET /invites/{code}` |
| `INVITE_EXPIRED` | 410 | Mã mời hết hạn | như trên |
| `INVITE_REVOKED` | 410 | Mã mời bị thu hồi | như trên |
| `ALREADY_MEMBER` | 409 | Đã là thành viên | `POST /invites/{code}/accept` |
| `GROUP_DEADLINE_INVALID` | 422 | Deadline nhóm trước ngày bắt đầu | `POST/PATCH /groups` |
| `SCHEDULE_INVALID` | 422 | Lịch checkpoint sai (weekday/giờ/timezone/khung mở) | `POST /groups`, `…/checkpoint-schedules` |
| `ASSIGNEE_NOT_IN_GROUP` | 422 | Người phụ trách không thuộc nhóm | `POST …/tasks` |
| `START_AFTER_DEADLINE` | 422 | `startAt` > `deadline` | tạo/sửa kế hoạch |
| `DEADLINE_OUTSIDE_CYCLE` | 422 | Deadline ngoài `(periodStartAt, closesAt]` của kỳ đã chọn | tạo/sửa kế hoạch |
| `DEADLINE_AFTER_GROUP_DEADLINE` | 422 | Deadline vượt deadline nhóm (MVP không có ngoại lệ) | tạo/sửa kế hoạch |
| `PLAN_INCOMPLETE` | 422 | Thiếu trường bắt buộc khi gửi | `POST /plans/{id}/submit` |
| `PLAN_NOT_EDITABLE` | 409 | Bản đang `PENDING_APPROVAL`/`APPROVED` | `PATCH /plans/{id}` |
| `INVALID_PLAN_STATE` | 409 | Trạng thái bản không cho thao tác (gửi/rút/duyệt) | `…/submit|withdraw|review` |
| `REVISION_ALREADY_IN_FLIGHT` | 409 | Đã có bản sửa đang soạn/chờ ở vòng này | `POST /tasks/{id}/plan-revisions` |
| `NO_CHANGES_PROPOSED` | 422 | Bản sửa không khác bản hiệu lực | như trên |
| `NO_EFFECTIVE_PLAN` | 409 | Chưa có kế hoạch được duyệt | `start`, `progress`, `plan-revisions`, nộp |
| `PLAN_CHANGED` | 409 | Bản hiệu lực đã đổi sau khi FE tải | `POST /task-rounds/{id}/submissions` |
| `ROUND_NOT_ACTIVE` | 409 | Vòng không `ACTIVE` | nộp kết quả |
| `SUBMISSION_WINDOW_CLOSED` | 409 | Đã quá `closesAt` của kỳ chứa vòng | nộp (task/Helper) |
| `EVIDENCE_REQUIRED` | 422 | Thiếu minh chứng | nộp |
| `REVIEW_BEFORE_CUTOFF` | 409 | Duyệt trước giờ chốt | `POST /task-submissions/{id}/review` |
| `STALE_SUBMISSION` | 409 | Không phải bản nộp chốt / quyền nhận vừa đổi | như trên |
| `SUBMISSION_ALREADY_REVIEWED` | 409 | Đã có quyết định khác | như trên |
| `INDEPENDENT_REVIEWER_REQUIRED` | 409 | Task có điểm của TL chưa chỉ định người duyệt độc lập | như trên |
| `TASK_ALREADY_DONE` | 409 | Task đã `DONE` | đăng hỗ trợ, đổi reviewer |
| `CARRYOVER_NOT_ALLOWED` | 409 | Kỳ chưa đóng / vòng không thuộc diện chuyển | `POST /task-rounds/{id}/carryover` |
| `CARRYOVER_AWAITING_REVIEW` | 409 | Bản nộp còn chờ TL duyệt | như trên |
| `CARRYOVER_ALREADY_DONE` | 409 | Vòng đã chuyển với tham số khác | như trên |
| `CARRYOVER_DEADLINE_INVALID` | 422 | Deadline mới không ở tương lai/ngoài kỳ đích | như trên |
| `CARRYOVER_REASON_REQUIRED` | 422 | Kỳ đích không liền sau mà thiếu lý do | như trên |
| `REPORT_WINDOW_NOT_OPEN` | 409 | Chưa tới `opensAt` | `my-report` PUT/POST |
| `CYCLE_CLOSED` | 409 | Kỳ đã đóng (không sửa ngược) | `my-report`, tạo task vào kỳ đóng |
| `REPORT_INCOMPLETE` | 422 | Chưa đủ task bắt buộc | `…/my-report/submit` |
| `RESULT_DECLARATION_MISMATCH` | 422 | Khai `RESULT_SUBMITTED`/`MISSING` không khớp lần nộp thật | `my-report` |
| `SUPPORT_REQUEST_CLOSED` | 409 | Đề nghị đã `RESOLVED` | `…/decision` |
| `RISK_ALREADY_RESOLVED` | 409 | Risk đã `RESOLVED` | `…/resolve` |
| `TASK_NOT_TRANSFERABLE` | 409 | Task ở trạng thái không thể nhờ làm thay | `POST …/help-requests` |
| `TASK_AWAITING_REVIEW` | 409 | Bản nộp đang chờ TL duyệt | như trên |
| `HELP_REQUEST_ALREADY_OPEN` | 409 | Task đã có yêu cầu còn hiệu lực | như trên |
| `HELP_REQUEST_BLOCKS_REVIEW` | 409 | Yêu cầu `OPEN` đang giữ quỹ task → đóng trước khi duyệt | `POST /task-submissions/{id}/review` |
| `BENEFICIARY_MISMATCH` | 422 | `beneficiaryId` ≠ người thực hiện hiện tại | `POST …/help-requests` |
| `HELP_REQUEST_NOT_OPEN` | 409 | Yêu cầu không còn `OPEN` | `offers`, `PATCH`, `cancel` |
| `HELP_ALREADY_MATCHED` | 409 | Đã có người được chọn ("Công việc đã có người hỗ trợ") | `offers`, `accept-offer` |
| `OFFER_ALREADY_EXISTS` | 409 | Đã có offer đang hoạt động của người này | `POST …/offers` |
| `OFFER_NOT_PENDING` | 409 | Offer không còn `PENDING` | `withdraw`, `reconfirm`, `accept-offer` |
| `OFFER_NEEDS_RECONFIRMATION` | 409 | Phạm vi đã đổi, Helper chưa xác nhận lại | `accept-offer` |
| `HELP_REQUEST_HAS_HELPER` | 409 | Hủy khi đã có người nhận | `POST …/cancel` |
| `WORK_NOT_SUBMITTABLE` | 409 | Work chưa/không thể nộp (đã nộp, chờ TL chốt vòng, …) | `POST /support-works/{id}/submissions` |
| `WORK_INVALID_STATE` | 409 | Work không ở trạng thái phù hợp | `unable`, `resolve-discussion` |
| `POINT_POLICY_NOT_ACTIVE` | 409 | Chưa có phương án điểm | `simulate`, `simulation-candidates` |
| `POINT_POLICY_INVALID` | 422 | Danh sách người/`R_i` sai, Σ không bảo toàn | `preview`, `activate` |
| `POLICY_TASKS_NOT_APPROVED` | 422 | Có task chưa duyệt/thiếu độ khó | `preview`, `activate` |
| `SIMULATION_INVALID_TASK` | 422 | Task không hợp lệ để mô phỏng | `…/me/simulate` |
| `SETTLEMENT_ALREADY_REVOKED` | 409 | Khoản đã bị đảo | `POST /point-settlements/{id}/reverse` |

Mã hạ tầng HTTP do framework sinh (cùng dạng lỗi chuẩn, **không** nằm trong `ErrorCode` của `api-types.ts`): `METHOD_NOT_ALLOWED` (405), `UNSUPPORTED_MEDIA_TYPE` (415). Vi phạm unique/FK/trigger DB do đua ghi được `GlobalExceptionHandler` quy về `409 VERSION_CONFLICT`; mã nghiệp vụ cụ thể ở bảng trên phải được service **kiểm tra trước** để trả đúng.

---

## 10. Phụ lục

### 10.1 Route FE gợi ý (không ràng buộc BE)
Mọi route mở được trực tiếp bằng URL; S06/S07 có link riêng. `ScreenTarget` → route:
`S00 /login` · `S01 /groups` · `S02 /groups/new`, `/join/:code` · `S03 /groups/:groupId` (+`?cycleId=`) · `S04 /groups/:groupId/tasks` · `S05 /groups/:groupId/tasks/:taskId` (+`?planId=`) · `S06 /groups/:groupId/checkpoints/:cycleId/report` · `S07 /groups/:groupId/support/new?taskId=` và `/groups/:groupId/support/:supportRequestId` · `S08 /groups/:groupId/help` · `S09 /groups/:groupId/help/:helpRequestId` · `S10 /groups/:groupId/works/:workId` · `S11 /groups/:groupId/works/:workId/review` · `S12 /groups/:groupId/points` (+`/ledger/:entryId`) · `S13 /notifications`.

### 10.2 Dữ liệu ví dụ thống nhất (dùng xuyên suốt các mẫu)
Nhóm "Bài tập lớn CSDL", múi giờ `Asia/Ho_Chi_Minh`, 5 người: **Minh** (`LEAD`), **An, Bình, Chi, Dung** (`MEMBER`). Lịch: chốt Thứ Sáu 22:00 (`weekday=5`, `cutoffTime="22:00"`). **Kỳ 3** = `(2026-11-13T15:00:00Z, 2026-11-20T15:00:00Z]` (= 13/11 22:00 → 20/11 22:00 giờ VN), `reminderAt = 2026-11-20T08:00:00Z`, **Kỳ 4** đóng `2026-11-27T15:00:00Z`. Task Khó của An: deadline `2026-11-20T13:00:00Z` (20:00 VN), nộp `2026-11-20T12:30:00Z` (19:30 VN); TL yêu cầu sửa, chốt deadline xử lý tiếp `2026-11-27T13:00:00Z`. Điểm: 20% = `200000` units, giỏ 1/2/3 = `33333/66667/100000`; An chuyển task Khó (10%) cho Bình → An 10%, Bình 30%.

### 10.3 Lịch sử thay đổi
- **v1.0** — bản đầu: 84 endpoint, điểm chốt là `pointUnits` nguyên + `percentLabel` (theo chỉ đạo điều phối viên, spec 12.8).
