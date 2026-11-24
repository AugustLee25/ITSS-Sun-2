# HANARE — Đối chiếu Solution 2 (Hỗ trợ tự nguyện & ghi nhận đóng góp)

Nguồn yêu cầu: `HANARE_SOLUTION2_CORE_REQUIREMENTS.md` (bản dự thảo, chờ Product Owner review).
Tài liệu đó ghi rõ *"chưa khẳng định source code hiện tại đã đáp ứng"*, nên file này là kết quả **đối chiếu từng yêu cầu ↔ code ↔ test tự động**, do harness `harness/` kiểm chứng.

> Chạy kiểm chứng: `harness\harness.cmd` (đầy đủ BE + FE + đối chiếu yêu cầu) — báo cáo ở `harness/reports/latest.md`, việc còn lại ở `harness/reports/NEXT_ACTIONS.md`. Chi tiết cách dùng: [`harness/README.md`](../harness/README.md).

## 1. Hiện trạng trước khi đối chiếu

Code đã dựng theo spec v1.2 (14 màn S00–S13). Bộ test cũ (BE 183, FE 162) đều xanh nhưng chỉ chứng minh đúng với spec cũ. Đối chiếu với tài liệu Solution 2 phát hiện các lệch sau (đã sửa):

| # | Yêu cầu Solution 2 | Hiện trạng trước | Đã làm |
|---|---|---|---|
| 1 | L1: Dashboard TL — click Member xem **toàn bộ** task; quá hạn đầu + đỏ pastel; cần hỗ trợ vàng pastel; DONE cuối; nút `Đưa lên Workspace` | Chỉ có danh sách task **theo một kỳ**, không phân nhóm/màu, không có nút đưa lên Workspace | Endpoint mới `GET …/dashboard/members/{userId}/tasks` (chỉ TL) + `MemberTaskBoard` ở S03 |
| 2 | Không có màn `My Support Work` / màn thực hiện hỗ trợ riêng; Helper dùng My Tasks/checkpoint | Có S10; sau `Chấp thuận` FE chuyển sang S10 | Bỏ S10; `Chấp thuận` về chính task; khối "Nhận làm thay" trong S05; link cũ chỉ chuyển hướng |
| 3 | Không có màn nghiệm thu riêng; review chung của Solution 1 gắn nhãn `Nhận làm thay` | Có S11 riêng | Bỏ S11; khung duyệt S05 hiện nhãn + Helper/người gốc/minh chứng/`P_t` |
| 4 | Helper nộp qua popup báo cáo task hiện có | BE chặn nộp qua S05 (`INVALID_STATE`), chỉ nộp được ở S10/S06 | `POST /task-rounds/{id}/submissions` cho Helper, vẫn liên kết contribution |
| 5 | Nút `Cần giúp đỡ` ở My Tasks và ở task trong checkpoint tuần | Tên "Báo khó khăn"; S04 không có nút; S06 chỉ hiện khi đã chọn mức nguy cơ | Đổi tên; thêm ở S04; S06 hiện ở **mọi** task |
| 6 | `Chưa tính điểm — chờ nghiệm thu` cho task nhận giúp trước khi duyệt | Nhãn "Chưa ghi điểm" chỉ ở S10 | Có ở thẻ task, S05, khung duyệt, S12 |
| 7 | Rule 8/9: Helper không tiếp tục → quyết định có lịch sử; người gốc không tự giành lại | Sau `CANCEL_REQUEST` người gốc **bị chặn nộp** (work đã huỷ vẫn "giữ" task) — lỗi tiềm ẩn | Sửa `TaskViews.supportWorks`; test `s2_D8` |
| 8 | §8.14: người gốc/Helper kiểm tra lịch sử bàn giao theo quyền | Chỉ xem được ở S10 | `lastSupportWorkId` → xem ngay trong S05 (cả sau khi `DONE`) |

## 2. Ma trận yêu cầu → xác minh

Mỗi dòng dưới đây là một yêu cầu trong `harness/requirements.json`; harness chỉ báo **ALL GREEN** khi mọi dòng PASS.

