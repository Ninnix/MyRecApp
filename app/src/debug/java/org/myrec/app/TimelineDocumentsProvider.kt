package org.myrec.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

class TimelineDocumentsProvider : DocumentsProvider() {
    override fun onCreate()=true
    @Synchronized private fun fixture(): File {
        val app=checkNotNull(context)
        val file=File(app.cacheDir,"timeline-provider-fixture.mp4")
        if (!file.isFile) app.createPackageContext("${app.packageName}.test",0).assets.open("fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }
    private fun document(id: String,projection: Array<out String>?): Cursor {
        require(id in listOf("root","media","take"))
        val fields=projection ?: arrayOf("document_id","_display_name","mime_type","_size","last_modified","flags")
        val file=if (id=="take") fixture() else null
        val data=mapOf<String,Any>("document_id" to id,"_display_name" to when (id) { "root" -> "Timeline fixture";"media" -> "Media";else -> "take.mp4" },
            "mime_type" to if (file==null) DocumentsContract.Document.MIME_TYPE_DIR else "video/mp4","_size" to (file?.length() ?: 0L),
            "last_modified" to (file?.lastModified() ?: 0L),"flags" to 0)
        return MatrixCursor(fields).apply { addRow(fields.map { data[it] }) }
    }
    override fun queryDocument(documentId: String,projection: Array<out String>?)=document(documentId,projection)
    override fun queryChildDocuments(parentDocumentId: String,projection: Array<out String>?,sortOrder: String?): Cursor = when (parentDocumentId) {
        "root" -> document("media",projection)
        "media" -> document("take",projection)
        else -> error("Not a directory")
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val fields=projection ?: arrayOf("root_id","document_id","title","flags")
        val data=mapOf<String,Any>("root_id" to "root","document_id" to "root","title" to "Timeline fixture","flags" to 0)
        return MatrixCursor(fields).apply { if (File(context!!.cacheDir,"timeline-provider-fixture.mp4").isFile) addRow(fields.map { data[it] }) }
    }
    override fun openDocument(documentId: String,mode: String,signal: CancellationSignal?): ParcelFileDescriptor {
        require(documentId=="take" && mode=="r");signal?.throwIfCanceled()
        return ParcelFileDescriptor.open(fixture(),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun deleteDocument(documentId: String) { require(documentId=="take");File(context!!.cacheDir,"timeline-provider-fixture.mp4").delete() }
    override fun isChildDocument(parentDocumentId: String,documentId: String)=parentDocumentId=="root" && documentId in listOf("media","take") || parentDocumentId=="media" && documentId=="take"
}
