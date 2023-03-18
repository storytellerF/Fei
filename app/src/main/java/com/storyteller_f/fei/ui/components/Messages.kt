package com.storyteller_f.fei.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.storyteller_f.fei.Message

class MessagesProvider : PreviewParameterProvider<Message> {
    override val values: Sequence<Message>
        get() = sequenceOf(Message("system", "hello"))

}

@Preview
@Composable
fun MessageItem(@PreviewParameter(MessagesProvider::class) item: Message) {
    Row {
        Box(
            modifier = Modifier
                .width(30.dp)
                .height(30.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
        ) {

        }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = item.from)
            Text(text = item.data)
        }
    }
}

class MessageContentProvider : PreviewParameterProvider<List<Message>> {
    override val values: Sequence<List<Message>>
        get() = sequenceOf(MessagesProvider().values.toList())

}

@Preview
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MessagePage(@PreviewParameter(MessageContentProvider::class) messageList: List<Message>, sendMessage: (String) -> Unit = {}) {
    var content by remember {
        mutableStateOf("")
    }
    Column {
        LazyColumn(
            modifier = Modifier
                .padding(20.dp)
                .weight(1f)
        ) {
            items(messageList.size) {
                val message = messageList[it]
                MessageItem(item = message)
            }
        }
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = content, onValueChange = {
                content = it
            }, modifier = Modifier.weight(1f))
            Button(onClick = {
                sendMessage(content)
                content = ""
            }, modifier = Modifier.padding(start = 8.dp)) {
                Text(text = "send")
            }
        }
    }
}