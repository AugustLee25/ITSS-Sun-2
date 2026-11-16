package app.hanare.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * JdbcClient/pgjdbc không bind {@link Instant} trực tiếp cho timestamptz: dùng {@link OffsetDateTime} (UTC)
 * khi ghi ({@code .param("at", DbTime.toDb(instant))}) và đọc bằng {@link #instant}.
 */
public final class DbTime {

	private DbTime() {
	}

	public static OffsetDateTime toDb(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	public static Instant fromDb(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	/** Đọc cột timestamptz thành Instant (null nếu SQL NULL). */
	public static Instant instant(ResultSet rs, String column) throws SQLException {
		return fromDb(rs.getObject(column, OffsetDateTime.class));
	}
}
