package com.nhatnam.server.tools.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.dto.ToolsDto.WatermarkSettings;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
// Import tường minh java.util.List vì java.awt.* ở trên cũng có java.awt.List —
// single-type import được ưu tiên hơn wildcard nên không bị nhập nhằng.
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Gắn watermark lên ảnh và video.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  QUY ƯỚC TỌA ĐỘ (phải khớp tuyệt đối với canvas ở frontend)
 * ═══════════════════════════════════════════════════════════════════════════
 *   settings.x, y : % vị trí TÂM watermark so với khung ảnh/video
 *   settings.scale: bề rộng watermark / bề rộng ẢNH GỐC   (KHÔNG phải bề rộng logo)
 *   rotation      : độ, dương = quay theo chiều kim đồng hồ
 *
 * Frontend vẽ:  wmW = imgW * scale;  wmH = wmW * (logoH / logoW)
 *               drawImage(logo, centerX - wmW/2, centerY - wmH/2, wmW, wmH)
 * Backend phải cho ra đúng con số đó.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  3 LỖI ĐÃ SỬA Ở PHẦN VIDEO (nguyên nhân watermark lệch vị trí khi export)
 * ═══════════════════════════════════════════════════════════════════════════
 *  1. `[1:v]scale=iw*0.28` — trong filter này `iw` là bề rộng của LOGO, không
 *     phải của video. UI thì tính theo bề rộng VIDEO. Logo 1000px + video
 *     1920px ⇒ export ra watermark chỉ bằng ~52% kích thước trên preview.
 *     Hệ số bù `* 2.2` gắn thêm sau đó chỉ đúng với đúng một cặp kích thước
 *     logo/video, đổi file là sai tiếp. → Giờ đọc kích thước thật bằng ffprobe
 *     rồi truyền số pixel tuyệt đối cho ffmpeg.
 *
 *  2. `String.format("%.3f", ...)` dùng Locale mặc định của máy. Máy cài
 *     tiếng Việt cho ra "0,280" thay vì "0.280" — mà dấu phẩy lại là ký tự
 *     phân tách tham số của ffmpeg, nên biểu thức vỡ hoàn toàn và watermark
 *     nhảy về vị trí vô nghĩa. → Ép Locale.ROOT ở mọi chỗ format số.
 *
 *  3. Chưa xử lý rotation và opacity cho video (UI có nhưng export bỏ qua).
 *     Ngoài ra opacity giờ cho vượt 100% bằng cách vẽ chồng nhiều lượt —
 *     xem alphaPasses().
 *     → Thêm filter rotate + colorchannelmixer, và dùng biểu thức
 *     `overlay=CX-w/2:CY-h/2` để ffmpeg tự bù kích thước khung sau khi xoay.
 */
@Service
@Log4j2
public class WatermarkService {

    private static final String LOGO_RESOURCE = "/watermarks/logo.png";
    /** Thời gian tối đa cho một lần chạy ffmpeg */
    private static final long FFMPEG_TIMEOUT_MINUTES = 15;
    /** Số lượt vẽ chồng tối đa — chặn opacity vô lý làm chuỗi filter phình to */
    private static final int MAX_OPACITY_PASSES = 4;

    // ════════════════════════════════════════════════════════════════
    // ẢNH
    // ════════════════════════════════════════════════════════════════

    public byte[] addWatermarkToImage(MultipartFile file, WatermarkSettings s, String outFormat) throws Exception {
        BufferedImage original = ImageIO.read(file.getInputStream());
        if (original == null) throw new IllegalArgumentException("Không đọc được file ảnh");

        BufferedImage watermark = loadLogo();

        boolean png = "png".equalsIgnoreCase(outFormat);
        BufferedImage result = new BufferedImage(
                original.getWidth(), original.getHeight(),
                png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

        Graphics2D g = result.createGraphics();
        // Ảnh gốc có alpha mà xuất JPG thì nền sẽ đen — tô trắng trước
        if (!png) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, original.getWidth(), original.getHeight());
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, null);

        // Cùng công thức với canvas ở frontend
        int wmW = (int) Math.round(original.getWidth() * s.getScale());
        int wmH = (int) Math.round(watermark.getHeight() * ((double) wmW / watermark.getWidth()));
        double centerX = original.getWidth()  * s.getX() / 100.0;
        double centerY = original.getHeight() * s.getY() / 100.0;

        AffineTransform at = new AffineTransform();
        at.translate(centerX, centerY);
        at.rotate(Math.toRadians(s.getRotation()));
        at.translate(-wmW / 2.0, -wmH / 2.0);