| ID | Yêu cầu (tham chiếu §) | Xác minh tự động |
|---|---|---|
| S2-A1 | Nút `Cần giúp đỡ` ở My Tasks (S03/S04) và task checkpoint tuần S06 (§4, §6.1, §8.1) | FE `[S2-A1]` ×4 (Overview, Tasks, TaskDetail, Checkpoint) |
| S2-A2 | Gửi riêng TL; người khác không xem; không đổi task/deadline/điểm (§6.1) | BE `SupportTest.*` + `s2_A2_*` |
| S2-A3 | Không rò nội dung riêng qua Workspace/Dashboard/thông báo/API (§6.11) | BE `s2_A3_*` (Member B/C/D đọc 9 API), `NotificationTest.openNever…` |
| S2-B1…B5 | Dashboard TL: toàn bộ task, quá hạn đầu + đỏ pastel, cần hỗ trợ vàng pastel + một dòng, DONE cuối, `Đưa lên Workspace` (§5 L1, §8.3–8.4) | BE `s2_B1…B5_*`; FE `[S2-B1…B5]` ở `Overview.test.tsx` |
| S2-C1 | Chỉ TL đăng (§3, §8.6) | BE `HelpFlowTest.memberCannotPublish…`; FE `[S2-C1]` |
| S2-C2 | Đăng task chưa nghiệm thu, `P_t` cố định từ task gốc (§8.5) | BE `HelpFlowTest.leadPublishesWholeTask…`, `PointsAcceptanceTest.publishedPriceNeverChanges…` |
| S2-C3 | Workspace đủ trường (M1) | FE `[S2-C3]` |
| S2-C4 | Đề nghị tự nguyện/rút; chưa chọn ≠ bàn giao; không bị phạt (§8.7) | BE `HelpFlowTest.offersAreVoluntary…`, `s2_C4_*`; FE `[S2-C4]` |
| S2-C5 | Điều kiện "chỉ nhận điểm sau khi TL nghiệm thu"; không có "Nhận việc ngay" (M2) | FE `[S2-C5]` + grep i18n |
| S2-C6 | Danh sách đề nghị: `Chấp thuận` / `Không chọn` (L2) | FE `[S2-C6]` ×2 |
| S2-C7 | Cùng `task_id`, đổi người thực hiện, giữ lịch sử, điểm xác nhận không đổi (§8.8) | BE `HelpFlowTest.acceptingAnOffer…`, `s2_C7_*` |
| S2-C8 | Task vào My Tasks/checkpoint của Helper; không có `My Support Work` | BE `s2_C8_*`; FE `[S2-C8]` ×3 + grep `router.tsx`, `S09` |
| S2-C9 | Chặn DONE/SUBMITTED/đã có Helper/nội dung-deadline đổi | BE `HelpFlowTest.onlyUnsubmittedOrRework…`, `openOfferRequiresReconfirmation…` |
| S2-D1 | Helper nộp qua popup `Gửi kết quả`/S06 → SUBMITTED, chưa tính điểm (§8.9) | BE `s2_D1_*`, `HelpFlowTest.helperCheckpointUses…`; FE `[S2-D1]` |
| S2-D2 | Review chung có nhãn `Nhận làm thay` + Helper + người gốc + minh chứng + `P_t` | FE `[S2-D2]` |
| S2-D3 | Duyệt đạt → DONE, Helper +`P_t` đúng một lần (§8.10, 8.12) | BE `HelpFlowTest.helperSubmissionThenAccepted…`, `PointsAcceptanceTest.transferTakeOver…` |
| S2-D4 | Chưa đạt/chưa duyệt không đổi điểm; giữ ID/quỹ/lịch sử (§8.11) | BE `s2_D4_*`, `HelpFlowTest.revisionRequestedKeepsPrice…` |
| S2-D5 | Không cộng trùng (double-click/hai API/retry) (§8.13) | BE `PointsAcceptanceTest.concurrentAndRepeated…`, `PointQueryTest.acceptedResultWritesExactlyOneAward…` |
| S2-D6 | Chỉ TL/người duyệt độc lập duyệt; Helper và người gốc không tự duyệt | BE `s2_D6_*`, `HelpFlowTest.leadsOwnTask…` |
| S2-D7 | Điểm đã nhận/chưa nhận/khả năng nhận tách bạch; `Chưa tính điểm — chờ nghiệm thu` (M3) | BE `PointQueryTest.meBeforeAndAfter…`; FE `[S2-D7]` |
| S2-D8 | Helper không thể tiếp tục → quyết định + lịch sử, không tự chuyển (§6.8) | BE `HelpFlowTest.helperCanReportUnable…`, `s2_D8_*`; FE `[S2-D8]` ×2 |
| S2-D9 | Người gốc không tự giành lại (§6.9) | BE `s2_D9_*` |
| S2-D10 | Mất liên lạc: không tự ép ai nhận (§6.10) | BE `s2_D10_*` + mục PO-02 bên dưới |
| S2-D11 | Người gốc/Helper xem lịch sử bàn giao theo quyền (§8.14) | BE `s2_D11_*`; FE `[S2-D11]` ×2 |
| S2-D12 | Bàn giao không sửa snapshot/kỳ cũ (§6.12) | BE `s2_D12_*` |
| S2-D13 | Thông báo dẫn đúng đích, không còn màn S10/S11 | BE `NotificationTest.targetsFollow…`; FE `[S2-D13]` ×4 |

