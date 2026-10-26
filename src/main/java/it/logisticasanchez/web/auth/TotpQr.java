package it.logisticasanchez.web.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/**
 * The QR code of the two-factor set-up (ENH-09, ADR 0039): ZXing encodes the {@code otpauth://}
 * URI in the app, the page draws it as one inline SVG path (a square per dark module), so no
 * image, no script and nothing leaves the server.
 */
final class TotpQr {

    private TotpQr() {}

    /** {@code size}: modules per side, quiet zone included; {@code path}: the SVG path data. */
    record Svg(int size, String path) {}

    static Svg of(String text) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter()
                    .encode(
                            text,
                            BarcodeFormat.QR_CODE,
                            0,
                            0,
                            Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        } catch (WriterException e) {
            throw new IllegalStateException("QR encoding failed", e);
        }
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return new Svg(matrix.getWidth(), path.toString());
    }
}
