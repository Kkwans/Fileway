package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar

/** Controlled search/draft: host cancels obsolete requests and retains inputs after failures.
 * Only a matching, fresh impact preview can authorize the confirmation callback. */
@Composable internal fun PhoneMediaMatchReview(state: PhoneMediaMatchState, imageLoader: ImageLoader,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()) {
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        FilewayTopAppBar(if (state.suggestionOnly) "提交识别建议" else "纠正识别", leadingIcon = R.drawable.ic_arrow_back,
            leadingDescription = "返回", onLeadingClick = { onAction(PhoneMediaAction.Back) })
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "current") {
                MediaHeading("当前识别")
                Text(state.currentTitle, style = MaterialTheme.typography.titleMedium)
                state.evidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
            item(key = "search") {
                OutlinedTextField(state.query, { onAction(PhoneMediaAction.MatchQuery(it)) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("片名") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                        if (state.query.isNotBlank() && !state.busy) {
                            focus.clearFocus(); onAction(PhoneMediaAction.SearchMatches(state.query))
                        }
                    }), shape = MaterialTheme.shapes.medium)
                TextButton(onClick = { focus.clearFocus(); onAction(PhoneMediaAction.SearchMatches(state.query)) },
                    enabled = state.query.isNotBlank() && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("搜索候选") }
            }
            state.conflict?.let { conflict -> item(key = "conflict") {
                // No force-overwrite button. Reload must preserve the query and candidate selection.
                MediaProblemCard(conflict.copy(recoveryLabel = "重新载入资料"), { onAction(PhoneMediaAction.ReloadMatch(state.itemId)) })
            } }
            state.problem?.let { problem -> item(key = "problem") {
                MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("match")) })
            } }
            state.candidates.problem?.let { problem -> item(key = "candidate-problem") {
                MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("match-candidates")) })
            } }
            item(key = "candidate-heading") { MediaHeading("匹配候选") }
            if (state.candidates.entries.isEmpty() && !state.candidates.loading && state.candidates.problem == null) item(key = "empty") {
                FilewayEmptyState(R.drawable.ic_search, "暂无候选", "可用中文片名或原名查找作品。")
            }
            items(state.candidates.entries, key = { "candidate/${it.id}" }) { candidate ->
                Surface(shape = MaterialTheme.shapes.medium,
                    color = if (candidate.id == state.selectedCandidate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().selectable(candidate.id == state.selectedCandidate, enabled = !state.busy,
                        role = Role.RadioButton, onClick = { onAction(PhoneMediaAction.PreviewMatch(candidate.id, state.revision)) })
                        .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (candidate.poster != null) MediaImage(candidate.poster, imageLoader, Modifier.width(64.dp).aspectRatio(2f / 3f))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                            Text(candidate.metadata, style = MaterialTheme.typography.bodySmall)
                            Text(candidate.externalIds, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            candidate.evidence?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        RadioButton(selected = candidate.id == state.selectedCandidate, onClick = null, enabled = !state.busy)
                    }
                }
            }
            item(key = "paging") { MediaPaging("match-candidates", state.candidates.loading, state.candidates.hasMore, onAction) }
            state.impact?.takeIf { it.candidateId == state.selectedCandidate }?.let { impact -> item(key = "impact") {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MediaHeading("变更预览")
                        Text(impact.summary, style = MaterialTheme.typography.bodyMedium)
                        impact.consequences.forEach { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(onClick = { onAction(PhoneMediaAction.ApplyMatch(impact.candidateId, impact.expectedRevision, state.suggestionOnly)) },
                            enabled = state.canApply && impact.canApply && !state.busy && state.conflict == null && impact.expectedRevision == state.revision,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                            Text(if (state.suggestionOnly) "提交建议" else "确认更正")
                        }
                    }
                }
            } }
            if (state.busy) item(key = "busy") { MediaLoading("正在处理，请稍候") }
        }
    }
}

/** Host-provided source roots and policy choices are authoritative: do not invent local/NAS
 * paths, online-provider readiness, language defaults or permission grants in this form. */
@Composable internal fun PhoneMediaLibrarySetup(state: PhoneMediaSetupState,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()) {
    val focus = LocalFocusManager.current
    val draft = state.draft
    val ready = state.canCreate && !state.busy && !state.loading && draft.name.isNotBlank() && state.nameError == null &&
        state.sourceError == null && state.roots.any { it.id == draft.sourceRootId && it.available } &&
        state.languages.any { it.id == draft.languageId && it.enabled } &&
        state.networkPolicies.any { it.id == draft.networkPolicyId && it.enabled }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        FilewayTopAppBar("创建媒体库", leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回",
            onLeadingClick = { onAction(PhoneMediaAction.Back) })
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "intro") {
                Text("从已有目录开始", style = MaterialTheme.typography.headlineSmall)
                Text("新媒体库默认仅自己可见。扫描读取影片与本地资料，不改名、不移动原文件。",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.problem?.let { problem -> item(key = "problem") {
                MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("library-setup")) })
            } }
            if (state.loading) item(key = "loading") { MediaLoading("正在加载可用来源") }
            item(key = "name") {
                OutlinedTextField(draft.name, { onAction(PhoneMediaAction.SetupDraft(draft.copy(name = it))) },
                    label = { Text("媒体库名称") }, enabled = !state.busy, singleLine = true, isError = state.nameError != null,
                    supportingText = state.nameError?.let { error -> { Text(error) } }, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            }
            item(key = "source-heading") {
                MediaHeading("影视来源")
                Text("仅列出你有权使用的目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.sourceError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (state.roots.isEmpty() && !state.loading) item(key = "no-roots") {
                FilewayEmptyState(R.drawable.ic_folder, "暂无可选择的来源", "请先连接来源，或请管理员授予目录访问权限。")
            }
            items(state.roots, key = { "root/${it.id}" }) { root ->
                FilewaySelectionRow(root.name, listOfNotNull(root.description, root.unavailableReason).joinToString("\n"),
                    selected = draft.sourceRootId == root.id, enabled = root.available && !state.busy,
                    onClick = { onAction(PhoneMediaAction.SetupDraft(draft.copy(sourceRootId = root.id))) })
            }
            item(key = "language") {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaHeading("资料语言")
                    state.languages.forEach { language ->
                        FilewaySelectionRow(language.label, language.description, draft.languageId == language.id,
                            enabled = language.enabled && !state.busy,
                            onClick = { onAction(PhoneMediaAction.SetupDraft(draft.copy(languageId = language.id))) })
                    }
                    if (state.languages.isEmpty()) Text("暂无可用语言配置", style = MaterialTheme.typography.bodySmall)
                }
            }
            item(key = "network") {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaHeading("资料获取方式")
                    state.networkPolicies.forEach { policy ->
                        FilewaySelectionRow(policy.label, policy.description, draft.networkPolicyId == policy.id,
                            enabled = policy.enabled && !state.busy,
                            onClick = { onAction(PhoneMediaAction.SetupDraft(draft.copy(networkPolicyId = policy.id))) })
                    }
                    if (state.networkPolicies.isEmpty()) Text("暂无可用资料策略", style = MaterialTheme.typography.bodySmall)
                }
            }
            item(key = "submit") {
                if (state.busy) MediaLoading("正在创建媒体库")
                Button(onClick = { focus.clearFocus(); onAction(PhoneMediaAction.SubmitLibrary(draft)) }, enabled = ready,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) { Text("创建媒体库") }
            }
        }
    }
}
