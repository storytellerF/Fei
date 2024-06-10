package com.storyteller_f.fei.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.storyteller_f.fei.R
import com.storyteller_f.fei.service.Message
import com.storyteller_f.fei.service.getAvatarIcon

class MessagesProvider : PreviewParameterProvider<Message> {
    override val values: Sequence<Message>
        get() = sequenceOf(Message("system", "hello"), Message("user0", "world"))

}

const val MAX_LINE = 4

@Preview
@Composable
fun MessageItem(@PreviewParameter(MessagesProvider::class) item: Message) {
    var expanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var maxLines by remember {
        mutableIntStateOf(MAX_LINE)
    }
    val measurer = rememberTextMeasurer()
    val lineCount by remember {
        derivedStateOf {
            measurer.measure(item.data).lineCount
        }
    }
    Row(modifier = Modifier
        .clickable {
            expanded = true
        }
        .padding(bottom = 8.dp)
        .fillMaxWidth()) {
        AsyncImage(
            model = getAvatarIcon(item.from),
            contentDescription = item.from,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(40.dp)
                .height(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = item.from)
            Text(text = item.data, maxLines = maxLines)
            if (lineCount > MAX_LINE) {
                Button(onClick = {
                    maxLines = if (maxLines == MAX_LINE) {
                        Int.MAX_VALUE
                    } else {
                        MAX_LINE
                    }
                }) {
                    Text(text = if (maxLines == MAX_LINE) "Show All" else "Close")
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(id = android.R.string.copy)) },
                onClick = {
                    clipboardManager.setText(AnnotatedString(item.data))
                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT)
                        .show()
                    expanded = false
                })
        }
    }
}

class MessageContentProvider : PreviewParameterProvider<List<Message>> {
    override val values: Sequence<List<Message>>
        get() = sequenceOf(MessagesProvider().values.toList())

}

@Preview
@Composable
fun MessagePage(
    @PreviewParameter(MessageContentProvider::class) messageList: List<Message>,
    sendMessage: (String) -> Unit = {}
) {
    var content by remember {
        mutableStateOf("")
    }
    Column {
        LazyColumn(
            modifier = Modifier
                .padding(20.dp)
                .weight(1f),
        ) {
            items(messageList.size) {
                val message = messageList[it]
                MessageItem(item = message)
            }
        }
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = content, onValueChange = {
                    content = it
                }, modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 100.dp)
            )
            Button(onClick = {
                sendMessage(content)
                content = ""
            }, modifier = Modifier.padding(start = 8.dp)) {
                Text(text = stringResource(R.string.send))
            }
        }
    }
}