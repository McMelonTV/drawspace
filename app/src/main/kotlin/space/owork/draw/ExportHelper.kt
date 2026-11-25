package space.owork.draw

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.Document
import space.owork.draw.engine.DocumentPreviewGenerator
import space.owork.draw.renderer.DocumentPngExporter
import java.io.File
import java.io.IOException

data class SharePayload(
    val uri: Uri,
    val mimeType: String,
    val title: String,
)

internal object ExportHelper {

    fun safeFileStem(document: Document): String =
        document.title.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').ifEmpty { document.id }

    fun savePngToPhotos(
        context: Context,
        document: Document,
        theme: AppTheme,
        transparentBackground: Boolean,
    ): String {
        val displayName = "${safeFileStem(document)}_${System.currentTimeMillis()}.png"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Drawspace")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create MediaStore entry for $displayName")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                DocumentPngExporter.exportPngToStream(
                    document = document,
                    outputStream = out,
                    theme = theme,
                    transparentBackground = transparentBackground,
                )
            } ?: throw IOException("Could not open output stream for $displayName")

            val doneValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            return displayName
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    fun prepareSharePng(
        context: Context,
        exportDir: File,
        document: Document,
        theme: AppTheme,
        transparentBackground: Boolean,
    ): SharePayload {
        pruneOldExports(exportDir)
        val fileName = "${safeFileStem(document)}_${System.currentTimeMillis()}.png"
        val outFile = File(exportDir, fileName)
        DocumentPngExporter.exportPng(
            document = document,
            outputFile = outFile,
            theme = theme,
            transparentBackground = transparentBackground,
        )
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return SharePayload(uri = uri, mimeType = "image/png", title = "Share ${document.title} (PNG)")
    }

    fun prepareShareSvg(
        context: Context,
        exportDir: File,
        document: Document,
        theme: AppTheme,
        transparentBackground: Boolean,
    ): SharePayload {
        pruneOldExports(exportDir)
        val fileName = "${safeFileStem(document)}_${System.currentTimeMillis()}.svg"
        val outFile = File(exportDir, fileName)
        outFile.parentFile?.mkdirs()
        val svgBytes = DocumentPreviewGenerator.generateSvg(
            document = document,
            theme = theme,
            maxDimensionPx = 2400,
            transparentBackground = transparentBackground,
        )
        outFile.writeBytes(svgBytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return SharePayload(uri = uri, mimeType = "image/svg+xml", title = "Share ${document.title} (SVG)")
    }

    fun writeSvgToUri(
        context: Context,
        uri: Uri,
        document: Document,
        theme: AppTheme,
        transparentBackground: Boolean,
    ) {
        val svgBytes = DocumentPreviewGenerator.generateSvg(
            document = document,
            theme = theme,
            maxDimensionPx = 2400,
            transparentBackground = transparentBackground,
        )
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(svgBytes)
            out.flush()
        } ?: throw IOException("Could not open selected destination for SVG export")
    }

    fun launchShareSheet(context: Context, payload: SharePayload) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_STREAM, payload.uri)
            putExtra(Intent.EXTRA_TITLE, payload.title)
            clipData = ClipData.newRawUri(payload.title, payload.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, payload.title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }

    private fun pruneOldExports(exportDir: File, maxKept: Int = 8) {
        val files = exportDir.listFiles() ?: return
        if (files.size <= maxKept) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxKept)
            .forEach { it.delete() }
    }
}
