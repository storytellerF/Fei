package com.storyteller_f.fei.ui.components

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.storyteller_f.fei.FeiService
import com.storyteller_f.fei.R
import com.storyteller_f.fei.allIp
import com.storyteller_f.fei.createQRImage

@Composable
fun ShowQrCode(sub: String, port: String, modifier: Modifier = Modifier) {
    val width = 200
    var selectedIp by remember {
        mutableStateOf(FeiService.listenerAddress)
    }
    val url by produceState(initialValue = "http://$selectedIp:$port/$sub", sub, selectedIp, port) {
        value = "http://$selectedIp:$port/$sub"
    }
    val image by remember {
        derivedStateOf {
            url.createQRImage(width, width)
        }
    }
    var expanded by remember { mutableStateOf(false) }

    val ipList by produceState(initialValue = listOf(FeiService.listenerAddress)) {
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
    val current = LocalClipboardManager.current
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
            current.setText(AnnotatedString(url))
            Toast.makeText(context, stringResource, Toast.LENGTH_SHORT).show()
        }) {
            Text(text = stringResource(R.string.copy_link))
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