        // Nhân thêm vào transform hiện có thay vì setTransform (an toàn hơn nếu
        // sau này có thêm bước vẽ khác trước đó)
        g.transform(at);

        // Độ đậm > 100% → vẽ chồng nhiều lượt, xem alphaPasses()
        for (double alpha : alphaPasses(s.getOpacity())) {
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, (float) clamp(alpha, 0, 1)));
            g.drawImage(watermark, 0, 0, wmW, wmH, null);
        }
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(result, png ? "png" : "jpg", out);
        return out.toByteArray();
    }

    // ════════════════════════════════════════════════════════════════
    // VIDEO
    // ════════════════════════════════════════════════════════════════

    public byte[] addWatermarkToVideo(MultipartFile file, WatermarkSettings s) throws Exception {
        Path in = null, out = null, logoFile = null;
        try {
            in       = Files.createTempFile("wm_in_",  ".mp4");
            out      = Files.createTempFile("wm_out_", ".mp4");
            logoFile = Files.createTempFile("wm_logo_", ".png");

            file.transferTo(in.toFile());

            try (InputStream is = getClass().getResourceAsStream(LOGO_RESOURCE)) {
                if (is == null) throw new IllegalStateException("Không tìm thấy " + LOGO_RESOURCE);
                Files.copy(is, logoFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // ── Kích thước thật của video và của logo ────────────────
            int[] videoSize = probeVideoSize(in);
            int videoW = videoSize[0], videoH = videoSize[1];

            BufferedImage logo = loadLogo();

            // ── Quy đổi sang pixel tuyệt đối, đúng công thức của UI ──
            int wmW = (int) Math.round(videoW * s.getScale());
            int wmH = (int) Math.round(logo.getHeight() * ((double) wmW / logo.getWidth()));
            if (wmW < 1) wmW = 1;
            if (wmH < 1) wmH = 1;

            int centerX = (int) Math.round(videoW * s.getX() / 100.0);
            int centerY = (int) Math.round(videoH * s.getY() / 100.0);

            double opacity = Math.max(0, s.getOpacity());   // >1 = vẽ chồng nhiều lượt
            double radians = Math.toRadians(s.getRotation());

            String filter = buildFilter(wmW, wmH, opacity, radians, centerX, centerY);
            log.info("[Watermark] video {}x{} → wm {}x{} @ ({},{}) | filter={}",
                    videoW, videoH, wmW, wmH, centerX, centerY, filter);

            runFfmpeg(
                    "ffmpeg", "-hide_banner", "-loglevel", "warning",
                    "-i", in.toString(),
                    "-i", logoFile.toString(),
                    "-filter_complex", filter,
                    "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "copy",
                    "-movflags", "+faststart",
                    "-y", out.toString()
            );

            return Files.readAllBytes(out);

        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
            deleteQuietly(logoFile);
        }
    }

    /**
     * Chuỗi filter ffmpeg. Mọi số đều format với Locale.ROOT — máy cài tiếng Việt
     * sẽ sinh dấu phẩy thập phân và làm vỡ biểu thức (xem ghi chú lỗi #2 ở đầu file).
     *
     * `overlay=CX-w/2:CY-h/2` để ffmpeg tự lấy w,h SAU khi xoay, nhờ đó tâm
     * watermark luôn nằm đúng điểm người dùng chọn dù có xoay hay không.
     */
    private String buildFilter(int wmW, int wmH, double opacity, double radians, int cx, int cy) {
        List<Double> passes = alphaPasses(opacity);

        // 1) Chuẩn bị watermark: scale về đúng số pixel, xoay nếu cần
        StringBuilder fc = new StringBuilder("[1:v]format=rgba");
        fc.append(String.format(Locale.ROOT, ",scale=%d:%d", wmW, wmH));
        if (Math.abs(radians) > 1e-6) {
            // ow/oh=rotw/roth → mở rộng khung chứa trọn ảnh sau khi xoay, nền trong suốt
            fc.append(String.format(Locale.ROOT,
                    ",rotate=%.6f:c=none:ow=rotw(%.6f):oh=roth(%.6f)",
                    radians, radians, radians));
        }
        fc.append("[wmbase];");

        // 2) Nhân bản watermark thành N nhánh, mỗi nhánh một mức alpha.
        //    ffmpeg không cho dùng lại cùng một stream nhiều lần nên phải split.
        int n = passes.size();
        if (n == 1) {
            fc.append(String.format(Locale.ROOT,
                    "[wmbase]colorchannelmixer=aa=%.4f[wm0];", passes.get(0)));
        } else {
            fc.append("[wmbase]split=").append(n);
            for (int i = 0; i < n; i++) fc.append("[s").append(i).append("]");
            fc.append(";");
            for (int i = 0; i < n; i++) {
                fc.append(String.format(Locale.ROOT,
                        "[s%d]colorchannelmixer=aa=%.4f[wm%d];", i, passes.get(i), i));
            }
        }

        // 3) Chồng lần lượt lên video.
        //    Dùng biểu thức CX-w/2 để ffmpeg tự lấy w,h SAU khi xoay, nhờ đó tâm
        //    watermark luôn nằm đúng điểm người dùng chọn dù có xoay hay không.
        String current = "[0:v]";
        for (int i = 0; i < n; i++) {
            fc.append(current)
                    .append(String.format(Locale.ROOT,
                            "[wm%d]overlay=%d-w/2:%d-h/2:format=auto", i, cx, cy));
            if (i < n - 1) {
                fc.append("[v").append(i).append("];");
                current = "[v" + i + "]";
            }
        }
        return fc.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // ffmpeg / ffprobe
    // ════════════════════════════════════════════════════════════════

    /** @return [width, height] của luồng video đầu tiên */
    private int[] probeVideoSize(Path video) throws Exception {
        String out = runAndCapture(
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=s=x:p=0",
                video.toString()
        ).trim();

        String[] parts = out.split("[x,]");
        if (parts.length < 2) {
            throw new IllegalStateException("Không đọc được kích thước video (ffprobe trả về: '" + out + "')");
        }
        return new int[]{ Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
    }

    private void runFfmpeg(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = startOrExplain(pb, cmd[0]);

        StringBuilder log0 = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                log0.append(line).append('\n');
                log.debug("[ffmpeg] {}", line);
            }
        }

        if (!p.waitFor(FFMPEG_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("ffmpeg chạy quá " + FFMPEG_TIMEOUT_MINUTES + " phút, đã hủy");
        }
        if (p.exitValue() != 0) {
            log.error("[ffmpeg] thất bại (exit {}):\n{}", p.exitValue(), log0);
            throw new IllegalStateException("Xử lý video thất bại. Chi tiết: "
                    + lastLines(log0.toString(), 3));
        }
    }

    private String runAndCapture(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = startOrExplain(pb, cmd[0]);
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes());
        }
        if (!p.waitFor(2, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("ffprobe treo quá lâu");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("ffprobe lỗi (exit " + p.exitValue() + ")");
        }
        return out;
    }

    /**
     * Chưa cài ffmpeg/ffprobe là lỗi môi trường, không phải bug — báo thẳng
     * thay vì để IOException "Cannot run program" đổ stack trace khó hiểu.
     */
    private Process startOrExplain(ProcessBuilder pb, String program) throws IOException {
        try {
            return pb.start();
        } catch (IOException e) {
            throw new ToolsException(
                    "Máy chủ chưa sẵn sàng xử lý video. Vui lòng liên hệ quản trị hệ thống.",
                    "Chưa cài '" + program + "' hoặc chưa thêm vào PATH của máy chủ. "
                            + "Cài ffmpeg (đã kèm sẵn ffprobe) rồi khởi động lại backend.",
                    e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private BufferedImage loadLogo() throws IOException {
        try (InputStream is = getClass().getResourceAsStream(LOGO_RESOURCE)) {
            if (is == null) throw new IllegalStateException("Không tìm thấy " + LOGO_RESOURCE);
            BufferedImage img = ImageIO.read(is);
            if (img == null) throw new IllegalStateException("File logo không hợp lệ");
            return img;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Quy đổi "độ đậm" sang danh sách lượt vẽ.
     *
     * Cả Java2D lẫn ffmpeg đều chỉ nhận alpha tối đa 1.0 — không có cách nào
     * làm ảnh đậm hơn chính nó trong một lượt vẽ. Muốn đậm hơn thì VẼ CHỒNG
     * nhiều lượt: 1.8 → [1.0, 0.8].
     *
     * Frontend dùng đúng hàm này (alphaPasses trong WatermarkPage.jsx) nên
     * preview khớp với file xuất ra. Sửa một bên thì phải sửa bên kia.
     */
    private static List<Double> alphaPasses(double opacity) {
        List<Double> out = new ArrayList<>();
        double remain = Math.max(0, opacity);
        while (remain > 0.001 && out.size() < MAX_OPACITY_PASSES) {
            out.add(Math.min(1.0, remain));
            remain -= 1.0;
        }
        if (out.isEmpty()) out.add(0.0);
        return out;
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.strip().split("\n");
        int from = Math.max(0, lines.length - n);
        return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }
}