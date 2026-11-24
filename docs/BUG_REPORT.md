# HANARE — Báo cáo lỗi (Tester & Hacker)

Bối cảnh: demo **offline trên localhost**, đăng nhập bằng email + mật khẩu (đăng nhập Google **không** nằm trong phạm vi kiểm thử đợt này).

## Quy tắc của file này

1. **Chỉ được thêm lỗi mới, không xóa, không sửa nội dung lỗi cũ, không bỏ qua lỗi nào.**
2. Mỗi lỗi bắt đầu bằng `- [ ]`. Chỉ đổi thành `- [x]` **sau khi đã sửa xong, có test hồi quy và harness đầy đủ vẫn ALL GREEN**; phần "Cách sửa" được bổ sung ngay dưới lỗi.
3. Harness (`harness\harness.cmd`) có yêu cầu `S2-Z1`: còn bất kỳ `- [ ]` nào trong file này thì **không thể ALL GREEN**.
4. Mã lỗi: `T-xx` do **Tester** tìm (chức năng, biên, trạng thái, UI), `H-xx` do **Hacker** tìm (bảo mật, phá luồng, quyền, rò rỉ). Mức độ: Nghiêm trọng / Cao / Trung bình / Thấp.

## Tổng kết

**Đợt 1 (24/11/2026):** 14 lỗi — Hacker 2 (H-01 Nghiêm trọng, H-02 Trung bình), Tester 12 (T-01, T-03 Cao; T-02, T-04 Trung bình; T-05…T-12 Thấp). **Đã sửa 14/14, mỗi lỗi có test hồi quy**; harness đầy đủ ALL GREEN (BE 225 test, FE 176 test, lint + build sạch, 31/31 yêu cầu).

**Đợt 2 (24/11/2026, rà soát lộ khóa/secret):** thêm 2 lỗi — H-03 (Trung bình), H-04 (Thấp). **Đã sửa 2/2**; tổng cộng 16/16 lỗi đã sửa và tích. Harness đầy đủ ALL GREEN (BE 226 test, FE 176 test, lint + build sạch, 32/32 yêu cầu, thêm `S2-Z2`). Không tìm thấy khóa API/token/khóa riêng/file `.env` thật nào trong repo.

Ghi chú trung thực: T-01 là lỗi do chính luồng bàn giao (Solution 2) — test `s2_D8` trước đây chỉ kiểm người gốc *nộp được* sau khi hoàn trả, không kiểm *duyệt được* nên bỏ sót; nay đã có test duyệt + ghi điểm. Hai quan sát của T-08 được xác minh không phải lỗi (xem phần "Cách sửa"). Các mục "Nghi vấn từ Tester cần PO" bên dưới KHÔNG tính là lỗi và được giữ nguyên để PO quyết.

## Danh sách lỗi

### Đợt 1 — Hacker (24/11/2026)

- [x] **H-01 · Nghiêm trọng · Secret JWT mặc định nằm cố định trong repo (profile demo/dev) → giả mạo token của bất kỳ user, kể cả TL, không cần mật khẩu**
  - Nguồn: Hacker · Khu vực: BE — `application-demo.yml`, `application-dev.yml` (`hanare.jwt.secret` mặc định `demo-only-…` / `dev-only-…`), `SecurityConfig#jwtSecretKey`; `DemoController#accounts` (công khai, trả `userId` thật).
  - Tái hiện: `GET /api/v1/demo/accounts` lấy `userId` của TL → tự ký JWT HS256 (iss `hanare-be`, sub = userId) bằng secret mặc định trong file cấu hình → `GET /api/v1/auth/me` = 200 và gọi được endpoint LEAD-only (`/dashboard/members/{id}/tasks` = 200, trong khi Member thật = 403). Hacker đã chạy PoC đầy đủ trên cổng 28080.
  - Thực tế: ai có mã nguồn + biết một userId có thể mạo danh mọi tài khoản mà không cần mật khẩu khi chạy profile demo/dev mà không đặt `HANARE_JWT_SECRET`.
  - Mong đợi: secret ký token không phải hằng số công khai trong repo; mỗi lần khởi động demo/dev dùng secret ngẫu nhiên (hoặc bắt buộc đặt biến môi trường).
  - Đánh giá của người xử lý: lỗi cấu hình thật, nhưng tác động thực tế thấp hơn mức "Nghiêm trọng" khi chạy localhost: `/demo/accounts` vốn đã công khai cả mật khẩu demo (`Demo@1234`) theo thiết kế để đăng nhập thử. Vẫn sửa vì secret hằng số công khai là rủi ro nếu ai đó chạy profile này ngoài máy cá nhân. `/demo/accounts` giữ nguyên (cần cho đăng nhập demo, chỉ tồn tại ở profile demo).
  - Cách sửa: bỏ secret mặc định khỏi `application-demo.yml`/`application-dev.yml`; `SecurityConfig#jwtSecretKey` ở profile demo/dev mà không đặt `HANARE_JWT_SECRET` thì sinh khóa 48 byte ngẫu nhiên (`SecureRandom`) mỗi lần khởi động; production vẫn bắt buộc đặt biến môi trường (≥ 32 byte), secret ngắn bị từ chối kể cả ở demo. Test hồi quy: `JwtSecretTest` (4 test, gồm kiểm tra repo không còn chuỗi secret công khai) và `DemoTest#tokenSignedWithTheOldPublicDefaultSecretIsRejected` (token ký bằng secret cũ → 401 trên server demo thật).

