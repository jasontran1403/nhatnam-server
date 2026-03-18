package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.service.FileStorageService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Log4j2
public class FileStorageServiceImpl implements FileStorageService {

    private static final String BASE_STORAGE_PATH =
            System.getProperty("user.home") + "/Desktop/nhatnam-storage";

    private static final String PRODUCT_IMAGE_PATH     = BASE_STORAGE_PATH + "/product";
    private static final String POS_PRODUCT_IMAGE_PATH = BASE_STORAGE_PATH + "/pos-product";
    private static final String CATEGORY_IMAGE_PATH    = BASE_STORAGE_PATH + "/category";
    private static final String VARIANT_IMAGE_PATH     = BASE_STORAGE_PATH + "/variant";
    private static final String INGREDIENT_IMAGE_PATH  = BASE_STORAGE_PATH + "/ingredient";

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif"
    );

    public FileStorageServiceImpl() {
        initDirs();
    }

    private void initDirs() {
        try {
            Files.createDirectories(Paths.get(PRODUCT_IMAGE_PATH));
            Files.createDirectories(Paths.get(POS_PRODUCT_IMAGE_PATH));
            Files.createDirectories(Paths.get(CATEGORY_IMAGE_PATH));
            Files.createDirectories(Paths.get(VARIANT_IMAGE_PATH));
            Files.createDirectories(Paths.get(INGREDIENT_IMAGE_PATH));
            log.info("✅ Storage directories initialized at: {}", BASE_STORAGE_PATH);
        } catch (IOException e) {
            throw new RuntimeException("Could not create storage directories", e);
        }
    }

    // ════════════════════════════════════════
    // PUBLIC SAVE METHODS
    // ════════════════════════════════════════

    @Override
    public String saveProductImage(MultipartFile file) throws IOException {
        return saveImage(file, PRODUCT_IMAGE_PATH, "product", 163, 162);
    }

    @Override
    public String savePosProductImage(MultipartFile file) throws IOException {
        return saveImage(file, POS_PRODUCT_IMAGE_PATH, "pos-product", 300, 300);
    }

    @Override
    public String saveCategoryImage(MultipartFile file) throws IOException {
        return saveImage(file, CATEGORY_IMAGE_PATH, "category", 200, 200);
    }

    @Override
    public String saveVariantImage(MultipartFile file) throws IOException {
        return saveImage(file, VARIANT_IMAGE_PATH, "variant", 200, 200);
    }

    @Override
    public String saveIngredientImage(MultipartFile file) throws IOException {
        return saveImage(file, INGREDIENT_IMAGE_PATH, "ingredient", 150, 150);
    }

    // ════════════════════════════════════════
    // CORE SAVE LOGIC
    // ════════════════════════════════════════

    private String saveImage(MultipartFile file, String directory,
                             String prefix, int targetW, int targetH) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !isValidFormat(originalFilename)) {
            throw new IllegalArgumentException("Invalid image format. Allowed: " + ALLOWED_EXTENSIONS);
        }

        BufferedImage original = ImageIO.read(file.getInputStream());
        if (original == null) throw new IOException("Cannot read image file");

        // Detect xem ảnh gốc có alpha (transparency) không
        boolean hasAlpha = original.getColorModel().hasAlpha();

        BufferedImage resized = resizeAndCrop(original, targetW, targetH, hasAlpha);

        String filename = generateFilename(prefix);
        Path target = Paths.get(directory, filename);

        if (!ImageIO.write(resized, "png", target.toFile())) {
            throw new IOException("Failed to write image as PNG");
        }

        log.info("✅ [{}] saved → {} ({}×{} → {}×{}, alpha={})",
                prefix, filename,
                original.getWidth(), original.getHeight(),
                targetW, targetH, hasAlpha);

        return "/images/" + prefix + "/" + filename;
    }

    /**
     * Cover-crop: scale để fill, rồi crop từ giữa.
     * Nếu ảnh gốc có alpha → dùng ARGB để giữ nền trong suốt.
     * Nếu không có alpha → dùng RGB bình thường.
     */
    private BufferedImage resizeAndCrop(BufferedImage src, int w, int h, boolean hasAlpha) {
        double ratioW = (double) w / src.getWidth();
        double ratioH = (double) h / src.getHeight();
        double ratio  = Math.max(ratioW, ratioH);

        int sw = (int) (src.getWidth()  * ratio);
        int sh = (int) (src.getHeight() * ratio);

        // ── Chọn type phù hợp ────────────────────────────────
        // TYPE_INT_ARGB: giữ alpha (nền trong suốt)
        // TYPE_INT_RGB:  không có alpha (nền đặc)
        int imgType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        BufferedImage tmp = new BufferedImage(sw, sh, imgType);
        Graphics2D g = tmp.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        if (hasAlpha) {
            // Clear toàn bộ canvas thành transparent (alpha=0)
            // Bắt buộc phải dùng Composite.CLEAR trước khi draw
            // để tránh JVM fill màu đen mặc định vào vùng alpha
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
            g.fillRect(0, 0, sw, sh);
            // Restore composite về SRC_OVER để draw ảnh lên
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        } else {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, sw, sh);
        }

        g.drawImage(src, 0, 0, sw, sh, null);
        g.dispose();

        int x = Math.max(0, (sw - w) / 2);
        int y = Math.max(0, (sh - h) / 2);
        return tmp.getSubimage(x, y, w, h);
    }

    // ════════════════════════════════════════
    // DELETE / GET
    // ════════════════════════════════════════

    @Override
    public void deleteFile(String filePath) throws IOException {
        Path path = resolvePath(filePath);
        if (path == null) {
            log.warn("⚠️ Unknown file path: {}", filePath);
            return;
        }
        if (Files.exists(path)) {
            Files.delete(path);
            log.info("✅ Deleted: {}", filePath);
        } else {
            log.warn("⚠️ File not found: {}", filePath);
        }
    }

    @Override
    public byte[] getFile(String filePath) throws IOException {
        Path path = resolvePath(filePath);
        if (path == null) throw new IllegalArgumentException("Invalid file path: " + filePath);
        if (!Files.exists(path)) throw new IOException("File not found: " + filePath);
        return Files.readAllBytes(path);
    }

    private Path resolvePath(String dbPath) {
        if (dbPath == null || dbPath.isEmpty()) return null;
        String[] parts = dbPath.split("/");
        if (parts.length < 4) return null;
        String type     = parts[2];
        String filename = parts[3];
        return switch (type) {
            case "product"     -> Paths.get(PRODUCT_IMAGE_PATH,     filename);
            case "pos-product" -> Paths.get(POS_PRODUCT_IMAGE_PATH, filename);
            case "category"    -> Paths.get(CATEGORY_IMAGE_PATH,    filename);
            case "variant"     -> Paths.get(VARIANT_IMAGE_PATH,     filename);
            case "ingredient"  -> Paths.get(INGREDIENT_IMAGE_PATH,  filename);
            default            -> null;
        };
    }

    // ════════════════════════════════════════
    // UTILS
    // ════════════════════════════════════════

    private boolean isValidFormat(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return ALLOWED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase());
    }

    private String generateFilename(String prefix) {
        return String.format("%s_%d_%s.png", prefix,
                System.currentTimeMillis(), UUID.randomUUID());
    }
}