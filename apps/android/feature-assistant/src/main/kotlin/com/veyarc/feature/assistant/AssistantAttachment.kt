package com.veyarc.feature.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.veyarc.data.storage.ChatAttachmentStore
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

@Serializable
enum class AttachmentKind { IMAGE, PDF, TEXT }

@Serializable
data class AssistantAttachment(
    val id: String,
    val name: String,
    val kind: AttachmentKind,
    val mimeType: String,
    val byteCount: Int,
    val width: Int = 0,
    val height: Int = 0,
    val pageCount: Int = 0,
) {
    val needsVision: Boolean get() = kind != AttachmentKind.TEXT
    fun validate() {
        require(UUID.fromString(id).toString() == id && name.length in 1..255)
        require(byteCount in 1..ChatAttachmentStore.MAX_BYTES)
        require(
            when (kind) {
                AttachmentKind.IMAGE -> mimeType == "image/png" && width in 1..8192 && height in 1..8192
                AttachmentKind.PDF -> mimeType == "application/pdf" && pageCount in 1..600
                AttachmentKind.TEXT -> mimeType == "text/plain"
            },
        )
    }
}

/** Prepared on IO and used only by the request builder. Never serialized into chat history. */
class AttachmentContent(val attachment: AssistantAttachment, val text: String? = null, val images: List<ByteArray> = emptyList())

/** Android pickers grant access only to explicitly selected files. Imports copy and encrypt immediately. */
class AssistantAttachmentImporter(private val context: Context, val store: ChatAttachmentStore = ChatAttachmentStore(context)) {
    fun import(uri: Uri): AssistantAttachment {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val name = (displayName ?: uri.lastPathSegment ?: "Attachment").replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(255).ifBlank { "Attachment" }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                checkAttachmentWork()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ChatAttachmentStore.MAX_BYTES) { "Choose a file smaller than 32 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("This file could not be opened.")
        require(bytes.isNotEmpty()) { "This file is empty." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val matrix = imageOrientation(bytes)
            var sample = 1
            while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > MAX_IMAGE_PIXELS || maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("This image could not be read.")
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            return try {
                saveImage(name, rotated)
            } finally {
                if (rotated !== bitmap) rotated.recycle()
                bitmap.recycle()
            }
        }
        if (bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) {
            val pages = withPdf(bytes) { it.pageCount }
            require(pages in 1..600) { "Choose a PDF with 1 to 600 pages." }
            return AssistantAttachment(store.save(bytes), name, AttachmentKind.PDF, "application/pdf", bytes.size, pageCount = pages)
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        val type = resolver.getType(uri)
        val text = when (extension) {
            "docx" -> documentText(bytes)

            in TEXT_EXTENSIONS -> decodeText(bytes)

            else -> when {
                type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> documentText(bytes)
                type?.startsWith("text/") == true || type in setOf("application/json", "application/xml") -> decodeText(bytes)
                else -> error("Choose an image, PDF, DOCX, or text file.")
            }
        }
        require(text.isNotBlank()) { "This document has no readable text." }
        val content = text.toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_TEXT_BYTES) { "This document contains too much text to send in one message." }
        return AssistantAttachment(store.save(content), name, AttachmentKind.TEXT, "text/plain", content.size)
    }

    fun saveImage(name: String, bitmap: Bitmap): AssistantAttachment {
        require(bitmap.width in 1..8192 && bitmap.height in 1..8192 && bitmap.width.toLong() * bitmap.height <= MAX_IMAGE_PIXELS) { "This image is too large." }
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        val bytes = output.toByteArray()
        return AssistantAttachment(store.save(bytes), name, AttachmentKind.IMAGE, "image/png", bytes.size, bitmap.width, bitmap.height)
    }

    fun bitmap(attachment: AssistantAttachment): Bitmap = BitmapFactory.decodeByteArray(store.read(attachment.id), 0, attachment.byteCount) ?: error("This image could not be read.")

    fun preview(attachment: AssistantAttachment): Bitmap {
        var sample = 1
        while (maxOf(attachment.width, attachment.height) / sample > 320) sample *= 2
        val bytes = store.read(attachment.id)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("This image could not be read.")
    }

    fun cropPreview(attachment: AssistantAttachment, crop: com.veyarc.model.NormalizedRect, maxSide: Int = 1600): Bitmap {
        val original = bitmap(attachment)
        try {
            val left = kotlin.math.floor(crop.x * original.width).toInt().coerceIn(0, original.width - 1)
            val top = kotlin.math.floor(crop.y * original.height).toInt().coerceIn(0, original.height - 1)
            val right = kotlin.math.ceil((crop.x + crop.width) * original.width).toInt().coerceIn(left + 1, original.width)
            val bottom = kotlin.math.ceil((crop.y + crop.height) * original.height).toInt().coerceIn(top + 1, original.height)
            val cropped = Bitmap.createBitmap(original, left, top, right - left, bottom - top)
            try {
                val scale = minOf(1f, maxSide.toFloat() / maxOf(cropped.width, cropped.height))
                val result = Bitmap.createScaledBitmap(cropped, (cropped.width * scale).roundToInt().coerceAtLeast(1), (cropped.height * scale).roundToInt().coerceAtLeast(1), true)
                return if (result === cropped) result.copy(Bitmap.Config.ARGB_8888, false) else result
            } finally {
                if (cropped !== original) cropped.recycle()
            }
        } finally {
            original.recycle()
        }
    }

