package com.storyteller_f.fei

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.net.toUri
import com.storyteller_f.fei.service.SharedFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

val shares = MutableStateFlow<List<SharedFileInfo>>(listOf())

val savedUriFile: MappedByteBuffer by lazy {
    val file = File("/data/data/com.storyteller_f.fei/files/list.txt").ensureFile()
    val channel = FileChannel.open(
        file.toPath(),
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE
    )
    channel.map(FileChannel.MapMode.READ_WRITE, 0, 1024)
}

private fun File.ensureFile(): File {
    val parentFile = parentFile!!
    if (!parentFile.exists()) parentFile.mkdir()
    if (!exists()) createNewFile()
    return this
}

fun Context.removeUri(path: SharedFileInfo) {
    val file = savedUriFile
    try {
        val readText = file.readText()
        val valid = readText.trim().split("\n").filter {
            it.isNotEmpty() && it != path.uri
        }
        valid.joinToString("\n").let {
            file.writeText(it)
        }
        contentResolver.outgoingPersistedUriPermissions.forEach {
            if (!valid.contains(it.uri.toString())) {
                contentResolver.releasePersistableUriPermission(
                    path.uri.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    } catch (e: Exception) {
        Toast.makeText(this, e.localizedMessage, Toast.LENGTH_LONG).show()
    }


}

private fun MappedByteBuffer.writeText(it: String) {
    position(0)
    put(it.toByteArray() + 0)
}

private fun MappedByteBuffer.readText(): String {
    val bytes = ByteArray(1024)
    position(0)
    var index = 0
    while (true) {
        val b = get()
        if (b.toInt() != 0)
            bytes[index++] = b
        else break
    }
    return String(bytes, 0, index)
}

fun MappedByteBuffer.appendText(s: String) {
    val old = readText()
    val new = old + (if (old.isEmpty()) "" else "\n") + s
    writeText(new)
}


val mutex = Mutex()

suspend fun Context.cacheInvalid() = withContext(Dispatchers.IO) {
    mutex.withLock {
        cacheInvalidInternal()
    }
}

private fun Context.cacheInvalidInternal(): Boolean {
    val buffer = savedUriFile
    val content = buffer.readText()
    val savedList = content.split("\n").filter {
        it.isNotEmpty()
    }.mapNotNull {
        sharedFileInfo(it)
    }
    buffer.writeText(savedList.joinToString("\n") {
        it.uri
    })
    val savedFiles = File(filesDir, "saved").listFiles()?.let {
        it.map { file ->
            SharedFileInfo(file.toUri().toString(), file.name)
        }
    }.orEmpty()
    return shares.tryEmit(savedFiles + savedList)
}

private fun Context.sharedFileInfo(it: String) = try {
    val toUri = it.toUri()
    val name = contentResolver.query(toUri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val columnIndex =
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val string = cursor.getString(columnIndex)
            string
        } else "unknown"
    } ?: "unknown"
    SharedFileInfo(it, name)
} catch (e: Exception) {
    null
}