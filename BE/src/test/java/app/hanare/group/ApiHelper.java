package app.hanare.group;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.TestAuth;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gọi API thật qua MockMvc cho các test BE-A (tạo nhóm, nhận lời mời...). */
public final class ApiHelper {

	public static final JsonMapper JSON = JsonMapper.builder().build();

	private ApiHelper() {
	}

	public static String groupBody(String name, String deadline, int weekday, String cutoff, String tz) {
		return """
				{"name":"%s","deadline":"%s","schedule":{"weekday":%d,"cutoffTime":"%s","timezone":"%s"},"scheduleAgreed":true}
				""".formatted(name, deadline, weekday, cutoff, tz);
	}

	/** Tạo nhóm qua API; trả về toàn bộ {@code CreateGroupResponse}. */
	public static JsonNode createGroup(MockMvc mvc, TestAuth auth, UserRef lead, String name, int weekday, String cutoff)
			throws Exception {
		String body = mvc.perform(post("/api/v1/groups").header("Authorization", auth.bearer(lead))
				.contentType(MediaType.APPLICATION_JSON)
				.content(groupBody(name, "2026-12-20", weekday, cutoff, "Asia/Ho_Chi_Minh")))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JSON.readTree(body);
	}

	public static UUID id(JsonNode node, String pointer) {
		return UUID.fromString(node.at(pointer).asString());
	}

	public static JsonNode getJson(MockMvc mvc, String bearer, String url) throws Exception {
		return JSON.readTree(mvc.perform(get(url).header("Authorization", bearer)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString());
	}
}
