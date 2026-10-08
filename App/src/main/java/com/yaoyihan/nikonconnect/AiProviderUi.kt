@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.yaoyihan.nikonconnect

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AiSettingsEntry(controller: AiPhotoController, initialProfileId: String? = null) {
    val busy by controller.configBusy.collectAsState()
    var management by remember { mutableStateOf(initialProfileId != null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI 默认配置", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge)
        AiModelPicker(controller, !busy, showManage = false) {}
        TextButton({ management = true }, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("供应商管理")
        }
    }
    if (management) AiProviderManagerDialog(controller, initialProfileId) { management = false }
}

@Composable
private fun AiProviderManagerDialog(controller: AiPhotoController, initialProfileId: String?, dismiss: () -> Unit) {
    val busy by controller.configBusy.collectAsState()
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AiDialogWindow()
        Surface(Modifier.fillMaxSize(), color = BridgeNight) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("供应商管理", Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.titleLarge)
                    IconButton(dismiss, enabled = !busy) { Icon(Icons.Default.Close, "关闭供应商管理", tint = BridgeWhite) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    AiProviderManagementContent(controller, initialProfileId)
                }
            }
        }
    }
}

@Composable
private fun AiProviderManagementContent(controller: AiPhotoController, initialProfileId: String?) {
    val catalog by controller.catalog.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(catalog.profiles.firstOrNull { it.id == initialProfileId }) }
    var adding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("供应商", Modifier.weight(1f), color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge)
            Box {
                IconButton({ adding = true }, enabled = !busy && editing == null) { Icon(Icons.Default.Add, "添加供应商", tint = BridgeEmber) }
                DropdownMenu(adding, { adding = false }) {
                    AiImageProvider.entries.forEach { preset ->
                        DropdownMenuItem(text = { Text("添加${if (preset == AiImageProvider.DOUBAO) "豆包" else "阿里"}预设") }, onClick = {
                            editing = AiProviderProfile(UUID.randomUUID().toString(), preset.title, preset)
                            adding = false
                        })
                    }
                    DropdownMenuItem(text = { Text("自定义供应商") }, onClick = {
                        editing = AiProviderProfile(UUID.randomUUID().toString(), "", models = emptyList())
                        adding = false
                    })
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { Text(it, color = BridgeEmber, style = MaterialTheme.typography.bodySmall) }
            editing?.takeIf { draft -> catalog.profiles.none { it.id == draft.id } }?.let { profile ->
                key(profile.id) { AiProviderEditor(controller, profile, { editing = null }, { text -> editing = null; message = text }) }
            }
            catalog.profiles.forEach { profile ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = BridgeSurface) {
                    Row(Modifier.clickable(enabled = !busy && editing == null) { editing = profile }.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, null, Modifier.size(22.dp), tint = if (profile.enabled) BridgeEmber else BridgeWhite.copy(alpha = .4f))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(profile.name, color = BridgeWhite, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${profile.models.count { it.enabled }} / ${profile.models.size} 个模型 · ${if (profile.key.isBlank()) "未配置 Key" else "已配置 Key"}",
                                color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
                            Text(aiProtocolTitle(profile.protocol), color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(profile.enabled, { enabled ->
                            busy = true
                            scope.launch {
                                try { controller.enableProfile(profile.id, enabled); message = null }
                                catch (error: CancellationException) { throw error }
                                catch (error: Exception) { message = "配置保存失败，请重试" }
                                finally { busy = false }
                            }
                        }, enabled = !busy && editing == null, modifier = Modifier.semantics { contentDescription = "启用供应商 ${profile.name}" })
                        IconButton({ editing = profile }, enabled = !busy && editing == null) { Icon(Icons.Default.ExpandMore, "展开 ${profile.name}", tint = BridgeWhite) }
                    }
                }
                if (editing?.id == profile.id) key(profile.id) {
                    AiProviderEditor(controller, editing!!, { editing = null }, { text -> editing = null; message = text })
                }
            }
            if (catalog.profiles.isEmpty() && editing == null) {
                Text("暂无供应商", Modifier.fillMaxWidth().padding(vertical = 32.dp), color = BridgeWhite.copy(alpha = .6f))
            }
        }
    }
}

