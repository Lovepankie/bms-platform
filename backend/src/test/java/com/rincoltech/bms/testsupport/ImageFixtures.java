package com.rincoltech.bms.testsupport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Fabricated test images built in code: flat colours and a fake SVG, no real logo anywhere. */
public final class ImageFixtures {

    private ImageFixtures() {}

    /** A PNG with a flat colour and a transparent corner. */
    public static byte[] png(int width, int height, Color colour) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, width, height);
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, Math.min(8, width), Math.min(8, height));
        g.dispose();
        return write(image, "png");
    }

    public static byte[] jpeg(int width, int height, Color colour) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return write(image, "jpeg");
    }

    /** A JPEG with an APP1 Exif segment spliced in after the start marker, as a phone camera writes it. */
    public static byte[] jpegWithExif(int width, int height, Color colour) {
        byte[] j = jpeg(width, height, colour);
        byte[] payload = "Exif\0\0TEST-GPS-0.3476N-32.5825E".getBytes(StandardCharsets.ISO_8859_1);
        int len = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(j, 0, 2);
        out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len}, 0, 4);
        out.write(payload, 0, payload.length);
        out.write(j, 2, j.length - 2);
        return out.toByteArray();
    }

    /**
     * A lossless WebP of one flat colour, assembled by hand (the JDK and the decoder plugin have no
     * WebP writer): a VP8L stream whose five prefix codes each hold a single symbol, so the pixels
     * take no bits at all.
     */
    public static byte[] webp(int width, int height, Color colour) {
        BitWriter bits = new BitWriter();
        bits.put(0x2F, 8); // VP8L signature
        bits.put(width - 1, 14);
        bits.put(height - 1, 14);
        bits.put(0, 1); // alpha not used
        bits.put(0, 3); // version
        bits.put(0, 1); // no transform
        bits.put(0, 1); // no colour cache
        bits.put(0, 1); // no meta prefix codes
        for (int symbol : new int[] {colour.getGreen(), colour.getRed(), colour.getBlue(), 255}) {
            bits.put(1, 1); // simple code
            bits.put(0, 1); // one symbol
            bits.put(1, 1); // the symbol takes 8 bits
            bits.put(symbol, 8);
        }
        bits.put(1, 1); // distance code: simple, one symbol of 1 bit, symbol 0
        bits.put(0, 1);
        bits.put(0, 1);
        bits.put(0, 1);
        // The decoder reads ahead eight bytes at a time, so the chunk carries unused trailing zeros.
        byte[] stream = Arrays.copyOf(bits.toBytes(), 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int chunk = stream.length + (stream.length % 2);
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        le32(out, 4 + 8 + chunk);
        out.writeBytes("WEBPVP8L".getBytes(StandardCharsets.US_ASCII));
        le32(out, stream.length);
        out.writeBytes(stream);
        if (stream.length % 2 == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    /** A text file that is an SVG with a script in it; it must never be accepted. */
    public static byte[] svg() {
        return ("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"200\">"
                        + "<script>alert('test')</script><rect width=\"200\" height=\"200\" fill=\"#0d5c75\"/></svg>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The same SVG as UTF-16 text, which a naive text check would miss. */
    public static byte[] svgUtf16() {
        return new String(svg(), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_16LE);
    }

    public static byte[] gif() {
        return write(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "gif");
    }

    private static byte[] write(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void le32(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < 4; i++) {
            out.write((value >> (8 * i)) & 0xFF);
        }
    }

    /** Least significant bit first, as VP8L reads. */
    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;

        void put(int value, int count) {
            for (int i = 0; i < count; i++) {
                current |= ((value >> i) & 1) << used;
                if (++used == 8) {
                    out.write(current);
                    current = 0;
                    used = 0;
                }
            }
        }

        byte[] toBytes() {
            if (used > 0) {
                out.write(current);
                current = 0;
                used = 0;
            }
            return out.toByteArray();
        }
    }
}
