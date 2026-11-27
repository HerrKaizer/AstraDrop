package dev.tapdrop

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/**
 * A compressed (or RAW-converted) copy is a new JPEG, which would know nothing about the photo.
 * This copies over when and where it was taken and what took it, so the iPhone's Photos files it
 * under the right day and place instead of "today".
 */
object ExifCopy {
    private val TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    )

    /** [orientation] false when the pixels were already turned upright (the source's rotation no longer applies). */
    fun copy(ctx: Context, from: Uri, to: File, orientation: Boolean = true) {
        try {
            val src = ctx.contentResolver.openInputStream(from)?.use { ExifInterface(it) } ?: return
            val dst = ExifInterface(to.path)
            var any = false
            for (t in TAGS) {
                if (!orientation && t == ExifInterface.TAG_ORIENTATION) continue
                val v = src.getAttribute(t) ?: continue
                dst.setAttribute(t, v)
                any = true
            }
            if (any) dst.saveAttributes()
        } catch (e: Exception) {
            android.util.Log.w("TapDrop", "exif copy failed for $from", e)
        }
    }
}
