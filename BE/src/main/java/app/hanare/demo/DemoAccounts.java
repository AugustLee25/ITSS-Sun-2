package app.hanare.demo;

import java.util.List;

/** 5 tài khoản của nhóm mẫu (mật khẩu chung). Dùng cho {@link DemoDataLoader} và {@code GET /demo/accounts}. */
final class DemoAccounts {

	static final String PASSWORD = "Demo@1234";
	static final String GROUP_NAME = "Nhóm Lập trình Web";

	record Acc(String name, String email, String role, String note) {
	}

	static final Acc TL = new Acc("Lê Thu Hà", "tl@hanare.demo", "LEAD", "Trưởng nhóm: duyệt kế hoạch/kết quả, đăng yêu cầu hỗ trợ");
	static final Acc AN = new Acc("Nguyễn Văn An", "an@hanare.demo", "MEMBER", "Đã nộp đúng hạn, có điểm đã ghi (10,0000) và task chờ duyệt");
	static final Acc BAO = new Acc("Phạm Quốc Bảo", "bao@hanare.demo", "MEMBER", "Có điểm đã ghi (13,3333); đã đề nghị hỗ trợ một yêu cầu công khai");
	static final Acc CUC = new Acc("Trần Thị Cúc", "cuc@hanare.demo", "MEMBER", "5 task Khó, đang chậm; có đề nghị hỗ trợ riêng và cảnh báo nguy cơ trễ");
	static final Acc DUC = new Acc("Đỗ Minh Đức", "duc@hanare.demo", "MEMBER", "Task bị yêu cầu sửa và đã nộp lại; có đề nghị hỗ trợ riêng đang mở");

	static final List<Acc> ALL = List.of(TL, AN, BAO, CUC, DUC);

	private DemoAccounts() {
	}
}
