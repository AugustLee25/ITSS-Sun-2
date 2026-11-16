package app.hanare.group;

import java.util.UUID;

/** Tham chiếu người dùng trong API (không bao giờ kèm email). */
public record UserRef(UUID userId, String name) {
}
