# HANARE BE — Nền tảng dùng chung (`app.hanare.common|security|config`)

**Chạy test:** `BE\mvnw.cmd -B test` (một lớp: `-Dtest=FoundationTest`). Cần JDK ≥ 21, không cần Docker (PostgreSQL 16 nhúng, Zonky 2.2.2).
**Demo không cần Docker:** `run-demo.cmd` (hoặc `BE\mvnw.cmd spring-boot:test-run` — `app.hanare.DemoRunner`, profile `demo`, PG nhúng, cổng 8080; tài khoản ở `GET /api/v1/demo/accounts`, mật khẩu `Demo@1234`; tua giờ bằng `POST /api/v1/demo/clock`). Chỉ profile `demo` có `/demo/*` (profile khác → 404). `VALIDATION_FAILED` luôn là **HTTP 400** trên toàn BE.
**Chạy thật:** `docker compose up -d db`, rồi `BE\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`. Prod: đặt `HANARE_DB_URL/USER/PASSWORD`, `HANARE_JWT_SECRET` (≥32 byte; thiếu → app không khởi động).

**CurrentUser / JWT:** controller khai báo tham số `CurrentUser user` (`id`, `email`); trong service dùng `CurrentUser.require()`. Phát token: `JwtService.issue(userId, email)` → `IssuedToken(token, expiresAt)` (HS256, TTL `hanare.jwt.ttl`=8h, hết hạn tính theo bean `Clock`). Công khai: `/api/v1/auth/**` (trừ `/auth/me`), `/actuator/health`; còn lại cần `Authorization: Bearer`. Hash mật khẩu: bean `PasswordEncoder` (BCrypt).

**GroupAccess** (mọi endpoint có `{projectId}` gọi đầu tiên): `requireMember(projectId, user)` → `GroupMembership(projectId,userId,role)`; `requireLeader(...)`; ngoài nhóm → 404 `NOT_A_GROUP_MEMBER` (không lộ nhóm), Member gọi việc của TL → 403 `LEAD_ONLY`. Thêm: `findActive`, `isActiveMember`, `activeLeaderId`, `activeMemberIds` (fan-out thông báo). Vai trò `GroupRole.LEAD|MEMBER`.

**Lỗi:** ném `ApiException.of(ErrorCode.X, msg)` (mã chung, tên khớp `docs/api-types.ts`), `ApiException.of(HttpStatus, "MA_NGHIEP_VU", msg)` (mã nghiệp vụ theo API_CONTRACT), `ApiException.validation(field, msg)`. `GlobalExceptionHandler` trả `{code,message,fieldErrors[],traceId}` (header `X-Trace-Id`); Bean Validation → `VALIDATION_FAILED`; JSON hỏng → `MALFORMED_REQUEST`; vi phạm unique/FK/trigger DB → 409 `VERSION_CONFLICT`; còn lại 500 `INTERNAL_ERROR` (không lộ chi tiết).

**Thời gian:** luôn đọc "bây giờ" qua `Clock` (bean `ClockConfig`, test thay bằng `FixedClock`). JVM mặc định UTC. JdbcClient **không bind `Instant`**: ghi bằng `DbTime.toDb(instant)` (OffsetDateTime), đọc bằng `DbTime.instant(rs,"col")`.

**Test nền (`src/test/.../testsupport`):**
- `@HanareTest`: Spring context + PG nhúng MỚI (Flyway sạch) mỗi lớp test + `MockMvc`; `@Autowired` được `FixedClock` (mặc định 2026-11-16T03:00Z, `set/advance`), `DbFixtures`, `TestAuth` (`auth.bearer(user)` → header Authorization), `JdbcClient`.
- `AbstractDbTest`: test SQL thuần không Spring (`jdbc`, `fx` sẵn dùng) — nhanh hơn, dùng cho ràng buộc DB.
- `DbFixtures`: `user`, `group(name,size)`/`groupOf5()`, `schedule`, `weeklyCycles`, `approvedTask`, `submission`, `review`, `pointsScenarioFiveMembers()` (A có task 1/2/3 = 33.333/66.667/100.000 units), `submitAndAccept`, `resetData()`. Mẫu: xem `FoundationTest`, `ConstraintsTest`.
- Controller thử nghiệm chỉ cho test: khai báo `@RestController static class` lồng trong lớp test + `@Import(...)` (không bị quét vào context khác).
- Mỗi lớp `@HanareTest` khởi động context mới (~3-5s); gộp test vào ít lớp, hoặc dùng `fx.resetData()` giữa các test.
