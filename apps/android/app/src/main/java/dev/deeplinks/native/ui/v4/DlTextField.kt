package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshRadius

/**
 * v4 输入框（3.3、5.10、5.13）：M3 描边输入框，浮动标签，聚焦品牌色描边，透明底，圆角 container 12dp。
 */
@Composable
fun DlTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val clear = Dsh.bgBase.copy(alpha = 0f)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = isError,
        textStyle = DshType.body.copy(color = Dsh.labelPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Dsh.labelPrimary,
            unfocusedTextColor = Dsh.labelPrimary,
            disabledTextColor = Dsh.tertiaryText,
            focusedContainerColor = clear,
            unfocusedContainerColor = clear,
            disabledContainerColor = clear,
            errorContainerColor = clear,
            cursorColor = Dsh.brand400,
            focusedBorderColor = Dsh.brand400,
            unfocusedBorderColor = Dsh.outline,
            disabledBorderColor = Dsh.outline,
            errorBorderColor = Dsh.err,
            focusedLabelColor = Dsh.brand400,
            unfocusedLabelColor = Dsh.labelSecondary,
            disabledLabelColor = Dsh.tertiaryText,
            errorLabelColor = Dsh.err,
            focusedPlaceholderColor = Dsh.tertiaryText,
            unfocusedPlaceholderColor = Dsh.tertiaryText,
        ),
        shape = RoundedCornerShape(DshRadius.container),
        modifier = modifier.fillMaxWidth(),
    )
}
