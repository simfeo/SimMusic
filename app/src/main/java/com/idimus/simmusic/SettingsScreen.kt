package com.idimus.simmusic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings: section headers over white rounded cards. */
@Composable
fun SettingsScreen(vm: MusicViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = SevenColors.text()) }
        }
        Text(stringResource(R.string.settings), fontSize = 28.sp, fontWeight = FontWeight.Medium, color = SevenColors.text(),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))

        Header(R.string.accent_color)
        Card {
            val context = LocalContext.current
            // Swatch grid: 4 per row, the selected one ringed and checked.
            AccentPreset.entries.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    row.forEach { p ->
                        val c = p.color(isSystemInDarkTheme())
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                .clickable { Accent.set(context, p); PlayerWidget.refresh(context) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .border(if (p == Accent.preset) 3.dp else 1.dp,
                                        if (p == Accent.preset) c.copy(alpha = 0.45f) else SevenColors.divider(), CircleShape)
                                    .padding(if (p == Accent.preset) 5.dp else 0.dp).clip(CircleShape).background(c),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (p == Accent.preset) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = SevenColors.onAccent)
                            }
                            // Long color names (uk "Помаранчевий") must stay on one line under the swatch.
                            Text(stringResource(p.label), color = SevenColors.text(), fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
                        }
                    }
                }
            }
        }

        Header(R.string.folder_view)
        Card {
            Choice(R.string.folder_view_all, R.string.folder_view_all_summary, !vm.folderTree) { vm.updateFolderTree(false) }
            Choice(R.string.folder_view_tree, R.string.folder_view_tree_summary, vm.folderTree) { vm.updateFolderTree(true) }
        }

        Header(R.string.folder_sort)
        Card {
            Choice(R.string.folder_sort_name, null, !vm.folderSortByPath, enabled = !vm.folderTree) { vm.updateFolderSortByPath(false) }
            Choice(R.string.folder_sort_path, null, vm.folderSortByPath, enabled = !vm.folderTree) { vm.updateFolderSortByPath(true) }
        }

        Header(R.string.playback)
        Card {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = vm.folderTree) { vm.updateIncludeSubfolders(!vm.includeSubfolders) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.include_subfolders), color = SevenColors.text().dim(!vm.folderTree), fontSize = 17.sp)
                    Text(stringResource(R.string.include_subfolders_summary), color = SevenColors.secondaryText(), fontSize = 14.sp)
                }
                Switch(vm.includeSubfolders, { vm.updateIncludeSubfolders(it) }, enabled = vm.folderTree,
                    colors = SwitchDefaults.colors(checkedTrackColor = SevenColors.accent))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun Color.dim(off: Boolean) = if (off) copy(alpha = 0.4f) else this

@Composable
private fun Header(res: Int) {
    Text(stringResource(res), color = SevenColors.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(SevenColors.card())) { content() }
}

@Composable
private fun Choice(title: Int, summary: Int?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), color = SevenColors.text().dim(!enabled), fontSize = 17.sp)
            if (summary != null) Text(stringResource(summary), color = SevenColors.secondaryText(), fontSize = 14.sp)
        }
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(24.dp), tint = SevenColors.accent.copy(alpha = if (enabled) 1f else 0.4f))
    }
}
