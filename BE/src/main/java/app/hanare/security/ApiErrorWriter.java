package app.hanare.security;

import app.hanare.common.ApiError;
import app.hanare.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Ghi {@link ApiError} JSON ở tầng filter (401/403 do Spring Security, trước khi tới controller advice). */
@Component
public class ApiErrorWriter {

	private final JsonMapper mapper;

	public ApiErrorWriter(JsonMapper mapper) {
		this.mapper = mapper;
	}

	public void write(HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		if (code.status().value() == 401) {
			response.setHeader("WWW-Authenticate", "Bearer");
		}
		mapper.writeValue(response.getOutputStream(), ApiError.of(code, null, null));
	}
}
