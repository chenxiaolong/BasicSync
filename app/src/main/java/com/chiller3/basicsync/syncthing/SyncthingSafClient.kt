/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.syncthing

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.chiller3.basicsync.binding.stbridge.SafChangeListener
import com.chiller3.basicsync.binding.stbridge.SafClient
import com.chiller3.basicsync.binding.stbridge.SafObserver
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class SyncthingSafClient(private val context: Context) : SafClient {
    companion object {
        fun getNarrowestDocumentId(uri: Uri): String =
            try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: IllegalArgumentException) {
                DocumentsContract.getTreeDocumentId(uri)
            }
    }

    private fun Cursor.asSequence() = generateSequence(seed = takeIf { it.moveToFirst() }) {
        takeIf { it.moveToNext() }
    }

    private fun <R> queryMetadataUri(uri: Uri, block: (Sequence<JSONObject>) -> R): R {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        ) ?: throw IOException("Query returned null cursor: $uri")

        return cursor.use {
            val indexDocumentId =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val indexDisplayName =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val indexMimeType =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val indexSize =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val indexLastModified =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            block(cursor.asSequence().map {
                val documentId = cursor.getString(indexDocumentId)
                val displayName = cursor.getString(indexDisplayName)
                val mimeType = cursor.getString(indexMimeType)
                val size = cursor.getLong(indexSize)
                val lastModified = cursor.getLong(indexLastModified)

                val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)

                JSONObject()
                    .put("uri", childUri.toString())
                    .put("name", displayName)
                    .put("size", size)
                    .put("mtime", lastModified)
                    .put("is_dir", mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
            })
        }
    }

    override fun toTreeDocumentUri(treeUri: String): String {
        val uri = treeUri.toUri()
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            getNarrowestDocumentId(uri),
        )

        return documentUri.toString()
    }

    override fun queryTreeRootsJson(): String {
        return JSONArray()
            .apply {
                for (persisted in context.contentResolver.persistedUriPermissions) {
                    if (!DocumentsContract.isTreeUri(persisted.uri)) {
                        continue
                    }

                    put(persisted.uri.toString())
                }
            }
            .toString()
    }

    override fun queryChildDocumentsJson(documentUri: String): String {
        val uri = documentUri.toUri()
        val childDocumentsUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri,
            getNarrowestDocumentId(uri),
        )

        return JSONArray()
            .apply {
                queryMetadataUri(childDocumentsUri) { children ->
                    children.forEach { put(it) }
                }
            }
            .toString()
    }

    override fun queryDocumentJson(documentUri: String): String {
        return queryMetadataUri(documentUri.toUri()) { it.first().toString() }
    }

    override fun openDocument(documentUri: String, mode: String): Long {
        val uri = documentUri.toUri()

        @SuppressLint("Recycle")
        val pfd = context.contentResolver.openFileDescriptor(uri, mode)
            ?: throw IOException("Failed to open fd: $uri")

        // stbridge will own the fd.
        return pfd.detachFd().toLong()
    }

    override fun createDocument(parentDocumentUri: String, mimeType: String, name: String): String {
        val newDocumentUri = DocumentsContract.createDocument(
            context.contentResolver,
            parentDocumentUri.toUri(),
            mimeType,
            name,
        ) ?: throw IOException("Failed to create file: $parentDocumentUri, $mimeType, $name")

        return newDocumentUri.toString()
    }

    override fun renameDocument(documentUri: String, name: String): String {
        val newDocumentUri = DocumentsContract.renameDocument(
            context.contentResolver,
            documentUri.toUri(),
            name,
        ) ?: throw IOException("Failed to rename file: $documentUri -> $name")

        return newDocumentUri.toString()
    }

    override fun deleteDocument(documentUri: String) {
        if (!DocumentsContract.deleteDocument(context.contentResolver, documentUri.toUri())) {
            throw IOException("Failed to delete file: $documentUri")
        }
    }

    override fun moveDocument(
        sourceDocumentUri: String,
        sourceParentDocumentUri: String,
        targetParentDocumentUri: String,
    ): String {
        val newDocumentUri = DocumentsContract.moveDocument(
            context.contentResolver,
            sourceDocumentUri.toUri(),
            sourceParentDocumentUri.toUri(),
            targetParentDocumentUri.toUri(),
        ) ?: throw IOException("Failed to move document: $sourceDocumentUri: $sourceParentDocumentUri -> $targetParentDocumentUri")

        return newDocumentUri.toString()
    }

    override fun observeDocument(
        documentUri: String,
        changeListener: SafChangeListener,
    ): SafObserver {
        val uri = documentUri.toUri()
        val childDocumentsUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri,
            getNarrowestDocumentId(uri),
        )

        // For AOSP's FileSystemProvider, we need to keep the cursor alive or else it won't watch
        // for inotify events.
        val cursor = context.contentResolver.query(
            childDocumentsUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        ) ?: throw IOException("Query returned null cursor: $uri")

        return Observer(cursor, changeListener)
    }

    private class Observer(
        private val cursor: Cursor,
        private val changeListener: SafChangeListener,
    ) : ContentObserver(null), SafObserver {
        init {
            cursor.registerContentObserver(this)
        }

        override fun cancel() {
            cursor.unregisterContentObserver(this)
            cursor.close()
        }

        override fun onChange(selfChange: Boolean) {
            changeListener.onChange()
        }
    }
}