## 3. Điểm cần Product Owner chốt (mục 9 của tài liệu yêu cầu)

Các mục này **chưa được tự quyết**; bảng ghi hành vi hiện tại để PO review.

| Mã | Câu hỏi | Hành vi hiện tại |
|---|---|---|
| **PO-01** | Công thức `P_t` (trọng số EASY/MEDIUM/HARD = 1/2/3, phân bổ theo tổng trọng số) | Đã cài theo đề xuất thiết kế: `P_t = (B_i − R_i) × w / W_i`, `B_i = 100/N` (phần dư lớn nhất), quỹ tổng 100% (`PointPolicyTest`, `PointMathTest`). Cần PO xác nhận trước khi lập quỹ điểm thật. |
| **PO-02** | Mất liên lạc: TL có được chuyển trách nhiệm + toàn bộ `P_t` khi người gốc không phản hồi, sau quy trình xác minh/thông báo? | **Chưa có quy trình xác minh riêng.** Hệ thống không bao giờ tự giao việc/tự ép ai nhận (kiểm bởi `s2_D10_*`). TL đăng được kể cả khi người gốc chưa bấm `Cần giúp đỡ` (L1-2) và chấp thuận một Helper tự nguyện; người gốc chỉ nhận **thông báo** `POINT_ASSIGNMENT_CHANGED`, không có bước xác nhận. Nếu PO muốn bắt buộc bước xác minh/đồng thuận trước khi chuyển điểm, cần thêm trạng thái riêng — chưa làm. |
| **PO-03** | Helper được chọn sau checkpoint của task đã qua | TL phải chốt deadline xử lý tiếp (carryover) trước; `accept-offer` trả `409` tới khi đó, đề nghị cũ phải xác nhận lại (`HelpFlowTest.openOfferRequiresReconfirmation…`). Trường hợp sát deadline dự án chưa có quy tắc riêng. |
| **PO-04** | Hỗ trợ một phần | MVP chỉ chuyển **toàn bộ** task; không có tách task/quỹ (đúng đề xuất của tài liệu). |
| **PO-05** | Thông báo ngoài ứng dụng (Slack/Messenger/Zalo) | Chỉ thông báo trong ứng dụng; chưa có kênh ngoài. |

## 4. Giới hạn đã biết

- Kho phát lại phản hồi cho `Idempotency-Key` chưa phủ mọi endpoint của `help`/`points` (xem README); nhưng việc duyệt và ghi điểm **không bao giờ ghi trùng** nhờ ràng buộc DB (`D5`).
- Màn S10/S11 đã bỏ khỏi giao diện; các bảng/endpoint `support_works`, `support_submissions` vẫn là mô hình dữ liệu nội bộ (không có màn tương ứng).
- QA bằng mắt cho Dashboard TL **chưa thực hiện** (công cụ trình duyệt không kết nối được khi kiểm chứng). Đã kiểm bằng: test DOM (`data-pastel`, nhãn chữ), CSS pastel có trong bản build đúng token (đỏ `#fee2e2`/chữ `#7f1d1d`, vàng `#fef3c7`/chữ `#78350f`), và gọi endpoint mới trên BE demo thật (TL nhận 200 với task quá hạn lên đầu; Member nhận 403). Nên xem bằng mắt trước khi nghiệm thu: `run-demo.cmd` → đăng nhập `tl@hanare.demo` → S03 → bấm một Member (vd. Trần Thị Cúc).
