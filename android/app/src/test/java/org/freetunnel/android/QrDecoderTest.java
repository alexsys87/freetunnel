package org.freetunnel.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.nayuki.qrcodegen.QrCode;

import org.junit.Test;

import java.util.Arrays;

// What Scan QR code reads from a camera frame: a code drawn into luminance bytes, one a pixel, as the camera's Y plane has them.
public class QrDecoderTest {
    private static final int FRAME_WIDTH = 1280;
    private static final int FRAME_HEIGHT = 720;

    // A frame of mid grey with the code in it at (left, top), scale pixels a module and its four-module quiet zone around it.
    private static byte[] frame(String text, int scale, int left, int top, boolean inverted) {
        QrCode code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        byte[] out = new byte[FRAME_WIDTH * FRAME_HEIGHT];
        Arrays.fill(out, (byte) 128);
        int dark = inverted ? 230 : 20;
        int light = inverted ? 20 : 230;
        int modules = code.size + 8;
        for (int my = 0; my < modules; my++) {
            for (int mx = 0; mx < modules; mx++) {
                boolean on = code.getModule(mx - 4, my - 4);
                for (int y = 0; y < scale; y++) {
                    for (int x = 0; x < scale; x++) {
                        int px = left + mx * scale + x;
                        int py = top + my * scale + y;
                        if (px < FRAME_WIDTH && py < FRAME_HEIGHT) out[py * FRAME_WIDTH + px] = (byte) (on ? dark : light);
                    }
                }
            }
        }
        return out;
    }

    private static String link() {
        DeepLinkImport.Link link = new DeepLinkImport.Link();
        link.hostname = "vpn.example.com";
        link.addresses.add("203.0.113.10:443");
        link.username = "alice";
        link.password = "s3cret";
        link.name = "DE · Frankfurt";
        link.authKey = "aabbccdd";
        return DeepLinkImport.encode(link);
    }

    @Test public void readsALinkAsTheEndpointShowsIt() {
        String link = link();
        String read = new QrDecoder().decode(frame(link, 6, 300, 60, false), FRAME_WIDTH, FRAME_HEIGHT);
        assertEquals(link, read);
        assertTrue(DeepLinkImport.looksLikeLink(read));
    }

    @Test public void readsALightCodeOnADarkGround() {
        String link = link();
        assertEquals(link, new QrDecoder().decode(frame(link, 6, 500, 100, true), FRAME_WIDTH, FRAME_HEIGHT));
    }

    @Test public void readsTheQrPagesAddress() {
        String page = "https://trusttunnel.org/qr.html#tt=" + link().substring("tt://?".length());
        assertEquals(page, new QrDecoder().decode(frame(page, 5, 100, 40, false), FRAME_WIDTH, FRAME_HEIGHT));
    }

    @Test public void oneDecoderReadsFrameAfterFrame() {
        QrDecoder decoder = new QrDecoder();
        byte[] empty = new byte[FRAME_WIDTH * FRAME_HEIGHT];
        Arrays.fill(empty, (byte) 128);
        assertNull(decoder.decode(empty, FRAME_WIDTH, FRAME_HEIGHT));
        assertEquals("first", decoder.decode(frame("first", 8, 10, 10, false), FRAME_WIDTH, FRAME_HEIGHT));
        assertNull(decoder.decode(empty, FRAME_WIDTH, FRAME_HEIGHT));
        assertEquals("second", decoder.decode(frame("  second\n", 8, 600, 300, false), FRAME_WIDTH, FRAME_HEIGHT));
    }
}
