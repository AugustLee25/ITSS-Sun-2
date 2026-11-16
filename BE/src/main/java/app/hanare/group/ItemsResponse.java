package app.hanare.group;

import java.util.List;

/** Danh sách không phân trang: {@code { "items": [...] }}. */
public record ItemsResponse<T>(List<T> items) {
}
