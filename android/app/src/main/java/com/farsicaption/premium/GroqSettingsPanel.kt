package com.farsicaption.premium

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GroqSettingsPanel(enabled: Boolean, onReady: (Boolean) -> Unit) {
    val context = LocalContext.current
    val vault = remember { GroqKeyStore(context) }
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf(vault.hasKey()) }
    // A key draft is never saved in instance state, preferences, analytics or logs.
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(hasKey) { onReady(hasKey) }
    GroqKeyFields(hasKey, draft, message, enabled && !busy,
        onDraft = { draft = it.take(512) },
        onSave = {
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { vault.save(draft) } }
                    .onSuccess { hasKey = true; draft = ""; message = "کلید روی گوشی ذخیره شد" }
                    .onFailure { message = "ذخیرهٔ کلید انجام نشد؛ کلید را کامل و بدون فاصله وارد کنید" }
                busy = false
            }
        },
        onClear = {
            busy = true
            scope.launch {
                withContext(Dispatchers.IO) { vault.clear() }
                hasKey = false; draft = ""; message = "کلید حذف شد"; busy = false
            }
        },
        onGetKey = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }
                .onFailure { message = "در مرورگر console.groq.com/keys را باز کنید" }
        })
}

@Composable
internal fun GroqKeyFields(hasKey: Boolean, draft: String, message: String, enabled: Boolean,
    onDraft: (String) -> Unit, onSave: () -> Unit, onClear: () -> Unit, onGetKey: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (hasKey) "✓ کلید شخصی Groq ذخیره شده" else "راه‌اندازی آنلاین · کلید شخصی Groq", color = Mint, fontSize = 13.sp)
        Text("فقط صدای ویدیو به Groq ارسال می‌شود. اینترنت لازم است؛ سهمیه و هزینه تابع حساب Groq شماست. ویرایش و خروجی روی گوشی انجام می‌شود.",
            color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp)
        TextButton(onClick = onGetKey, enabled = enabled) { Text("گرفتن کلید از سایت Groq", color = AccentSoft) }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(value = draft, onValueChange = onDraft, enabled = enabled,
                label = { Text(if (hasKey) "Replace API key (optional)" else "Groq API key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Accent, unfocusedBorderColor = Stroke, cursorColor = Accent),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave, enabled = enabled && draft.isNotBlank()) { Text("ذخیرهٔ کلید") }
            if (hasKey) TextButton(onClick = onClear, enabled = enabled) { Text("حذف کلید", color = TextMuted) }
        }
        if (message.isNotBlank()) Text(message, color = AccentSoft, fontSize = 12.sp)
    }
}
