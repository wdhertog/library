package nl.wdhertog.library.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import nl.wdhertog.library.util.TextNormalizer

/** Tekstveld met suggesties uit wat al in de collectie staat (auteurs, series, personen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionTextField(
    label: String,
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val matches = remember(value, suggestions) {
        val needle = TextNormalizer.normalize(value)
        if (needle.isEmpty()) emptyList()
        else suggestions.filter {
            val candidate = TextNormalizer.normalize(it)
            candidate.contains(needle) && candidate != needle
        }.take(6)
    }
    ExposedDropdownMenuBox(
        expanded = focused && !dismissed && matches.isNotEmpty(),
        onExpandedChange = { dismissed = !it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                dismissed = false
                onValueChange(it)
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = focused && !dismissed && matches.isNotEmpty(),
            onDismissRequest = { dismissed = true },
        ) {
            matches.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        dismissed = true
                    },
                )
            }
        }
    }
}
