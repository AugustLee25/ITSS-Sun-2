package app.hanare.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gán traceId cho mỗi request (header {@code X-Request-Id} nếu hợp lệ, không thì sinh mới),
 * đưa vào MDC (log) và header phản hồi {@code X-Trace-Id}. Chạy trước Spring Security
 * nên lỗi 401/403 cũng có traceId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String MDC_KEY = "traceId";
	public static final String RESPONSE_HEADER = "X-Trace-Id";
	public static final String REQUEST_HEADER = "X-Request-Id";
	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(REQUEST_HEADER);
		String traceId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : newTraceId();
		MDC.put(MDC_KEY, traceId);
		response.setHeader(RESPONSE_HEADER, traceId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	/** TraceId của request hiện tại (hoặc id mới nếu gọi ngoài request, vd. job). */
	public static String currentTraceId() {
		String id = MDC.get(MDC_KEY);
		return id != null ? id : newTraceId();
	}

	private static String newTraceId() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