    fun content(attachment: AssistantAttachment): AttachmentContent {
        attachment.validate()
        val bytes = store.read(attachment.id)
        require(bytes.size == attachment.byteCount)
        return when (attachment.kind) {
            AttachmentKind.IMAGE -> AttachmentContent(attachment, images = listOf(bytes))

            AttachmentKind.TEXT -> AttachmentContent(attachment, text = decodeText(bytes))

            AttachmentKind.PDF -> withPdf(bytes) { renderer ->
                val images = mutableListOf<ByteArray>()
                var total = 0
                repeat(renderer.pageCount) { index ->
                    checkAttachmentWork()
                    val image = renderPage(renderer, index)
                    val output = ByteArrayOutputStream()
                    try {
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
                    } finally {
                        image.recycle()
                    }
                    total += output.size()
                    require(total <= 32 * 1024 * 1024) { "This PDF is too large to send. Choose fewer pages." }
                    images += output.toByteArray()
                }
                AttachmentContent(attachment, images = images)
            }
        }
    }

    fun pdfPage(attachment: AssistantAttachment, index: Int): Bitmap = withPdf(store.read(attachment.id)) { renderPage(it, index) }

    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap = renderer.openPage(index).use { page ->
        val scale = minOf(1600f / maxOf(page.width, page.height), 1_700_000f.div(page.width.toFloat() * page.height).let { kotlin.math.sqrt(it) })
        val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1), (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    }

    private fun <T> withPdf(bytes: ByteArray, block: (PdfRenderer) -> T): T {
        // PdfRenderer requires a seekable descriptor. Private temporary plaintext is always removed.
        val file = File.createTempFile("chat-pdf-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use(block) }
        } finally {
            file.delete()
        }
    }

    private fun imageOrientation(bytes: ByteArray): Matrix {
        val exif = try {
            androidx.exifinterface.media.ExifInterface(bytes.inputStream())
        } catch (_: java.io.IOException) {
            return Matrix()
        }
        return Matrix().apply {
            when (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                2 -> postScale(-1f, 1f)

                3 -> postRotate(180f)

                4 -> postScale(1f, -1f)

                5 -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }

                6 -> postRotate(90f)

                7 -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }

                8 -> postRotate(270f)
            }
        }
    }

    companion object {
        private fun checkAttachmentWork() {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Attachment processing cancelled")
        }
        const val MAX_IMAGE_PIXELS = 4_194_304L
        const val MAX_TEXT_BYTES = 1024 * 1024
        val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "html", "htm", "kt", "java", "py", "js", "ts", "css")
        val FILE_MIME_TYPES = arrayOf("image/*", "application/pdf", "text/*", "application/json", "application/xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

        internal fun decodeText(bytes: ByteArray): String {
            require(bytes.size <= MAX_TEXT_BYTES) { "Choose a text file smaller than 1 MB." }
            val decoder = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").also { require('\u0000' !in it) { "This file is not readable text." } }
        }

        internal fun documentText(bytes: ByteArray): String = ZipInputStream(bytes.inputStream()).use { zip ->
            var entries = 0
            var expanded = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 2000) { "This DOCX has too many entries." }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkAttachmentWork()
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(expanded <= 64 * 1024 * 1024) { "This DOCX is too large." }
                    if (entry.name == "word/document.xml") {
                        require(output.size() + count <= 4 * MAX_TEXT_BYTES) { "This DOCX is too large." }
                        output.write(buffer, 0, count)
                    }
                }
                if (entry.name != "word/document.xml") continue
                val xml = output.toByteArray().toString(Charsets.UTF_8)
                require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "This document contains unsupported XML declarations." }
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setInput(output.toByteArray().inputStream(), "UTF-8")
                return@use buildString {
                    var inText = false
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        when (parser.eventType) {
                            XmlPullParser.DOCDECL -> error("This document contains unsupported XML declarations.")

                            XmlPullParser.START_TAG -> {
                                inText = parser.name == "t"
                                if (parser.name == "tab") append('\t')
                                if (parser.name == "br") append('\n')
                            }

                            XmlPullParser.TEXT -> if (inText) append(parser.text)

                            XmlPullParser.END_TAG -> {
                                if (parser.name == "t") inText = false
                                if (parser.name == "p" || parser.name == "tr") append('\n')
                            }
                        }
                        require(length <= MAX_TEXT_BYTES) { "This DOCX contains too much text." }
                        parser.next()
                    }
                }
            }
            error("This DOCX has no document body.")
        }
    }
}
