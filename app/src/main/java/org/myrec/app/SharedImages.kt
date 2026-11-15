package org.myrec.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class SharedImages : ContentProvider() {
    companion object {
        fun directory(context: Context) = File(context.cacheDir,"shared-images").apply {
            check(isDirectory || mkdirs()) { "Cannot prepare the JPEG directory" }
        }
        fun uri(context: Context, file: File): Uri {
            require(file.isFile && file.extension == "jpg" && file.canonicalFile.parentFile == directory(context).canonicalFile)
            return Uri.Builder().scheme("content").authority(context.packageName + ".images").appendPath(file.name).build()
        }
    }
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("No JPEG selected")
        val root = directory(context!!).canonicalFile
        val file = File(root,name).canonicalFile
        if (file.parentFile != root || file.extension != "jpg" || !file.isFile) throw FileNotFoundException("JPEG is unavailable")
        return file
    }
    override fun getType(uri: Uri): String { file(uri); return "image/jpeg" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Shared JPEGs are read only")
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { column -> when (column) {
            OpenableColumns.DISPLAY_NAME -> file.name.substringAfter('_')
            OpenableColumns.SIZE -> file.length()
            else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}