- [x] **H-02 · Trung bình · `POST /auth/login` không giới hạn số lần thử sai (brute-force mật khẩu không bị chặn)**
  - Nguồn: Hacker · Khu vực: BE — `AuthController#login`, `AuthService#login`.
  - Tái hiện: gửi 8 lần `POST /api/v1/auth/login` với mật khẩu sai cho `tl@hanare.demo` → 8 × 401, không trễ/khóa; lần đúng kế tiếp → 200 ngay.
  - Thực tế: không có giới hạn theo email/IP, không tăng độ trễ.
  - Mong đợi: có giới hạn tối thiểu cho endpoint xác thực; mã lỗi `RATE_LIMITED` (429) đã có trong `ErrorCode` nhưng chưa được dùng cho đăng nhập (API_CONTRACT §1.7).
  - Cách sửa: thêm `LoginThrottle` (bộ nhớ, đồng hồ đơn điệu `System.nanoTime` — không dùng `Clock` vì đồng hồ demo đứng yên): tối đa 5 lần sai / 5 phút cho mỗi cặp (địa chỉ TCP, email chuẩn hóa) và 30 lần sai / 5 phút cho mỗi địa chỉ; vượt ngưỡng → `429 RATE_LIMITED` kèm số giây phải chờ, kể cả khi mật khẩu đúng; đăng nhập đúng xóa bộ đếm của cặp; email không tồn tại bị đếm như email tồn tại (không lộ user). Không tin `X-Forwarded-For`. Test hồi quy: `LoginThrottleTest` (6), `LoginRateLimitApiTest` (3, qua HTTP), FE `Login.test.tsx [H-02]`.

### Đợt 1 — Tester (24/11/2026)

Nguồn: agent Tester (≈ 450 lời gọi API + 25 test FE trong sandbox). Mỗi lỗi bên dưới đã được Tester tái hiện bằng test đỏ; khi sửa, test tương ứng được chuyển vào bộ test chính của dự án.