@Composable
private fun AiProviderEditor(controller: AiPhotoController, initial: AiProviderProfile, dismiss: () -> Unit, complete: (String) -> Unit) {
    val catalog by controller.catalog.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseline by remember(initial.id) { mutableStateOf(initial) }
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var protocolMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var deleteProvider by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var modelEdit by remember { mutableStateOf<String?>(null) }
    var deletingModel by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val existing = catalog.profiles.any { it.id == initial.id }
    val requestDismiss = { if (!saving) { if (draft != baseline) discard = true else dismiss() } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (existing) "编辑供应商" else "添加供应商", Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.titleMedium)
            if (existing) Box {
                IconButton({ moreMenu = true }, enabled = !saving) { Icon(Icons.Default.MoreVert, "供应商操作", tint = BridgeWhite) }
                DropdownMenu(moreMenu, { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("删除供应商") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = {
                        moreMenu = false; deleteProvider = true
                    })
                }
            }
            IconButton(requestDismiss, enabled = !saving) { Icon(Icons.Default.ExpandLess, "收起供应商配置", tint = BridgeWhite) }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            run {
                OutlinedTextField(draft.name, { if (it.length <= 60) draft = draft.copy(name = it) }, Modifier.fillMaxWidth(), enabled = !saving,
                    label = { Text("供应商名称") }, singleLine = true, colors = bridgeFieldColors())
            }
            run {
                Box {
                    OutlinedButton({ protocolMenu = true }, Modifier.fillMaxWidth(), enabled = !saving) {
                        Text(aiProtocolTitle(draft.protocol), Modifier.weight(1f), maxLines = 2)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(protocolMenu, { protocolMenu = false }) {
                        AiImageProvider.entries.forEach { protocol ->
                            DropdownMenuItem(text = { Text(aiProtocolTitle(protocol)) }, onClick = {
                                val endpoint = if (draft.endpoint == draft.protocol.endpoint) protocol.endpoint else draft.endpoint
                                draft = draft.copy(protocol = protocol, endpoint = endpoint)
                                protocolMenu = false
                            })
                        }
                    }
                }
            }
            run {
                OutlinedTextField(draft.endpoint, { draft = draft.copy(endpoint = it.trim()) }, Modifier.fillMaxWidth(), enabled = !saving,
                    label = { Text("完整 HTTPS 接口地址") }, minLines = 2, maxLines = 4, colors = bridgeFieldColors())
            }
            run {
                OutlinedTextField(draft.key, { draft = draft.copy(key = it.trim()) }, Modifier.fillMaxWidth(), enabled = !saving,
                    label = { Text("API Key") }, singleLine = true, colors = bridgeFieldColors(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton({ showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "隐藏密钥" else "显示密钥")
                    } })
            }
            run {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("启用供应商", Modifier.weight(1f), color = BridgeWhite)
                    Switch(draft.enabled, { draft = draft.copy(enabled = it) }, enabled = !saving)
                }
            }
            run {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模型列表", Modifier.weight(1f), color = BridgeWhite, fontWeight = FontWeight.SemiBold)
                    TextButton({ modelEdit = "" }, enabled = !saving) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加模型")
                    }
                }
            }
            draft.models.forEach { model ->
                Column(Modifier.fillMaxWidth()) {
                    Text(model.id, Modifier.fillMaxWidth().clickable(enabled = !saving) { modelEdit = model.id }.padding(top = 8.dp),
                        color = BridgeWhite, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val active = catalog.activeProfileId == draft.id && catalog.activeModelId == model.id
                        Text(if (active) "当前使用" else if (model.enabled) "已启用" else "已停用", Modifier.weight(1f),
                            color = if (active) BridgeEmber else BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall)
                        Switch(model.enabled, { enabled -> draft = draft.copy(models = draft.models.map { if (it.id == model.id) it.copy(enabled = enabled) else it }) },
                            enabled = !saving, modifier = Modifier.semantics { contentDescription = "启用模型 ${model.id}" })
                        IconButton({ modelEdit = model.id }, enabled = !saving) { Icon(Icons.Default.Edit, "编辑模型 ${model.id}", tint = BridgeWhite, modifier = Modifier.size(18.dp)) }
                        IconButton({ deletingModel = model.id }, enabled = !saving) { Icon(Icons.Default.DeleteOutline, "删除模型 ${model.id}", tint = BridgeWhite.copy(alpha = .65f), modifier = Modifier.size(18.dp)) }
                    }
                }
                HorizontalDivider(color = BridgeWhite.copy(alpha = .12f))
            }
            if (draft.models.isEmpty()) Text("暂无模型", color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, color = BridgeEmber, style = MaterialTheme.typography.bodySmall) }
            run {
                Text("Key 在本机加密保存。生成时照片与 Key 会发送到此接口，费用由你的账号承担。",
                    color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ draft = draft.copy(endpoint = draft.protocol.endpoint) }, enabled = !saving) { Text("恢复协议默认地址") }
                    TextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(draft.protocol.docs))) }
                        .onFailure { message = "无法打开浏览器" } }) { Text("接入文档") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                if (!existing) { draft = draft.copy(key = ""); message = "已清除 Key" }
                else {
                    saving = true
                    scope.launch {
                        try {
                            controller.clearKey(initial.id)
                            draft = draft.copy(key = ""); baseline = baseline.copy(key = ""); message = "已清除本机保存的 Key"
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { message = "密钥清除失败，请重试" }
                        finally { saving = false }
                    }
                }
            }, Modifier.weight(1f), enabled = !saving, contentPadding = PaddingValues(8.dp)) {
                Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("清除 Key")
            }
            Button({
                saving = true
                scope.launch {
                    try { controller.saveProfile(draft); complete("已保存配置，未发起付费请求") }
                    catch (error: CancellationException) { throw error }
                    catch (error: IllegalArgumentException) { message = error.message }
                    catch (error: Exception) { message = "配置加密保存失败，请重试" }
                    finally { saving = false }
                }
            }, Modifier.weight(1f), enabled = !saving, contentPadding = PaddingValues(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) {
                Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (saving) "保存中" else "保存配置")
            }
        }
    }
    modelEdit?.let { original ->
        AiModelEditor(original, draft.models, { modelEdit = null }) { value ->
            draft = draft.copy(models = if (original.isEmpty()) draft.models + AiProviderModel(value)
                else draft.models.map { if (it.id == original) it.copy(id = value) else it })
            modelEdit = null
        }
    }
    deletingModel?.let { id ->
        AiConfirm("删除模型", id, { deletingModel = null }) {
            draft = draft.copy(models = draft.models.filterNot { it.id == id }); deletingModel = null
        }
    }
    if (discard) AiConfirm("放弃未保存的修改？", "已保存的配置不会改变。", { discard = false }) { discard = false; dismiss() }
    if (deleteProvider) AiConfirm("删除供应商", "将删除模型列表与本机保存的 Key。已经发起的生成任务不会被撤销。", { deleteProvider = false }) {
        deleteProvider = false; saving = true
        scope.launch {
            try { controller.deleteProfile(initial.id); complete("已删除供应商") }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = "删除失败，请重试" }
            finally { saving = false }
        }
    }
}

