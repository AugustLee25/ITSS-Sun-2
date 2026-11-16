# RESEARCH — HANARE (15/11/2026)

Mục A đã chạy thật (Windows 11, JDK 26.0.1, PG 16.15); B–D từ tài liệu chính thức/npm.

## A. Spring Boot 4.1.2 (BE)
- **BOM 4.1.2** ([release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)): Framework 7.0.10, Security 7.1.2, Flyway 12.4.0, Jackson 3.1.7 (`tools.jackson`, bean `JsonMapper`, `@JacksonComponent`), driver PG 42.7.14.
- **Tên module đổi** ([migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)): `spring-boot-starter-webmvc`; `spring-boot-starter-security-oauth2-resource-server` (tên cũ vẫn tải được nhưng deprecated); Flyway cần `spring-boot-starter-flyway` + `flyway-database-postgresql`; test là `spring-boot-starter-<x>-test`. `@SpringBootTest` không còn tự có MockMvc: thêm `@AutoConfigureMockMvc`; `@MockitoBean` thay `@MockBean`.
- **JWT HS256:** Boot không có property secret-key; tự khai bean (khóa ≥ 32 byte):
```java
var key = new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256");
JwtEncoder enc = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
JwtDecoder dec = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
enc.encode(JwtEncoderParameters.from(JwtClaimsSet.builder().subject(id).issuedAt(now).expiresAt(exp).build()));
```
`withSecretKey` (Security 7.0+, [mã nguồn](https://github.com/spring-projects/spring-security/blob/main/oauth2/oauth2-jose/src/main/java/org/springframework/security/oauth2/jwt/NimbusJwtEncoder.java)) tự đặt header HS256+`typ=JWT`; `new NimbusJwtEncoder(jwkSource)` mặc định RS256 nên hỏng với khóa HMAC. Lệch giờ mặc định 60 s ([doc](https://docs.spring.io/spring-security/reference/7.2/servlet/oauth2/resource-server/jwt.html)); 4.1 thêm `…jwt.authority-prefix`.
- **JdbcClient** ([doc](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)), đã thử: `sql("insert … returning id").param("id", uuid).query(UUID.class).single()`; `param("ids", List<UUID>)` mở rộng `IN (:ids)`; `query(Rec.class)` ánh xạ snake_case→record (UUID, `BigDecimal` scale 4, `OffsetDateTime`/`Instant`/`LocalDate`); `update()` trả số dòng. **Bẫy:** bind `Instant` → `BadSqlGrammarException`; bind `instant.atOffset(UTC)`. Đọc `Instant` thì được.
- **Zonky** ([README](https://github.com/zonkyio/embedded-postgres)): `io.zonky.test:embedded-postgres:2.2.2` chạy được trên JDK 26 (lần đầu ~8 s). Mặc định PG **14.22**; muốn PG 16 như compose, import `io.zonky.test.postgres:embedded-postgres-binaries-bom:16.15.0` (pom, import) trước Boot BOM. `EmbeddedPostgres.builder().start()` một lần/JVM (static; có shutdown hook, đã kiểm không sót tiến trình), `@DynamicPropertySource` đặt `spring.datasource.url=jdbc:postgresql://localhost:<port>/postgres`, user/pass `postgres`; Flyway chạy khi context lên.
- **Chống chạy trùng** ([INSERT](https://www.postgresql.org/docs/16/sql-insert.html), [FOR UPDATE](https://www.postgresql.org/docs/16/sql-select.html)), đã kiểm: `job_run(group_id, job_name, period_key)` PK + `insert … on conflict do nothing`; chỉ chạy khi `update()==1` (lần 2 trả 0; `returning` rỗng khi trùng). Hàng đợi: `select … where due_at<=:now for update skip locked limit N` (tx2 bỏ qua dòng đang khóa).

**Áp dụng cho HANARE:**
- Thêm `spring-boot-starter-security-oauth2-resource-server` vào `BE/pom.xml` (hiện chỉ có `-security`); `JwtEncoder`/`JwtDecoder` từ `HANARE_JWT_SECRET`, fail-fast nếu < 32 byte; `now`/`exp` lấy từ `Clock`.
- Bind thời gian bằng `OffsetDateTime` (một helper chuyển `Instant`); test base: zonky 2.2.2 + binaries BOM 16.15.0, một instance static.
- Claim job và xử lý cùng một transaction; thông báo/job tới hạn dùng `skip locked`; không cần ShedLock.

## B. React stack (npm `latest`, 15/11/2026)
- react/react-dom **19.3.1**; react-router **8.5.1**; @tanstack/react-query **5.107.1**; react-hook-form **7.92.0** + @hookform/resolvers **7.0.6**; zod **4.6.7**; vite **8.3.8** (+@vitejs/plugin-react 6.3.0); vitest **5.1.0**; typescript 7.0.2; @testing-library/react 16.3.3.
- React Router 8 ([changelog](https://github.com/remix-run/react-router/blob/main/CHANGELOG.md)): Node ≥ 22.22, React ≥ 19.2.7, Vite 7+, ESM-only; **đã bỏ `react-router-dom`** → import từ `react-router` và `react-router/dom`.
- Route modal có URL riêng ([routing](https://reactrouter.com/start/data/routing)): `createBrowserRouter` + `RouterProvider`; route cha render nền và `<Outlet />`, route con (vd. `tasks/:id/help`) render dialog trong Outlet, nên mở trực tiếp URL vẫn có nền. Dialog dùng `<dialog>.showModal()` ([MDN](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/dialog): nền inert, Esc đóng, Baseline) và [APG](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/): `aria-labelledby`, trả focus, nút đóng hiển thị.

**Áp dụng cho HANARE:**
- Ghim phiên bản trong `package.json`; chạy `npm ls` kiểm peer deps (zod 4 + resolvers 7 chưa kiểm). Node máy 24.21 đủ yêu cầu.
- Một `RouteDialog` dùng chung cho S06/S07; đóng bằng `navigate(-1)` nếu đã có lịch sử trong app, không thì `navigate(cha, {replace:true})`.
- Bottom sheet = cùng `<dialog>`, CSS neo đáy trên mobile.

## C. UX
- Trello đánh dấu due-date bằng màu cố định, người dùng phàn nàn ([Atlassian Community](https://community.atlassian.com/t5/Trello-questions/Colorblind-mode-at-trello-Web-App/qaq-p/2255675)); Asana phải thêm chế độ color-blind và in đậm quá hạn ([Asana](https://asana.com/inside-asana/new-accessibility-feature-colorblind-friendly)): màu không đủ.
- WCAG 2.2 AA: [1.4.1](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html) không chỉ dùng màu; chữ ≥ 4.5:1, [1.4.11](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) viền/icon ≥ 3:1; [2.5.8](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) mục tiêu ≥ 24 px (spec 44 px vượt mức này); 2.5.7 thao tác kéo phải có cách một chạm; 2.4.11 footer dính không che ô đang focus; 3.3.7 không bắt nhập lại ([What's new](https://www.w3.org/WAI/standards-guidelines/wcag/new-in-22/)); [4.1.3](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html) toast/lỗi dùng `role="status"`/`aria-live="polite"`.
- Linear Inbox ([docs](https://linear.app/docs/inbox)): tự theo dõi việc liên quan, tab ưu tiên tách khỏi phần còn lại, snooze, đã đọc/chưa. Chống spam ([Courier](https://www.courier.com/blog/how-to-reduce-notification-fatigue-7-proven-product-strategies-for-saas), nguồn vendor): gộp cùng loại trong 5–30 phút, một sự kiện một thông báo, quiet hours, việc khẩn bỏ qua gộp.

**Áp dụng cho HANARE:**
- Chip trạng thái = nền + icon khác hình + nhãn chữ; đạt 4.5:1 cho chữ, 3:1 cho viền; dashboard S03 mỗi người một hàng, có nhãn chữ.
- Checkpoint: deep link từ thông báo → chọn trạng thái (1 chạm) → ghi chú tùy chọn → Gửi; lưu nháp.
- Thông báo: khóa khử trùng `(người nhận, loại, đối tượng, kỳ)`, gộp nhắc cùng kỳ, chưa đọc lên đầu; không gửi lại khi trạng thái không đổi.

## D. Chia điểm (phần dư lớn nhất)
Làm hoàn toàn bằng số nguyên `long` theo spec 12.8 (1% = 10.000 units; quỹ 1.000.000):
`base_i = total·w_i / W` (chia nguyên), `rem_i = total·w_i % W`, `left = total − Σbase`; cộng 1 unit cho `left` phần tử có `rem` lớn nhất, hòa thì id nhỏ nhất. So `rem` bằng số nguyên, không dùng double.
- Kiểm bằng Python: total 200.000, w=1/2/3 → base 33.333/66.666/100.000, dư 1, `rem`=1/6·2/6·0 → task w=2 nhận thêm → **3,3333 / 6,6667 / 10,0000**, tổng 20,0000 ✓. Năm task Khó của C: 40.000 mỗi task (4%) ✓. Làm tròn độc lập: 3 task w=1 ra 6,6667×3 = 20,0001 (vượt quỹ); đúng là 66.667/66.667/66.666.
- Hai tầng: quỹ 1.000.000 cho N người (N=3: 333.334/333.333/333.333), rồi `(B_i − R_i)` cho task; R_i giữ riêng. Tách task: chia lại đúng `P_t`.

**Áp dụng cho HANARE:**
- Một hàm thuần `allocate(total, weights, ids)` kèm test bất biến `Σ = total` (ngẫu nhiên w, N).
- Khi khóa phương án, ghi `P_t` (units) vào DB, không tính lại lúc đọc; thứ tự hòa cố định theo `task.id` (UUID) khi khóa.
- Hiển thị `BigDecimal.valueOf(units, 4)`; FE cộng units rồi mới định dạng.
- Lệch tài liệu: brief ghi `numeric(12,4)`, spec 12.8 ghi số nguyên `point_units`. Hai dạng tương đương (`numeric(12,4)` = units/10.000); chọn một dạng lưu duy nhất và ghi vào `SPEC_ISSUES.md`.