- [x] **T-01 · Cao · Sau `CANCEL_REQUEST` người gốc nhận lại task nhưng kết quả của họ KHÔNG BAO GIỜ duyệt được (409 `STALE_SUBMISSION`), không ghi được điểm**
  - Nguồn: Tester · Khu vực: BE — `SupportWorkService#resolve` (nhánh CANCEL/REOPEN gọi `handoff.swapAssignment(..., workId, ...)` truyền `workId` của work đã hủy vào assignment MỚI của người gốc), `HandoffService#swapAssignment`, `AcceptedSubmissionHandler` (`assignmentWorkId() != null && contributionId == null` → `STALE_SUBMISSION`), `PointLedgerService` (~dòng 105: `helper = viaSupport || b.assignmentWorkId() != null`).
  - Tái hiện: world 5 người, `point-policy/activate`; TL đăng yêu cầu cho task a2 của A; B đề nghị; TL `accept-offer`; B `POST /support-works/{id}/unable`; TL `POST /support-works/{id}/resolve-discussion` `CANCEL_REQUEST` (200, người thực hiện + quyền nhận điểm về A); A `POST /task-rounds/{round}/submissions` (201); đặt giờ sau `closesAt`; TL `POST /task-submissions/{id}/review` `ACCEPTED`. Test đỏ của Tester: `TesterBugsTest#t01_originalMemberCanBeAcceptedAfterCancelRequest`.
  - Thực tế: review trả 409 `STALE_SUBMISSION` ("Quyền nhận điểm đã chuyển cho Helper…") dù `currentRecipient == A`, người thực hiện = A, request `CANCELLED`; ledger = 0, task kẹt ở `SUBMITTED`. Cùng nguyên nhân với `REOPEN_REQUEST` rồi TL hủy yêu cầu kế nhiệm.
  - Mong đợi: §6.8–6.9 và §5 L3 — Helper không tiếp tục → quyết định có lịch sử, người gốc quay lại làm; duyệt đạt thì người có quyền nhận điểm hiện tại (A) nhận đúng `+P_t` một lần. (Test `s2_D8` trước đây chỉ kiểm A *nộp được*, không kiểm *duyệt được*.)
  - Cách sửa: `SupportWorkService#resolve` (CANCEL_REQUEST/REOPEN_REQUEST) không còn truyền `workId` của work đã hủy vào assignment trả về người gốc (`swapAssignment(..., null, ...)`), nên người gốc không còn bị coi là "Helper" ở `AcceptedSubmissionHandler`/`PointLedgerService`/trigger quyết toán; duyệt đạt ghi đúng một khoản `+P_t` cho người gốc. Test hồi quy: `BugRegressionTest#t01_*` (2 test: sau CANCEL, và sau REOPEN rồi hủy yêu cầu kế nhiệm; kiểm tra người nhận điểm, tổng điểm và bất biến quỹ).

- [x] **T-02 · Trung bình · Chuỗi chỉ gồm khoảng trắng vượt kiểm tra độ dài → yêu cầu công khai có tiêu đề/mô tả RỖNG (và các trường tương tự)**
  - Nguồn: Tester · Khu vực: BE — `HelpDtos` (`CreateHelpRequestRequest.title/description`, `UpdateHelpRequestRequest`, `ReportUnableRequest.reason`, `ResolveDiscussionRequest.note` chỉ `@Size`, không `@NotBlank`/không kiểm sau `trim()`), `SupportDtos.CreateSupportRequestRequest.description`; services `.trim()` sau khi đã qua validate.
  - Tái hiện: `POST /groups/{g}/help-requests` với `"title":"   "` hoặc `"description":"          "`; `PATCH /help-requests/{id}` `{"description":"            ","version":N}`; `POST /support-works/{id}/unable` `{"reason":"          "}`; `POST /tasks/{id}/support-requests` với `description` 10 dấu cách; `" ab "` (trim còn 2 ký tự < min 3). Test đỏ: `TesterBugsTest#t02_*`, `#t02b_*`, `#t02c_*`.
  - Thực tế: 201/200 — thẻ yêu cầu công khai hiển thị `title:""`, `descriptionPreview:""`.
  - Mong đợi: 400 `VALIDATION_FAILED` như các trường độ dài khác (API_CONTRACT: title 3–120, description 10–2000, reason ≥ 5); nội dung công khai do TL tự viết rõ ràng (§5 L1.3).
  - Cách sửa: thêm ràng buộc `@TrimmedSize` (`common/TrimmedSize`) đo độ dài SAU khi cắt khoảng trắng, áp dụng cho tiêu đề/mô tả yêu cầu công khai (tạo + sửa), lý do `unable`, ghi chú `resolve-discussion`, ghi chú nộp kết quả hỗ trợ, đề nghị riêng (mô tả + phần cần hỗ trợ), tóm tắt risk và tên nhóm. Chuỗi toàn khoảng trắng hoặc `" ab "` → 400 `VALIDATION_FAILED`. Test hồi quy: `BugRegressionTest#t02_*`.

