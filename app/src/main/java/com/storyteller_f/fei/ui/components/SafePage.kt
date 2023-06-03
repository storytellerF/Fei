package com.storyteller_f.fei.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import com.jamal.composeprefs3.ui.PrefsScreen
import com.jamal.composeprefs3.ui.prefs.EditTextPref
import com.storyteller_f.fei.dataStore


@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SafePage() {
    PrefsScreen(dataStore = LocalContext.current.dataStore) {
        prefsItem {
            EditTextPref(key = "pass", title = "pass")
        }
    }
}
