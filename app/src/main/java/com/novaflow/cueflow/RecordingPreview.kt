package com.novaflow.cueflow

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore.Video.Media
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class RecordingPreview(val uri: Uri, val thumbnail: Bitmap)

internal fun loadRecordingPreview(context: Context, savedUri: Uri?): RecordingPreview? {
    val resolver = context.contentResolver
    val uri = savedUri ?: runCatching {
        // Pick up recordings made before the latest URI was stored.
        resolver.query(
            Media.EXTERNAL_CONTENT_URI, arrayOf(Media._ID),
            "${Media.OWNER_PACKAGE_NAME} = ? AND ${Media.RELATIVE_PATH} = ? AND ${Media.IS_PENDING} = 0",
            arrayOf(context.packageName, "$DEFAULT_RECORDING_FOLDER/"),
            "${Media.DATE_ADDED} DESC, ${Media._ID} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            else null
        }
    }.getOrNull() ?: return null

    val thumbnail = runCatching { resolver.loadThumbnail(uri, Size(192, 192), null) }.getOrNull()
        ?: runCatching {
            // Some document providers allow reading the video but don't supply thumbnails.
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
            }
        }.getOrNull() ?: return null
    return RecordingPreview(uri, thumbnail)
}

@Composable
internal fun RecordingPreviewTile(
    preview: RecordingPreview?,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
    iconModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val tile = Modifier.size(size).clip(shape).background(colors.surfaceContainerHighest)
        .border(1.dp, colors.outlineVariant, shape)
    val action = if (preview != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else Modifier
    Box(
        tile.then(action).semantics {
            contentDescription = if (preview == null) "No recent recording" else "Open latest recording"
        },
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(preview.thumbnail.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                painterResource(R.drawable.ic_video_library),
                contentDescription = null,
                modifier = iconModifier.size(size / 2),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}