- [x] **T-03 · Cao · FE — S08 `Đăng yêu cầu`/nút `Đưa lên Workspace` không chọn được task khi nhóm có > 100 task (form chỉ tải 100 task đầu theo deadline tăng dần)**
  - Nguồn: Tester · Khu vực: FE — `src/pages/S08-HelpBoard/CreateHelpDialog.tsx` (`useTasks(groupId, { size: 100 })` rồi lọc phía client SAU khi đã cắt trang; không phân trang, không tra theo `taskId` trên URL).
  - Tái hiện: nhóm ≥ 101 task; TL mở dashboard → Member → `Đưa lên Workspace` ở task có deadline muộn → form mở với `Task` trống và dropdown không chứa task đó. Test đỏ: `FE/src/pages/S08-HelpBoard/TesterCreate.test.tsx` ("nhóm có > 100 task…").
  - Thực tế: chỉ 1 lời gọi `GET /tasks?size=100`; task ngoài 100 mục đầu không được chọn sẵn (`?create=1&taskId=…`) cũng không chọn tay được → TL không đăng được đúng những task muộn/quá hạn gần đây (kịch bản chính của L1).
  - Mong đợi: §8 "TL có nút `Đưa lên Workspace` tại task hợp lệ"; task đăng được phải luôn chọn được (tải đúng task theo id, hoặc lọc phía server).
  - Cách sửa: `CreateHelpDialog` tải task **chưa hoàn thành** do server lọc (`completion=UNFINISHED`), cho "Tải thêm task" khi còn trang (`useTaskPages`), và luôn tra riêng task được chọn sẵn theo id (`useTask`) rồi gộp vào danh sách — task muộn/quá hạn không còn biến mất khi nhóm > 100 task. Test hồi quy: `CreateHelpTasks.test.tsx` (`[T-03]` ×2).

- [x] **T-04 · Trung bình · FE/BE — Sau `REOPEN_REQUEST`/`CANCEL_REQUEST` khối "Nhận làm thay" ở S05 vẫn hiện trạng thái cũ ("Cần trao đổi thêm"), và liên kết sang yêu cầu kế nhiệm không bao giờ hiển thị**
  - Nguồn: Tester · Khu vực: FE `SupportWorkSection.tsx` (`{canResolve ? <ResolveDiscussion/> : null}`) trong khi nhánh `work.successorHelpRequestId` nằm BÊN TRONG `ResolveDiscussion` (`Discussion.tsx`) → mã chết; BE `HelpViews` (`RESOLVE_DISCUSSION` chỉ khi request `IN_PROGRESS`, work vẫn `NEEDS_DISCUSSION` sau khi giải quyết).
  - Tái hiện: `FE/src/pages/S05-TaskDetail/TesterHandoff.test.tsx` ("REOPEN: người gốc đã nhận lại task…") với dữ liệu đúng hình dạng BE: `currentExecutor = originalAssignee`, work `NEEDS_DISCUSSION`, `helpRequestStatus = CANCELLED`, `successorHelpRequestId = help-2`, `allowedActions = []`.
  - Thực tế: vẫn hiện "Cần trao đổi thêm … Bình đang làm thay An …", không có link `/help/help-2`.
  - Mong đợi: §4 M3/§6.8 minh bạch lịch sử — hiện trạng thái đã giải quyết (hoàn trả/đăng lại) và đường dẫn tới yêu cầu mới (`successorHelpRequestId`).
  - Cách sửa: `SupportWorkSection` nhận biết yêu cầu đã `CANCELLED` (work chưa APPROVED) và hiện khối "Đã hoàn trả cho người phụ trách gốc" thay cho "Cần trao đổi thêm"; link "Xem yêu cầu mới" tới `successorHelpRequestId` được hiển thị độc lập với `canResolve` (trước đây nằm trong nhánh mã chết). Ẩn câu "đang làm thay" và nhãn chờ nghiệm thu khi đã hoàn trả. Test hồi quy: `Handoff.test.tsx` (`[T-04]` ×2).

