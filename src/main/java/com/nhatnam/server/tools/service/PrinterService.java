package com.nhatnam.server.tools.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.entity.FileAsset;
import com.nhatnam.server.tools.repository.FileAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.standard.PrinterIsAcceptingJobs;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tìm và điều khiển máy in.
 *
 * PHẠM VI — đọc kỹ trước khi dùng:
 * Trình duyệt không có API nào để dò máy in trong mạng LAN. Vì vậy việc dò tìm
 * được làm ở MÁY CHỦ: nó quét mDNS/Bonjour (_ipp._tcp) và hỏi CUPS. Nghĩa là
 * máy in tìm thấy là máy in cùng mạng với MÁY CHỦ, không phải cùng mạng với
 * người đang bấm nút.
 *
 * Với văn phòng nội bộ (server và máy in cùng LAN) thì đây đúng là thứ cần.
 * Nếu người dùng ở nhà và muốn in ra máy in nhà họ, cách duy nhất là in qua
 * trình duyệt — frontend có sẵn lựa chọn "In qua trình duyệt" cho tình huống đó.
 *
 * Yêu cầu hệ thống: CUPS (lp, lpstat, lpadmin, lpinfo). macOS có sẵn; Ubuntu
 * cài bằng `apt install cups-client avahi-utils`.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class PrinterService {

    private static final long CMD_TIMEOUT_SECONDS = 25;

    private final FileStorageService  storage;
    private final FileAssetRepository repo;

    // ════════════════════════════════════════════════════════════════
    // Danh sách máy in đã kết nối
    // ════════════════════════════════════════════════════════════════

    /**
     * Máy in đã cài trên máy chủ.
     *
     * Ưu tiên javax.print vì nó chạy trên mọi hệ điều hành kể cả Windows;
     * bổ sung thêm trạng thái chi tiết từ `lpstat -p` nếu có CUPS.
     */
    public List<Map<String, Object>> installed() {
        Map<String, String> states = cupsStates();
        PrintService defaultSvc = PrintServiceLookup.lookupDefaultPrintService();
        String defaultName = defaultSvc != null ? defaultSvc.getName() : null;

        List<Map<String, Object>> out = new ArrayList<>();
        for (PrintService svc : PrintServiceLookup.lookupPrintServices(null, null)) {
            var accepting = svc.getAttribute(PrinterIsAcceptingJobs.class);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",      svc.getName());
            m.put("isDefault", svc.getName().equals(defaultName));
            m.put("ready",     accepting == null || PrinterIsAcceptingJobs.ACCEPTING_JOBS.equals(accepting));
            m.put("status",    states.getOrDefault(svc.getName(), "Sẵn sàng"));
            out.add(m);
        }
        return out;
    }

    /** name → dòng trạng thái, đọc từ `lpstat -p`. Không có CUPS thì trả map rỗng. */
    private Map<String, String> cupsStates() {
        Map<String, String> map = new HashMap<>();
        var res = run(List.of("lpstat", "-p"));
        if (!res.ok()) return map;

        // "printer HP_LaserJet is idle.  enabled since ..."
        Pattern p = Pattern.compile("^printer\\s+(\\S+)\\s+(.*)$");
        for (String line : res.output().split("\\R")) {
            Matcher m = p.matcher(line.trim());
            if (m.matches()) map.put(m.group(1), vietnamize(m.group(2)));
        }
        return map;
    }

    private static String vietnamize(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("is idle"))     return "Rảnh";
        if (t.contains("now printing")) return "Đang in";
        if (t.contains("disabled"))    return "Đang tắt";
        return s;
    }

    // ════════════════════════════════════════════════════════════════
    // Dò tìm trong mạng
    // ════════════════════════════════════════════════════════════════

    /**
     * Dò máy in trong cùng mạng với máy chủ.
     *
     * Hai nguồn, gộp lại và khử trùng theo URI:
     *   1. `lpinfo -v`  — CUPS liệt kê mọi backend nó thấy (dnssd, ipp, socket, usb)
     *   2. avahi-browse / dns-sd — mDNS thuần, bắt được máy in mà CUPS chưa biết
     */
    public List<Map<String, Object>> discover() {
        Map<String, Map<String, Object>> byUri = new LinkedHashMap<>();

        for (Map<String, Object> p : discoverViaCups())  byUri.putIfAbsent((String) p.get("uri"), p);
        for (Map<String, Object> p : discoverViaMdns())  byUri.putIfAbsent((String) p.get("uri"), p);

        // Máy đã cài rồi thì đánh dấu để frontend hiện "Đã kết nối" thay vì nút Thêm
        Set<String> installedNames = new HashSet<>();
        for (var m : installed()) installedNames.add(String.valueOf(m.get("name")).toLowerCase(Locale.ROOT));

        List<Map<String, Object>> out = new ArrayList<>(byUri.values());
        for (var p : out) {
            String suggested = slug(String.valueOf(p.get("name")));
            p.put("connected", installedNames.contains(suggested.toLowerCase(Locale.ROOT)));
            p.put("suggestedName", suggested);
        }
        return out;
    }

    private List<Map<String, Object>> discoverViaCups() {
        var res = run(List.of("lpinfo", "--include-schemes", "dnssd,ipp,ipps,socket,lpd", "-v"));
        if (!res.ok()) return List.of();

        List<Map<String, Object>> out = new ArrayList<>();
        // "network ipp://HP%20LaserJet._ipp._tcp.local/"
        for (String line : res.output().split("\\R")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length < 2) continue;
            if (!"network".equals(parts[0]) && !"direct".equals(parts[0])) continue;

            String uri = parts[1].trim();
            out.add(printerEntry(nameFromUri(uri), uri, "CUPS"));
        }
        return out;
    }

    private List<Map<String, Object>> discoverViaMdns() {
        // avahi (Linux) trả về ngay với -t; dns-sd (macOS) chạy mãi nên phải có timeout
        var res = run(List.of("avahi-browse", "-rtp", "_ipp._tcp"));
        if (!res.ok()) return List.of();

        List<Map<String, Object>> out = new ArrayList<>();
        // "=;eth0;IPv4;HP LaserJet;_ipp._tcp;local;host.local;192.168.1.50;631;..."
        for (String line : res.output().split("\\R")) {
            if (!line.startsWith("=")) continue;
            String[] f = line.split(";");
            if (f.length < 9) continue;

            String name = f[3].replace("\\032", " ").trim();
            String host = f[7].trim();
            String port = f[8].trim();
            out.add(printerEntry(name, "ipp://" + host + ":" + port + "/ipp/print", "mDNS"));
        }
        return out;
    }

    private static Map<String, Object> printerEntry(String name, String uri, String via) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("uri",  uri);
        m.put("via",  via);
        return m;
    }

    private static String nameFromUri(String uri) {
        try {
            String decoded = java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("//([^/._]+)").matcher(decoded);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {}
        return uri;
    }

    /** CUPS chỉ nhận tên chữ-số-gạch, tên có dấu cách sẽ bị lpadmin từ chối */
    private static String slug(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9_-]", "_")
                .replaceAll("_+", "_");
        return s.isBlank() ? "printer" : s;
    }

    // ════════════════════════════════════════════════════════════════
    // Kết nối
    // ════════════════════════════════════════════════════════════════

    /**
     * Thêm máy in vào CUPS. `-m everywhere` dùng IPP Everywhere, không cần
     * driver riêng — gần như mọi máy in sản xuất sau 2015 đều hỗ trợ.
     */
    public String connect(String uri, String name) {
        if (uri == null || uri.isBlank()) throw new ToolsException("Thiếu địa chỉ máy in.");
        String printerName = slug(name == null || name.isBlank() ? nameFromUri(uri) : name);

        var res = run(List.of("lpadmin", "-p", printerName, "-E", "-v", uri, "-m", "everywhere"));
        if (!res.ok()) {
            throw new ToolsException(
                    "Không kết nối được máy in. Kiểm tra máy in đã bật và cùng mạng với máy chủ.",
                    "lpadmin thất bại: " + res.output());
        }
        log.info("[Print] Đã thêm máy in {} → {}", printerName, uri);
        return printerName;
    }

    // ════════════════════════════════════════════════════════════════
    // In
    // ════════════════════════════════════════════════════════════════

    /**
     * Gửi lệnh in một tệp trong kho.
     *
     * @param options copies, range ("1-5"), sides (one-sided|two-sided-long-edge),
     *                media (A4|Letter), color (true|false), orientation (portrait|landscape)
     */
    public String print(Long fileId, String printer, Map<String, Object> options) {
        FileAsset asset = repo.findById(fileId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy tệp cần in."));
        Path path = storage.pathOf(asset);
        if (!Files.exists(path)) throw new ToolsException("Tệp không còn trên máy chủ.");

        List<String> cmd = new ArrayList<>(List.of("lp"));
        if (printer != null && !printer.isBlank()) { cmd.add("-d"); cmd.add(printer); }

        int copies = intOf(options.get("copies"), 1);
        if (copies > 1) { cmd.add("-n"); cmd.add(String.valueOf(Math.min(copies, 99))); }

        String range = str(options.get("range"));
        // Chỉ nhận đúng dạng "1-5" hoặc "1,3,7" — chuỗi tự do đi thẳng vào lệnh
        // shell là một lỗ hổng, dù ProcessBuilder không qua shell vẫn nên chặn
        if (range != null && range.matches("[0-9,\\-]{1,40}")) { cmd.add("-P"); cmd.add(range); }

        String sides = str(options.get("sides"));
        if (sides != null && sides.matches("one-sided|two-sided-long-edge|two-sided-short-edge")) {
            cmd.add("-o"); cmd.add("sides=" + sides);
        }

        String media = str(options.get("media"));
        if (media != null && media.matches("[A-Za-z0-9_-]{1,20}")) {
            cmd.add("-o"); cmd.add("media=" + media);
        }

        if (Boolean.FALSE.equals(options.get("color"))) {
            cmd.add("-o"); cmd.add("ColorModel=Gray");
        }
        if ("landscape".equals(str(options.get("orientation")))) {
            cmd.add("-o"); cmd.add("orientation-requested=4");
        }

        cmd.add("--");            // chặn tên file bắt đầu bằng "-" bị hiểu thành tham số
        cmd.add(path.toString());

        var res = run(cmd);
        if (!res.ok()) {
            throw new ToolsException("Gửi lệnh in thất bại.", "lp thất bại: " + res.output());
        }
        // "request id is HP_LaserJet-42 (1 file(s))"
        Matcher m = Pattern.compile("request id is (\\S+)").matcher(res.output());
        String jobId = m.find() ? m.group(1) : "";
        log.info("[Print] Đã gửi {} tới {} (job {})", asset.getOriginalName(), printer, jobId);
        return jobId;
    }

    // ════════════════════════════════════════════════════════════════
    // Chạy lệnh hệ thống
    // ════════════════════════════════════════════════════════════════

    private record Result(boolean ok, String output) {}

    /**
     * Chạy lệnh với timeout. Máy chủ có thể KHÔNG cài CUPS/avahi — lúc đó
     * ProcessBuilder ném IOException, coi như "không tìm thấy gì" chứ không sập
     * cả API. Nhờ vậy trang Tệp vẫn chạy bình thường trên máy dev Windows.
     */
    private Result run(List<String> cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();

            StringBuilder sb = new StringBuilder();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(false, "Lệnh chạy quá lâu: " + String.join(" ", cmd));
            }
            return new Result(p.exitValue() == 0, sb.toString());

        } catch (Exception e) {
            log.debug("[Print] Không chạy được '{}': {}", String.join(" ", cmd), e.getMessage());
            return new Result(false, e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static int intOf(Object o, int fallback) {
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return fallback; }
    }
}
