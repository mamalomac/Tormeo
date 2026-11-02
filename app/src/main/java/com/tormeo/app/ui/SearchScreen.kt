package com.tormeo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tormeo.app.TormeoApp
import com.tormeo.app.data.MetaPreview
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(nav: Navigator) {
    val repo = TormeoApp.instance.addons
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, List<MetaPreview>>>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { fr.requestFocus() } }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = null; return@LaunchedEffect }
        delay(600) // debounce
        loading = true
        results = runCatching { repo.search(query.trim()) }.getOrDefault(emptyList())
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(screenPadding)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Naziv filma ili serije") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
            modifier = Modifier.fillMaxWidth().focusRequester(fr),
        )
        val r = results
        when {
            loading && r == null -> CenterMessage("Tražim…")
            r == null -> CenterMessage("Unesi bar 2 slova")
            r.isEmpty() -> CenterMessage("Nema rezultata")
            else -> LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(r, key = { it.first }) { (type, list) ->
                    Column {
                        SectionTitle("${typeLabel(type)} (${list.size})")
                        LazyRow(contentPadding = PaddingValues(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(list, key = { it.type + it.id }) { m ->
                                Poster(m.name, m.poster, subtitle = m.releaseInfo) {
                                    kb?.hide()
                                    nav.go(Screen.Detail(m.type, m.id, m.name, m.poster))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun typeLabel(type: String) = when (type) {
    "series" -> "Serije"
    "movie" -> "Filmovi"
    "channel" -> "Kanali"
    "tv" -> "TV"
    else -> type.replaceFirstChar { it.uppercase() }
}