- [x] **T-05 · Thấp · Bảng task theo Member: tại đúng deadline `overdue=true` nhưng chính `task.timeliness` vẫn `NOT_DUE`**
  - Nguồn: Tester · Khu vực: BE — `MemberTaskBoardService` (`!deadline.isAfter(now)`, tức `deadline <= now`) so với `TaskReader.eval` (`OVERDUE_NO_RESULT` chỉ khi `now > deadline`).
  - Tái hiện: `clock.set(deadline)` → `GET /groups/{g}/dashboard/members/{b}/tasks`. Test đỏ: `TesterBugsTest#t05_overdueAtExactDeadlineConsistentWithTimeliness`.
  - Thực tế: `deadline+0ms → overdue=true`, `timeliness=NOT_DUE`; `deadline+1ms → OVERDUE_NO_RESULT`.
  - Mong đợi: một định nghĩa duy nhất của "quá hạn" cho cùng một dòng (nhãn đỏ `Quá hạn` không đi kèm `timeliness=NOT_DUE`).
  - Cách sửa: `MemberTaskBoardService` dùng `deadline.isBefore(now)` (quá hạn là SAU deadline, đúng với `TaskReader.eval`), nên tại đúng deadline `overdue=false` và `timeliness=NOT_DUE`, sau 1 ms cả hai cùng chuyển sang quá hạn. Test hồi quy: `BugRegressionTest#t05_*`.

- [x] **T-06 · Thấp · FE — `MemberTaskBoard` lộ khóa i18n thô khi server trả `section` lạ**
  - Nguồn: Tester · Khu vực: `FE/src/pages/S03-Overview/MemberTaskBoard.tsx` (`t(\`overview.board.sections.${section}\`)` không có fallback).
  - Tái hiện: `FE/src/pages/S03-Overview/TesterBoard.test.tsx` ("section lạ từ server (FOO)…").
  - Thực tế: tiêu đề nhóm hiển thị `overview.board.sections.FOO (1)`.
  - Mong đợi: nhãn mặc định thay vì lộ khóa (rủi ro khi BE thêm section mới).
  - Cách sửa: `MemberTaskBoard` ánh xạ `section` qua danh sách đã biết; section lạ dùng nhãn "Khác" (`overview.board.sections.OTHER`) thay vì lộ khóa i18n. Test hồi quy: `BoardRegression.test.tsx` (`[T-06]`).

- [x] **T-07 · Thấp · Thông điệp chặn `Đưa lên Workspace` sai ngữ cảnh cho task quá hạn đã qua giờ chốt chưa chuyển kỳ**
  - Nguồn: Tester · Khu vực: BE — `TaskWritePolicy#requireRoundExecution` (mã `INVALID_STATE`, "Vòng đã nộp, đã báo cáo hoặc đã khóa.") được `MemberTaskBoardService#publishBlocker` và `POST /help-requests` trả nguyên văn.
  - Tái hiện: `TesterBoardTest#draftArchivedOrderAndOverdueBoundary`, `TesterPointsTest#carryoverThenPublishAfterCutoff` (`pub.beforeCarryover|409|INVALID_STATE`).
  - Thực tế: task chưa hề nộp/báo cáo nhưng thông báo nói "đã nộp, đã báo cáo hoặc đã khóa"; không chỉ ra việc cần làm (TL phải `carryover` trước — PO-03).
  - Mong đợi: mã/thông điệp riêng (ví dụ `CARRYOVER_REQUIRED`) để nút bị tắt giải thích đúng bước tiếp theo (§5 L1).
  - Cách sửa: `HandoffService#requireTransferable` kiểm tra kỳ đã chốt (`now > cycleClosesAt`) và trả `409 CARRYOVER_REQUIRED` ("TL cần chốt deadline xử lý tiếp… trước khi đăng lên Workspace") trước khi rơi vào thông điệp `INVALID_STATE` chung; cờ `publishBlockedCode/Message` của bảng task theo Member dùng cùng nguồn. Mã mới đã ghi vào API_CONTRACT §9. Test hồi quy: `BugRegressionTest#t07_*` (bảng task và `POST /help-requests` cùng trả `CARRYOVER_REQUIRED`).

