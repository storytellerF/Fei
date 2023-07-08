package com.storyteller_f.fei.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.storyteller_f.fei.R
import com.storyteller_f.fei.allIp
import com.storyteller_f.fei.service.FeiService
import java.util.Collections

@Composable
fun ShowQrCode(
    sub: String,
    port: String,
    modifier: Modifier = Modifier,
    sendText: (String) -> Unit = {}
) {
    val width = 200
    var selectedIp by remember {
        mutableStateOf(FeiService.defaultAddress)
    }
    val url by remember {
        derivedStateOf {
            "http://$selectedIp:$port/$sub"
        }
    }
    val image by remember {
        derivedStateOf {
            url.createQRImage(width, width)
        }
    }
    var expanded by remember { mutableStateOf(false) }

    val ipList by produceState(initialValue = listOf(FeiService.defaultAddress)) {
        value = allIp()
    }
    var quickSelectData by remember {
        mutableStateOf("")
    }
    LaunchedEffect(key1 = ipList) {
        ipList.firstOrNull {
            it.startsWith("192.168.")
        }?.let {
            quickSelectData = it
        }
    }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth()) {
        val widthDp = LocalConfiguration.current.smallestScreenWidthDp - 100
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = stringResource(R.string.qrcode),
            modifier = Modifier
                .width(
                    widthDp.dp
                )
                .height(widthDp.dp)
        )
        Text(
            text = selectedIp, modifier = Modifier
                .padding(8.dp)
                .background(
                    MaterialTheme.colorScheme.onPrimaryContainer, RectangleShape
                )
                .padding(8.dp)
                .clickable {
                    expanded = true
                }, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary
        )
        if (quickSelectData.isNotEmpty())
            Button(
                onClick = {
                    selectedIp = quickSelectData
                    quickSelectData = ""
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(text = stringResource(id = R.string.quick_selected_ip, quickSelectData))
            }
        val stringResource by rememberUpdatedState(newValue = stringResource(R.string.copied))
        Button(onClick = {
            clipboardManager.setText(AnnotatedString(url))
            Toast.makeText(context, stringResource, Toast.LENGTH_SHORT).show()
        }) {
            Text(text = stringResource(R.string.copy_link))
        }
        Button(onClick = {
            sendText(url)
        }) {
            Text(text = "通过蓝牙发送")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ipList.forEach {
                DropdownMenuItem(text = {
                    Text(text = it)
                }, onClick = {
                    selectedIp = it
                    expanded = false
                })
            }
        }
    }


}

fun String.createQRImage(width: Int, height: Int): Bitmap {
    val bitMatrix = QRCodeWriter().encode(
        this,
        BarcodeFormat.QR_CODE,
        width,
        height,
        Collections.singletonMap(EncodeHintType.CHARACTER_SET, "utf-8")
    )
    return Bitmap.createBitmap(
        (0 until height).flatMap { h: Int ->
            (0 until width).map { w: Int ->
                if (bitMatrix[w, h]
                ) Color.BLACK else Color.WHITE
            }
        }.toIntArray(),
        width, height, Bitmap.Config.ARGB_8888
    )
}