package com.cropdoc.app.ml;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.nio.ByteBuffer;
/**
 * Performs memory-safe subsampled Bitmap decoding with EXIF orientation normalization,
 * square 1:1 reticle box extraction, tight foreground leaf auto-cropping with buffer reuse,
 * and OOD checks.
 */
public final class LeafPreprocessor {
    private static final int MAX_DECODE_DIMENSION = 448;
    private static final int MIN_TIGHT_SPAN = 40;
    private static final int MIN_ROW_COL_FOLIAGE = 6;
    private LeafPreprocessor() {
        // Utility class
    }
    @Nullable
    public static Bitmap decodeSampledBitmap(@NonNull File imageFile) {
        if (!imageFile.exists() || imageFile.length() == 0) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateInSampleSize(
                    bounds.outWidth, bounds.outHeight, MAX_DECODE_DIMENSION, MAX_DECODE_DIMENSION);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
            if (decoded == null) {
                return null;
            }
            int deg = readExifRotationDegrees(imageFile);
            if (deg == 0) {
                return decoded;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(deg);
            Bitmap rotated = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (rotated != decoded && !decoded.isRecycled()) {
                decoded.recycle();
            }
            return rotated;
        } catch (Throwable t) {
            return null;
        }
    }
    public static int readExifRotationDegrees(@NonNull File imageFile) {
        try {
            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
            int orient = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return exifOrientationToDegrees(orient);
        } catch (Throwable e) {
            return 0;
        }
    }
    public static int exifOrientationToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90
                || exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE) {
            return 90;
        }
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180
                || exifOrientation == ExifInterface.ORIENTATION_FLIP_VERTICAL) {
            return 180;
        }
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270
                || exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE) {
            return 270;
        }
        return 0;
    }
    public static int calculateInSampleSize(int rawW, int rawH, int reqW, int reqH) {
        int inSampleSize = 1;
        if (rawH > reqH || rawW > reqW) {
            int halfH = rawH / 2;
            int halfW = rawW / 2;
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }
    @NonNull
    public static int[] computeReticleCropBounds(
            int bw, int bh, int previewW, int previewH, int reticleW, int reticleH) {
        int safeBw = Math.max(1, bw);
        int safeBh = Math.max(1, bh);
        int cropSize;
        if (previewW > 0 && previewH > 0 && reticleW > 0 && reticleH > 0) {
            float scale = Math.max((float) previewW / safeBw, (float) previewH / safeBh);
            int cropW = Math.max(1, Math.min(safeBw, Math.round(reticleW / scale)));
            int cropH = Math.max(1, Math.min(safeBh, Math.round(reticleH / scale)));
            cropSize = Math.min(cropW, cropH);
        } else {
            int minDim = Math.min(safeBw, safeBh);
            cropSize = Math.max(1, Math.min(minDim,
                    Math.round(minDim * ModelConfig.RETICLE_CROP_RATIO)));
        }
        cropSize = Math.max(1, Math.min(cropSize, Math.min(safeBw, safeBh)));
        int startX = Math.max(0, Math.min((safeBw - cropSize) / 2, safeBw - cropSize));
        int startY = Math.max(0, Math.min((safeBh - cropSize) / 2, safeBh - cropSize));
        return new int[] {startX, startY, cropSize};
    }
    @NonNull
    public static Bitmap extractReticleBitmap(
            @NonNull Bitmap source, int previewW, int previewH, int reticleW, int reticleH) {
        int[] b = computeReticleCropBounds(
                source.getWidth(), source.getHeight(), previewW, previewH, reticleW, reticleH);
        Bitmap cropped = Bitmap.createBitmap(source, b[0], b[1], b[2], b[2]);
        Bitmap reticle224 = (b[2] == ModelConfig.INPUT_WIDTH && b[2] == ModelConfig.INPUT_HEIGHT)
                ? cropped
                : Bitmap.createScaledBitmap(
                        cropped, ModelConfig.INPUT_WIDTH, ModelConfig.INPUT_HEIGHT, true);
        if (reticle224 != cropped && cropped != source && !cropped.isRecycled()) {
            cropped.recycle();
        }
        return reticle224;
    }
    @NonNull
    public static Bitmap cropReticleAndResize(@NonNull Bitmap source) {
        return cropReticleAndResize(source, new int[ModelConfig.PIXEL_COUNT]);
    }

    @NonNull
    public static Bitmap cropReticleAndResize(
            @NonNull Bitmap source, @NonNull int[] reusablePixels) {
        // Isotropic square reticle crop: preserves aspect ratio without non-isotropic
        // leaf stretching/distortion (AUD-ML-005).
        return (source.getWidth() == ModelConfig.INPUT_WIDTH
                && source.getHeight() == ModelConfig.INPUT_HEIGHT)
                ? source
                : extractReticleBitmap(source, 0, 0, 0, 0);
    }
    private static int findFirstIndexAtLeast(@NonNull int[] counts, int minVal) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] >= minVal) {
                return i;
            }
        }
        return 0;
    }
    private static int findLastIndexAtLeast(@NonNull int[] counts, int minVal) {
        for (int i = counts.length - 1; i >= 0; i--) {
            if (counts[i] >= minVal) {
                return i;
            }
        }
        return counts.length - 1;
    }
    public static float populateByteBufferAndComputeLeafRatio(
            @NonNull Bitmap bitmap224,
            @NonNull ByteBuffer targetBuffer,
            @NonNull int[] reusablePixels) {
        targetBuffer.rewind();
        bitmap224.getPixels(
                reusablePixels, 0, ModelConfig.INPUT_WIDTH, 0, 0,
                ModelConfig.INPUT_WIDTH, ModelConfig.INPUT_HEIGHT);
        int leafLikePixels = 0;
        for (int i = 0; i < ModelConfig.PIXEL_COUNT; i++) {
            int pixel = reusablePixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            targetBuffer.put((byte) r);
            targetBuffer.put((byte) g);
            targetBuffer.put((byte) b);
            if (isLeafOrLesionPixel(r, g, b)) {
                leafLikePixels++;
            }
        }
        targetBuffer.rewind();
        return (float) leafLikePixels / (float) ModelConfig.PIXEL_COUNT;
    }
    public static boolean isLeafOrLesionPixel(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 25 || max > 246 || (max - min) < 14) {
            return false;
        }
        int excessGreen = (2 * g) - r - b;
        boolean isGreenFoliage = g > r && g > b && excessGreen >= 12 && g > 35;
        boolean isChloroticFoliage = g >= (r - 16) && g > (b + 24) && excessGreen >= 8 && g > 75;
        return isGreenFoliage || isChloroticFoliage;
    }
}