- [x] **T-08 · Thấp · Phân trang/tham số không nhất quán giữa các endpoint danh sách**
  - Nguồn: Tester · Khu vực: BE — `TaskService` (danh sách task), `GroupController`/`PageParams` (danh sách nhóm), `NotificationController`.
  - Tái hiện: `GET /groups/{g}/tasks?size=0|101|-1` và `?page=-1` → 200 (bị chặn ngầm) trong khi help-requests/points/notifications/checkpoint-cycles trả 400; `GET /groups?size=0` → 200; `GET /notifications?status=BAD` → 200 (bỏ qua giá trị lạ) còn `unreadOnly=maybe` → 400; `GET /groups/{g}/tasks?assigneeId=xyz` → 200 rỗng còn `cycleId=xyz` → 400. Test: `TesterTaskTest#taskCreateValidation`, `TesterAuthGroupTest#notificationCases/groupCases`.
  - Mong đợi: API_CONTRACT §phân trang (`size` 1–100, `page` ≥ 0) và §1.7: giá trị sai → 400 `VALIDATION_FAILED` nhất quán.
  - Cách sửa: `GET /groups/{g}/tasks` kiểm tra `page`/`size` bằng `PageParams` như các danh sách khác (`size` 1–100, `page` ≥ 0, không phải số → 400) và `assigneeId` sai định dạng → 400. Hai quan sát còn lại của Tester được xác minh là KHÔNG phải lỗi: `GET /groups` không phân trang (trả toàn bộ nhóm của người dùng nên `size` không có nghĩa) và `status` không phải tham số của `GET /notifications` (tham số lạ bị bỏ qua theo thiết kế; tham số có định nghĩa như `tab`/`unreadOnly` đã trả 400 khi sai). Test hồi quy: `BugRegressionTest#t08_*`.

- [x] **T-09 · Thấp · Một số kiểm tra đầu vào quá lỏng**
  - Nguồn: Tester · Khu vực: BE — `ResultService#validateEvidence`, DTO đăng ký, `HelpDtos`, cấu hình Jackson.
  - Tái hiện: minh chứng `https://user:pw@host/x` (có userinfo) được nhận; URL `https://localhost/...`, `https://127.0.0.1/...` được nhận; trùng URL trong cùng một lần nộp được nhận; `estimatedEffortMinutes: 1.5` bị ép về 1; `privacyChecked:"true"` (chuỗi) được chấp nhận; đăng ký mật khẩu 8 dấu cách (`201`), email `a@b` (`201`). Test: `TesterHandoffTest#helperSubmitAndReview`, `TesterPublishOfferTest#publishValidation`, `TesterAuthGroupTest#authCases`.
  - Mong đợi: minh chứng "HTTPS có host" (API_CONTRACT §S06) — cấm credential trong URL, host nội bộ không dùng được làm minh chứng, không trùng; số nguyên không nhận số thực; mật khẩu không chỉ gồm khoảng trắng; email có miền hợp lệ.
  - Cách sửa: `ResultService#validateEvidence` (đường dùng chung của nộp task, `support-works` và báo cáo checkpoint) từ chối URL có userinfo (`user:pw@`), host nội bộ (`localhost`, `*.localhost`, loopback/private/link-local IPv4, `::1`) và URL trùng nhau (không phân biệt hoa/thường); `application.yml` tắt ép kiểu ngầm của Jackson (`accept-float-as-int=false`, `allow-coercion-of-scalars=false`) nên `1.5` cho số nguyên và `"true"` cho boolean bị 400; đăng ký từ chối mật khẩu toàn khoảng trắng (`@NotBlank`) và email thiếu miền (`a@b`, thêm `@Pattern`). `type` thiếu trong minh chứng vẫn được nhận (mặc định `URL`, đúng hợp đồng). Test hồi quy: `BugRegressionTest#t09_*` (2 test).

- [x] **T-10 · Thấp · Một Member tạo được nhiều đề nghị riêng OPEN trùng cho cùng một task (spam thông báo, cờ "Cần hỗ trợ" khó tắt)**
  - Nguồn: Tester · Khu vực: BE — `SupportRequestService#create`.
  - Tái hiện: `POST /tasks/{id}/support-requests` hai lần liên tiếp → 201 + 201, mỗi lần phát một `NEED_HELP` cho TL; TL `RESOLVED_PRIVATELY` một bản thì bảng task theo Member vẫn `needsHelp=true` do bản còn lại. Test: `TesterBoardTest#needsHelpFlag`.
  - Mong đợi: chặn (409) khi đã có đề nghị riêng chưa đóng cho cùng task của cùng người gửi.
  - Cách sửa: `SupportRequestService#create` trả `409 SUPPORT_REQUEST_ALREADY_OPEN` khi người gửi đã có đề nghị riêng chưa `RESOLVED` cho cùng task (không phát thêm thông báo); sau khi TL xử lý xong thì gửi lại được. Mã mới ghi ở API_CONTRACT §9. Test hồi quy: `BugRegressionTest#t10_*`.

