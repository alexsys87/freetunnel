package org.freetunnel.android;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Reads a QR code out of a frame's luminance, one byte a pixel, as QrScanActivity has it from the camera. No Android classes, so a
 * plain JVM can test it. One instance is for one thread: ZXing's reader keeps state between frames.
 */
final class QrDecoder {
    private final MultiFormatReader reader = new MultiFormatReader();

    QrDecoder() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        // QR codes alone: the other formats would only cost time, and could misread a QR code as one of theirs.
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        // A tt:// link is ASCII, and a QR page's address too; a name in it is UTF-8 inside the base64, never in the code itself.
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);
    }

    /** The code's text, trimmed, or null where the frame holds no code this can read. A code may be dark on light or light on dark. */
    String decode(byte[] luminance, int width, int height) {
        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false);
        String text = read(source);
        // A light code on a dark ground, as a screen in a dark theme may show one.
        if (text == null) text = read(source.invert());
        return text == null ? null : text.trim();
    }

    private String read(com.google.zxing.LuminanceSource source) {
        try {
            return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source))).getText();
        } catch (ReaderException | RuntimeException e) {
            // No code in the frame, the usual case (NotFoundException), or one that could not be read: the next frame may do better.
            return null;
        } finally {
            reader.reset();
        }
    }
}
