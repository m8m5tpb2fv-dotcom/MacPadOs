package com.macpad.macpad

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Folders the user granted to MacPad through the Storage Access Framework
 * (ACTION_OPEN_DOCUMENT_TREE). MacPad never asks for broad storage access: Finder only shows
 * these folders.
 */
object FinderPlaces {

    /**
     * Takes a persistable permission for [treeUri] (as returned by ACTION_OPEN_DOCUMENT_TREE) and
     * adds it to the Finder sidebar. Does a quick provider query for the folder name, so prefer
     * calling it right after the picker returns.
     */
    fun add(context: Context, prefs: MacPrefs, treeUri: Uri): FinderPlace {
        val cr = context.contentResolver
        try {
            cr.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            try {
                cr.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
        val place = FinderPlace(treeUri.toString(), displayName(context, treeUri))
        prefs.finderPlaces = prefs.finderPlaces.filter { it.uri != place.uri } + place
        return place
    }

    /** Removes a folder from the sidebar and releases MacPad's access to it. */
    fun remove(context: Context, prefs: MacPrefs, place: FinderPlace) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(place.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(place.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        prefs.finderPlaces = prefs.finderPlaces.filter { it.uri != place.uri }
    }

    /** True if MacPad still holds read access to [uri] (the user can revoke it in Android settings). */
    fun hasAccess(context: Context, uri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }

    /** Display name of the root folder of a tree URI. */
    fun displayName(context: Context, treeUri: Uri): String {
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {
        }
        val seg = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            treeUri.lastPathSegment
        }
        return seg?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { null } ?: "Папка"
    }
}
