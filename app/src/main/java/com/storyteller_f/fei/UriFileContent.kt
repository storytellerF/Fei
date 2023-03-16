package com.storyteller_f.fei

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.coroutines.CoroutineContext

suspend fun ApplicationCall.respondUri(context: Context, file: Uri, configure: OutgoingContent.() -> Unit = {}) {
    val it = context.contentResolver.query(file, null, null, null)
    if (it == null) {
        respond(HttpStatusCode.NotFound)
    } else {
        it.use {
            if (!it.moveToFirst()) {
                respond(HttpStatusCode.NotFound)
            } else {
                val mimeTypeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val lastModifiedIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mimeType = it.getString(mimeTypeIndex)
                val size = it.getLong(sizeIndex)
                val lastModified = it.getLong(lastModifiedIndex)
                val parse = ContentType.parse(mimeType)
                val fileDescriptor = context.contentResolver.openFileDescriptor(file, "r")
                if (fileDescriptor == null) {
                    respond(HttpStatusCode.NotFound)
                } else {
                    val content =
                        UriFileContent(contentType = parse, parcelFileDescriptor = fileDescriptor, length = size, lastModified = lastModified).apply(
                            configure
                        )
                    respond(content)
                }
            }
        }

    }
}

class UriFileContent(
    override val contentType: ContentType,
    private val parcelFileDescriptor: ParcelFileDescriptor,
    private val length: Long,
    lastModified: Long,
) : OutgoingContent.ReadChannelContent() {

    override val contentLength: Long get() = length
    private val fileDescriptor: FileDescriptor get() = parcelFileDescriptor.fileDescriptor

    init {
        //todo check file exists
        versions = versions + LastModifiedVersion(lastModified)
    }

    // TODO: consider using WriteChannelContent to avoid piping
    // Or even make it dual-content so engine implementation can choose
    override fun readFrom(): ByteReadChannel = fileDescriptor.readChannel(length = length)

    override fun readFrom(range: LongRange): ByteReadChannel = fileDescriptor.readChannel(range.first, range.last, length = length)
}

fun FileDescriptor.readChannel(
    start: Long = 0,
    endInclusive: Long = -1,
    coroutineContext: CoroutineContext = Dispatchers.IO,
    length: Long,
): ByteReadChannel {
    return CoroutineScope(coroutineContext).writer(CoroutineName("file-reader") + coroutineContext, autoFlush = false) {
        require(start >= 0L) { "start position shouldn't be negative but it is $start" }
        require(endInclusive <= length - 1) {
            "endInclusive points to the position out of the file: file size = $length, endInclusive = $endInclusive"
        }

        @Suppress("BlockingMethodInNonBlockingContext")
        FileInputStream(this@readChannel).use { file ->
            val fileChannel: FileChannel = file.channel
            if (start > 0) {
                fileChannel.position(start)
            }

            if (endInclusive == -1L) {
                @Suppress("DEPRECATION")
                channel.writeSuspendSession {
                    while (true) {
                        val buffer = request(1)
                        if (buffer == null) {
                            channel.flush()
                            tryAwait(1)
                            continue
                        }

                        val rc = fileChannel.read(buffer)
                        if (rc == -1) break
                        written(rc)
                    }
                }

                return@use
            }

            var position = start
            channel.writeWhile { buffer ->
                val fileRemaining = endInclusive - position + 1
                val rc = if (fileRemaining < buffer.remaining()) {
                    val l = buffer.limit()
                    buffer.limit(buffer.position() + fileRemaining.toInt())
                    val r = fileChannel.read(buffer)
                    buffer.limit(l)
                    r
                } else {
                    fileChannel.read(buffer)
                }

                if (rc > 0) position += rc

                rc != -1 && position <= endInclusive
            }
        }
    }.channel
}