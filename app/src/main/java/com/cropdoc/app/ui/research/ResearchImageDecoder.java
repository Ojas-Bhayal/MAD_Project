package com.cropdoc.app.ui.research;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.ml.LeafPreprocessor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Helper utility for memory-safe content URI decoding and square reticle normalization.
 */
public final class ResearchImageDecoder {

    private ResearchImageDecoder() {
        // Utility class
    }

    public static boolean copyUriToFile(
            @NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull File dest) {
        try (InputStream is = resolver.openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(dest)) {
            if (is == null) {
                return false;
            }
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            return true;
        } catch (Throwable t) {
            if (dest.exists()) {
                dest.delete();
            }
            return false;
        }
    }

    @Nullable
    public static Bitmap decodeAndCropSquare(@NonNull File imageFile) {
        Bitmap decoded = LeafPreprocessor.decodeSampledBitmap(imageFile);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        if (decoded == null) {
            return null;
        }
        Bitmap square224 = LeafPreprocessor.cropReticleAndResize(decoded);
        if (decoded != square224 && !decoded.isRecycled()) {
            decoded.recycle();
        }
        return square224;
    }

    @Nullable
    public static Bitmap createColorBitmap(int r, int g, int b) {
        try {
            Bitmap bmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
            int[] p = new int[224 * 224];
            java.util.Arrays.fill(p, (0xFF << 24) | (r << 16) | (g << 8) | b);
            bmp.setPixels(p, 0, 224, 0, 0, 224, 224);
            return bmp;
        } catch (Throwable ignore) {
            return null;
        }
    }
}