@Composable
private fun AiModelEditor(original: String, models: List<AiProviderModel>, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by remember { mutableStateOf(original) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (original.isBlank()) "添加模型" else "编辑模型") },
        text = {
            Column {
                OutlinedTextField(value, { value = it; error = null }, Modifier.fillMaxWidth(), label = { Text("模型 ID") },
                    minLines = 1, maxLines = 3, colors = bridgeFieldColors(), isError = error != null)
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = BridgeEmber, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton({
            val clean = value.trim()
            try {
                aiValidateModelId(clean)
                require(models.none { it.id == clean && it.id != original }) { "此模型已存在" }
                save(clean)
            } catch (invalid: IllegalArgumentException) { error = invalid.message }
        }) { Text("保存", color = BridgeEmber) } }, dismissButton = { TextButton(dismiss) { Text("取消", color = BridgeWhite) } },
        containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
}

@Composable
private fun AiConfirm(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(confirm) { Text("确认", color = BridgeEmber) } },
        dismissButton = { TextButton(dismiss) { Text("取消", color = BridgeWhite) } },
        containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
}

private fun aiProtocolTitle(protocol: AiImageProvider) = when (protocol) {
    AiImageProvider.DOUBAO -> "火山方舟图片协议"
    AiImageProvider.QWEN -> "阿里百炼图片协议"
}

