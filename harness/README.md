# Harness nghiệm thu Solution 2

Vòng kiểm chứng tự động để biết **code đã đáp ứng `HANARE_SOLUTION2_CORE_REQUIREMENTS.md` hay chưa**, thay vì chỉ tin "test xanh" (test cũ chỉ chứng minh đúng với spec v1.2).

```
requirements.json  ──►  run-harness.ps1  ──►  reports/latest.md      (bảng PASS/FAIL từng yêu cầu + chứng cứ)
 (30 yêu cầu truy vết)      │                  reports/NEXT_ACTIONS.md (việc phải sửa tiếp, kèm lỗi cụ thể)
                            │                  reports/history.log    (lịch sử các vòng)
                            ├─ BE: mvnw test  → đọc BE/target/surefire-reports/*.xml
                            ├─ FE: eslint + vitest (json) + build
                            └─ tĩnh: grep (nhãn có mặt, màn cũ không còn) + tài liệu PO
```

## Chạy

```
harness\harness.cmd                                              # đầy đủ (≈ 4–5 phút): BE + FE + yêu cầu
harness\harness.cmd -Stages static,fe -SkipBuild                 # nhanh: chỉ FE
harness\harness.cmd -Stages static,be -BeTests Solution2AcceptanceTest   # nhanh: chỉ test nghiệm thu BE
harness\harness.cmd -MaxIterations 5 -FixCommand "<lệnh sửa>"    # tự lặp: sau mỗi vòng đỏ chạy lệnh sửa
```

Mã thoát `0` ⇔ **ALL GREEN**: mọi yêu cầu PASS **và** BE/FE xanh **và** vòng chạy là đầy đủ (không thu hẹp `-Stages`/`-BeTests`). Chạy thu hẹp chỉ để lặp nhanh, không bao giờ báo ALL GREEN.

## Quy ước truy vết

Một yêu cầu PASS khi **mọi** check của nó đạt. Loại check (`requirements.json`):

| kind | Ý nghĩa |
|---|---|
| `be` | test BE cụ thể `Class#method` phải tồn tại và qua (có sẵn từ trước hoặc mới) |
| `be-tag` | mọi test BE có tên chứa tag (vd `s2_B2_…`) phải qua; tối thiểu `min` test — **thiếu test = FAIL** |
| `fe-tag` | mọi test FE có tiêu đề chứa `[S2-B2]` phải qua; tối thiểu `min` |
| `grep` | nhãn/chuỗi phải có (`present`) hoặc màn cũ không còn (`absent`) trong một file |
| `doc` | tài liệu phải ghi điểm cần PO chốt (vd `PO-02`) |

Khi thêm yêu cầu mới: thêm vào `requirements.json`, viết test có tag, chạy harness — nó đỏ (MISSING) cho tới khi có test + code.

## Vòng lặp làm việc

1. `harness.cmd` → mở `reports/NEXT_ACTIONS.md`.
2. Sửa code/test cho các yêu cầu FAIL/MISSING.
3. Chạy lại bản thu hẹp để lặp nhanh; khi hết đỏ, chạy **đầy đủ** lần cuối.
4. Lặp tới `ALL GREEN`.
