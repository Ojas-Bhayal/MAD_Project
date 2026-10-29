package com.cropdoc.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Compresses cropped leaf images (<80 KB), strips GPS/camera EXIF tags for DPDP Act privacy,
 * and encodes compact (<12 KB) Base64 thumbnails for zero-cost Firebase Spark Firestore sync.
 */
public final class ExifPrivacySanitizer {

    private static final int UPLOAD_TARGET_DIMENSION = 512;
    private static final int THUMBNAIL_DIMENSION = 96;
    private static final int JPEG_QUALITY = 80;
    private static final int THUMB_JPEG_QUALITY = 60;

    private ExifPrivacySanitizer() {
        // Utility class
    }

    @NonNull
    public static File saveSanitizedLeafJpeg(
            @NonNull Bitmap sourceBitmap,
            @NonNull File outputDirectory,
            @NonNull String fileName) throws IOException {
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Unable to create scan directory");
        }
        File targetFile = new File(outputDirectory, fileName);
        Bitmap scaled = scaleDownIfNeeded(sourceBitmap, UPLOAD_TARGET_DIMENSION);
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            fos.flush();
        } finally {
            if (scaled != sourceBitmap && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
        try {
            stripSensitiveExifTags(targetFile);
        } catch (Throwable ignored) {
            // Bitmap.compress already writes a clean pixel-only JPEG stream free of GPS tags
        }
        return targetFile;
    }

    @Nullable
    public static String encodeCompactBase64Thumbnail(@NonNull File localJpegFile) {
        if (!localJpegFile.exists() || localJpegFile.length() == 0) {
            return null;
        }
        Bitmap decoded = null;
        Bitmap thumb = null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(4096)) {
            decoded = BitmapFactory.decodeFile(localJpegFile.getAbsolutePath());
            if (decoded == null) {
                return null;
            }
            thumb = scaleDownIfNeeded(decoded, THUMBNAIL_DIMENSION);
            thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, baos);
            return "data:image/jpeg;base64,"
                    + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            return null;
        } finally {
            if (thumb != null && thumb != decoded && !thumb.isRecycled()) {
                thumb.recycle();
            }
            if (decoded != null && !decoded.isRecycled()) {
                decoded.recycle();
            }
        }
    }

    public static void stripSensitiveExifTags(@NonNull File jpegFile) throws IOException {
        ExifInterface exif = new ExifInterface(jpegFile.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null);
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null);
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null);
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null);
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null);
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null);
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null);
        exif.setAttribute(ExifInterface.TAG_MAKE, null);
        exif.setAttribute(ExifInterface.TAG_MODEL, null);
        exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, null);
        exif.saveAttributes();
    }

    @NonNull
    private static Bitmap scaleDownIfNeeded(@NonNull Bitmap bitmap, int maxDim) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxDim && height <= maxDim) {
            return bitmap;
        }
        float ratio = Math.min((float) maxDim / width, (float) maxDim / height);
        int targetW = Math.max(1, Math.round(width * ratio));
        int targetH = Math.max(1, Math.round(height * ratio));
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
    }
}