@Composable
internal fun AiModelPicker(controller: AiPhotoController, enabled: Boolean, showManage: Boolean = true, settings: () -> Unit) {
    val catalog by controller.catalog.collectAsState()
    val config by controller.config.collectAsState()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        Surface(Modifier.fillMaxWidth().clickable(enabled = enabled && !selecting) { choosing = true },
            shape = RoundedCornerShape(8.dp), color = BridgeWhite.copy(alpha = .06f),
            border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, null, Modifier.size(22.dp), tint = BridgeEmber)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(config.profileName, color = BridgeWhite, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(config.model.ifBlank { "选择默认模型" }, color = BridgeWhite.copy(alpha = .6f),
                        style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ChevronRight, "选择默认配置", tint = BridgeWhite.copy(alpha = .6f))
            }
        }
    }
    if (choosing) ModalBottomSheet(onDismissRequest = { if (!selecting) choosing = false }, containerColor = BridgeSurface,
        contentColor = BridgeWhite, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("选择默认配置", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            catalog.profiles.filter { it.enabled && it.models.any { model -> model.enabled } }.forEach { profile ->
                item(key = profile.id) {
                    Text(profile.name, Modifier.padding(top = 12.dp, bottom = 4.dp), color = BridgeWhite.copy(alpha = .6f),
                        style = MaterialTheme.typography.labelLarge)
                }
                profile.models.filter { it.enabled }.forEach { model ->
                    item(key = "${profile.id}:${model.id}") {
                        val active = catalog.activeProfileId == profile.id && catalog.activeModelId == model.id
                        val choose = {
                            selecting = true
                            scope.launch {
                                try { controller.selectModel(profile.id, model.id); error = null; choosing = false }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = "默认配置保存失败，请重试" }
                                finally { selecting = false }
                            }
                            Unit
                        }
                        Surface(Modifier.fillMaxWidth().clickable(enabled = !selecting, onClick = choose),
                            shape = RoundedCornerShape(8.dp), color = if (active) BridgeEmber.copy(alpha = .1f) else BridgeWhite.copy(alpha = .05f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (active) BridgeEmber else BridgeWhite.copy(alpha = .1f))) {
                            Row(Modifier.padding(end = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(active, choose, enabled = !selecting)
                                Column(Modifier.weight(1f)) {
                                    Text(model.id, color = BridgeWhite, style = MaterialTheme.typography.bodyMedium)
                                    if (profile.key.isBlank()) Text("未配置 API Key", color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (catalog.choices().isEmpty()) item { Text("暂无可用模型", Modifier.padding(vertical = 24.dp), color = BridgeWhite.copy(alpha = .6f)) }
            error?.let { item { Text(it, color = BridgeEmber, style = MaterialTheme.typography.bodySmall) } }
        }
        if (selecting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = BridgeEmber)
        if (showManage) TextButton({ choosing = false; settings() }, Modifier.fillMaxWidth(), enabled = !selecting) {
            Icon(Icons.Default.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("供应商管理")
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
