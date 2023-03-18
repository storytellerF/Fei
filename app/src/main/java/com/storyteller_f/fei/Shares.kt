package com.storyteller_f.fei

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

val shares = MutableStateFlow<List<SharedFileInfo>>(listOf())

suspend fun Context.savedUriFile(): File {
    return File(filesDir, "list.txt").also { listFile ->
        if (!listFile.exists()) withContext(Dispatchers.IO) {
            listFile.createNewFile()
        }
    }
}

suspend fun Context.removeUri(path: SharedFileInfo) {
    val file = savedUriFile()
    try {
        withContext(Dispatchers.IO) {
            val readText = file.readText()
            readText.trim().split("\n").filter {
                it.isNotEmpty() && it != path.uri
            }.joinToString("\n").let {
                file.writeText(it)
            }
        }
        //todo contentResolver.outgoingPersistedUriPermissions
        contentResolver.releasePersistableUriPermission(path.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: Exception) {
        Toast.makeText(this, e.localizedMessage, Toast.LENGTH_LONG).show()
    }


}

val mutex = Mutex()

suspend fun Context.cacheInvalid() = withContext(Dispatchers.IO) {
    mutex.withLock {
        cacheInvalidInternal()
    }
}

private suspend fun Context.cacheInvalidInternal(): Boolean {
    val listFile = savedUriFile()
    val readText = listFile.readText()
    val savedList = readText.split("\n").filter {
        it.isNotEmpty()
    }.mapNotNull {
        sharedFileInfo(it)
    }
    listFile.writeText(savedList.joinToString("\n") {
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