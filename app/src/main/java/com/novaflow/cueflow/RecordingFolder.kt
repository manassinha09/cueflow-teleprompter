package com.novaflow.cueflow

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

internal const val DEFAULT_RECORDING_FOLDER = "Movies/CueFlow"

internal data class SavedTake(
    val uri: Uri,
    val folderName: String,
    val usedFallback: Boolean = false,
    val microphoneInterrupted: Boolean = false,
)

internal data class RecordingFolder(val uri: Uri, val name: String) {
    fun copyRecording(resolver: ContentResolver, source: Uri, fileName: String): Uri {
        val target = checkNotNull(
            DocumentsContract.createDocument(resolver, folderDocument(uri), "video/mp4", fileName)
        ) { "Could not create a file in the recording folder." }
        try {
            checkNotNull(resolver.openInputStream(source)).use { input ->
                checkNotNull(resolver.openOutputStream(target, "w")).use { output ->
                    check(input.copyTo(output) > 0) { "The recording is empty." }
                }
            }
            return target
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw error
        }
    }

    companion object {
        fun select(resolver: ContentResolver, tree: Uri, resultFlags: Int): RecordingFolder {
            val grants = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            check(grants and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "Choose a folder CueFlow can write to." }
            val name = resolver.query(
                folderDocument(tree),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS),
                null, null, null,
            )?.use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
                    "Choose a folder CueFlow can write to."
                }
                cursor.getString(0)
            } ?: error("Could not open the selected folder.")
            resolver.takePersistableUriPermission(tree, grants)
            return RecordingFolder(tree, name)
        }

        private fun folderDocument(tree: Uri): Uri =
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }
}