- [x] **T-11 · Thấp · FE — S05 vẫn ghi "Điểm chỉ được ghi sau khi TL nghiệm thu." khi work đã `APPROVED` (đã ghi điểm)**
  - Nguồn: Tester · Khu vực: FE — `SupportWorkSection.tsx` (`handoff.summary` luôn ở thì "đang làm thay … điểm chỉ được ghi sau khi nghiệm thu").
  - Tái hiện: `TesterHandoff.test.tsx` (APPROVED/LEAD): "Bình đang làm thay An. Điểm chỉ được ghi sau khi TL nghiệm thu."
  - Mong đợi: §4 M3 — task đã ghi điểm phải hiển thị khác với "chưa tính điểm".
  - Cách sửa: câu tóm tắt của khối "Nhận làm thay" đổi theo trạng thái (`handoff.summaryDone`: "đã làm thay … và kết quả đã được TL nghiệm thu"; `summaryReturned` khi đã hoàn trả); khi APPROVED không còn câu "Điểm chỉ được ghi sau khi TL nghiệm thu" và nhãn chờ nghiệm thu. Test hồi quy: `Handoff.test.tsx` (`[T-11]`).

- [x] **T-12 · Thấp · TL chấp thuận được đề nghị của người ĐÃ RỜI nhóm (rút từ mục nghi vấn #1 của Tester, đã xác minh bằng SQL `left_at`)**
  - Nguồn: Tester (nghi vấn) → xác nhận là lỗi · Khu vực: BE — `OfferService#accept`.
  - Tái hiện: đặt `project_members.left_at` cho Helper đã đề nghị rồi TL `POST /help-requests/{id}/accept-offer` → 200, task (và `P_t`) được giao cho người không còn trong nhóm (`TesterHandoffTest`, `accept.offerOfLeftMember|200`).
  - Mong đợi: người nhận phải là thành viên đang hoạt động; trả 409 rõ ràng. (Việc TL vẫn đăng được task của người đã rời là đúng — đó chính là tình huống mất liên lạc.)
  - Cách sửa: `OfferService#accept` kiểm tra Helper còn là thành viên đang hoạt động (`isActiveMember`), nếu không trả `409 OFFER_HELPER_NOT_ACTIVE` và không đổi gì (người thực hiện, quyền nhận điểm, work). TL vẫn đăng được task của người đã rời. Test hồi quy: `BugRegressionTest#t12_*`.

### Đợt 2 — Rà soát lộ khóa / secret (24/11/2026)

Nguồn: rà soát tĩnh toàn bộ repo bằng regex (mẫu khóa AWS/Google/GitHub/OpenAI/Anthropic/Slack, khối PEM, JWT cứng, `*secret|password|token|api_key = …`), tìm file `.env`/`*.pem`/`*.key`/keystore, kiểm `.gitignore`, kiểm log có in token/mật khẩu, kiểm cấu hình actuator/CORS. Kết quả: **không có khóa API thật, token, khóa riêng, file `.env` hay keystore nào trong repo**; log không in token/mật khẩu; actuator chỉ mở `health` (`show-details: never`); CORS chỉ cho `http://localhost:5173`. Hai lỗi cấu hình còn lại:

- [x] **H-03 · Trung bình · `application-test.yml` (secret JWT cố định `test-only-…`) nằm trong `src/main/resources` → bị đóng gói vào jar chạy thật; ai bật profile `test` trên máy chủ là dùng một secret công khai**
  - Nguồn: Rà soát secret · Khu vực: BE — `BE/src/main/resources/application-test.yml` (`hanare.jwt.secret: ${HANARE_JWT_SECRET:test-only-hanare-jwt-secret-0123456789abcdef}`); thông báo lỗi `SecurityConfig#jwtSecretKey` còn gợi ý "hoặc dùng profile dev/test".
  - Tái hiện: `java -jar hanare-be.jar --spring.profiles.active=test` (không đặt `HANARE_JWT_SECRET`) → BE khởi động với secret có trong repo → cùng cách giả mạo token như H-01 (ký HS256 với `sub` = userId bất kỳ). Thông báo lỗi khi thiếu secret còn chỉ đường dùng profile `test`.
  - Mong đợi: cấu hình chỉ phục vụ test không nằm trong artifact chạy thật; thông báo lỗi không gợi ý profile có secret công khai.
  - Cách sửa: chuyển `application-test.yml` từ `BE/src/main/resources` sang `BE/src/test/resources` (test vẫn nạp được vì classpath test gồm cả hai; jar chạy thật không còn profile `test` và không còn secret nào có giá trị mặc định); thông báo lỗi của `SecurityConfig#jwtSecretKey` không còn gợi ý "dùng profile dev/test" mà nói rõ chỉ demo/dev tự sinh khóa. Test hồi quy: `JwtSecretTest#noConfigShippedInTheJarCarriesAnyNonEmptyDefaultSecret` (không có `application-test.yml` ở main và không file `.yml`/`.properties` nào ở main có `secret:` với giá trị mặc định không rỗng); yêu cầu harness `S2-Z2`. Lưu ý: bản sao cũ trong `BE/target/classes` là sản phẩm build cũ, đã xóa; build `clean` không còn nó.

- [x] **H-04 · Thấp · `docker-compose.yml` mở PostgreSQL `5432` trên MỌI giao diện mạng với tài khoản `hanare/hanare` → máy khác cùng Wi-Fi/LAN kết nối thẳng vào CSDL**
  - Nguồn: Rà soát secret · Khu vực: `docker-compose.yml` (`ports: "5432:5432"`, `POSTGRES_PASSWORD: hanare`); `application-dev.yml` (mật khẩu mặc định `hanare`).
  - Tái hiện: `docker compose up -d db` rồi từ máy khác trong mạng `psql -h <ip-máy-dev> -U hanare hanare` với mật khẩu `hanare` → vào được toàn bộ dữ liệu (băm mật khẩu, nội dung nhóm). Chỉ ảnh hưởng chế độ dev dùng Docker; demo dùng PostgreSQL nhúng thì không qua file này.
  - Mong đợi: cổng CSDL dev chỉ lắng nghe ở `127.0.0.1` (mật khẩu mặc định là chấp nhận được cho dev nếu cổng không lộ ra mạng).
  - Cách sửa: `docker-compose.yml` đổi `"5432:5432"` thành `"127.0.0.1:5432:5432"`, BE dev vẫn nối `localhost:5432` bình thường nhưng máy khác trong mạng không còn chạm tới CSDL. Mật khẩu mặc định `hanare` của dev giữ nguyên (chỉ dùng trên máy cá nhân). Kiểm tra tự động: yêu cầu harness `S2-Z2` (grep: không còn dòng `"5432:5432"` mở cho mọi giao diện).

### Nghi vấn từ Tester cần Product Owner quyết (CHƯA tính là lỗi — ghi lại để không bỏ sót)

Không có `- [ ]` nên không chặn harness; mỗi mục có hành vi hiện tại để PO review.

1. **Bản nháp kế hoạch riêng của Member hiện trong bảng task theo Member của TL** (mục `ACTIVE`, `canPublishToWorkspace=false`). Hiện giữ nguyên vì yêu cầu nói "toàn bộ task đang phụ trách".
2. **Sau `REOPEN_REQUEST` người gốc nộp được ngay khi yêu cầu kế nhiệm còn OPEN**; việc duyệt bị chặn `HELP_REQUEST_BLOCKS_REVIEW` cho tới khi TL hủy yêu cầu. Cần chốt chủ ý (T-01 đã sửa phần duyệt sau khi hủy).
3. **`PUT /tasks/{id}/independent-reviewer`** cho phép xóa/đổi người duyệt độc lập kể cả sau khi đã có bản nộp chờ duyệt — TL có thể "chọn lại người duyệt" sau khi thấy kết quả.
4. **`POST /groups`** cho phép `deadline` nhóm trước chu kỳ checkpoint đầu tiên và `startDate = deadline`.
5. `InviteService#latest()` sắp xếp `created_at DESC, id` — chỉ gặp với đồng hồ cố định trong test, không tái hiện trên môi trường thật.
6. Ảnh hưởng thị giác (đỏ/vàng pastel, tiêu đề dài tràn dòng) chưa kiểm bằng mắt (jsdom chỉ kiểm DOM).
