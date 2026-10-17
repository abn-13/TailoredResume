package com.bu.tailorsg.jobs;

import com.bu.tailorsg.llm.LlmTransport.ImageBlock;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * Screenshot handling for JD extraction (spec 3: 1-5 PNG/JPEG/WebP images, long edge at most 1568 px before
 * sending). Images are re-encoded as PNG, which keeps small text sharp. WebP is readable thanks to the
 * TwelveMonkeys ImageIO plugin.
 */
public final class ScreenshotInput {

    public static final int MAX_EDGE = 1568;
    public static final int MAX_IMAGES = 5;
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private ScreenshotInput() {}

    /** @throws IllegalArgumentException with a message for the user */
    public static ImageBlock prepare(byte[] bytes, String name) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException(name + " is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException(name + " is larger than 8 MB.");
        }
        if (!(isPng(bytes) || isJpeg(bytes) || isWebp(bytes))) {
            throw new IllegalArgumentException(name + " is not a PNG, JPEG or WebP image.");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalArgumentException(name + " could not be read as an image.");
            }
            BufferedImage scaled = downscale(image);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", out);
            return new ImageBlock("image/png", Base64.getEncoder().encodeToString(out.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BufferedImage downscale(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int longest = Math.max(w, h);
        if (longest <= MAX_EDGE) {
            return toRgb(image, w, h);
        }
        double scale = MAX_EDGE / (double) longest;
        return toRgb(image, Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)));
    }

    private static BufferedImage toRgb(BufferedImage source, int w, int h) {
        BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, w, h, java.awt.Color.WHITE, null);
        g.dispose();
        return target;
    }

    static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    static boolean isWebp(byte[] b) {
        return b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}
