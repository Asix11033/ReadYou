package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R

/**
 * 目录面板。
 *
 * 目录必须是客户端的原生 UI —— 正文里的 `<a href="#sec-1">` 会被 `WebViewClient` 当外链打开，
 * 正文内目录这条路走不通。
 *
 * 面板本身不负责滚动，点击后把 [TocItem] 交给上层，由 `AnchorResolver` 统一跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    items: List<TocItem>,
    onSelect: (TocItem) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.toc),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
            ) {
                items.forEach { item ->
                    TocRow(item = item, onClick = { onSelect(item) })
                }
            }
        }
    }
}

@Composable
private fun TocRow(item: TocItem, onClick: () -> Unit) {
    val isTopLevel = item.level <= 2
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.text,
            style =
                if (isTopLevel) MaterialTheme.typography.bodyLarge
                else MaterialTheme.typography.bodyMedium,
            color =
                if (isTopLevel) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // h2 顶格、h3 及更深缩进
            modifier = Modifier.padding(start = if (item.level >= 3) 20.dp else 0.dp),
        )
    }
}
