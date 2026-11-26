package com.ninerouter.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninerouter.android.R
import com.ninerouter.android.core.Capabilities
import com.ninerouter.android.core.ComboRotation
import com.ninerouter.android.core.SmartSessions
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.CapacityPool
import com.ninerouter.android.data.Combo
import com.ninerouter.android.data.Store

private data class StrategyOption(val value: String, val label: String, val hint: String)

private val comboStrategies = listOf(
    StrategyOption("fallback", "Fallback", "Try models in order, next on failure"),
    StrategyOption("round-robin", "Round Robin", "Rotate models across requests"),
    StrategyOption("smart", "Smart Router", "System One picks the best model"),
    StrategyOption("fusion", "Fusion", "Parallel panel, then a judge writes one answer"),
)

private fun strategyLabel(value: String): String =
    comboStrategies.firstOrNull { it.value == value }?.label ?: "Fallback"

private fun policyLabel(combo: Combo): String = when (combo.smartPolicy) {
    "strict" -> "Strict Lock"
    "per-turn" -> "Per-Turn"
    "custom" -> "Custom ≥${(combo.confidenceThreshold * 100).toInt()}%"
    else -> "Smart ≥80%"
}

@Composable
fun CombosScreen(refresh: () -> Unit) {
    val revision by storeRevision()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Combo?>(null) }
    var pendingDelete by remember { mutableStateOf<Combo?>(null) }
    var routerFor by remember { mutableStateOf<Combo?>(null) }
    var judgeFor by remember { mutableStateOf<Combo?>(null) }
    var policyFor by remember { mutableStateOf<Combo?>(null) }

    val combos = Store.state.combos.toList()
    val filtered = remember(combos, query, revision) {
        val q = query.trim()
        if (q.isBlank()) combos else combos.filter { combo ->
            combo.name.contains(q, ignoreCase = true) ||
                combo.models.any { it.contains(q, ignoreCase = true) }
        }
    }
    val allModels = remember(revision) { comboModelOptions() }
    val systemModels = remember(revision) { systemOneOptions() }
    val routerModels = systemModels.ifEmpty { allModels }

    fun updateCombo(name: String, block: (Combo) -> Combo) {
        val index = Store.state.combos.indexOfFirst { it.name == name }
        if (index < 0) return
        Store.state.combos[index] = block(Store.state.combos[index])
        Store.save()
        refresh()
    }

    fun resetComboState(name: String) {
        ComboRotation.reset("combo:$name")
        SmartSessions.clear(name)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Search combos…") },
                singleLine = true,
                shape = NineRouterShapes.small,
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = NineRouterColors.TextSubtle,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = t("Clear search"),
                                tint = NineRouterColors.TextSubtle,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (combos.isNotEmpty()) {
                PrimaryButton("Create", onClick = { showCreate = true })
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (combos.isEmpty()) {
                item {
                    Panel {
                        EmptyState(
                            title = "No combos yet",
                            body = "Group model ids under one name, then choose fallback, round robin, smart router or fusion.",
                            action = {
                                PrimaryButton("Create combo", onClick = { showCreate = true })
                            },
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                item {
                    Panel {
                        EmptyState(
                            title = "No combos match \"${query.trim()}\"",
                            body = "Try a shorter query, or clear the search to see all ${combos.size} combos.",
                            action = {
                                SecondaryButton("Clear search", onClick = { query = "" })
                            },
                        )
                    }
                }
            } else {
                items(filtered, key = { it.name }) { combo ->
                    ComboCard(
                        combo = combo,
                        onCopy = {
                            clipboard.setText(AnnotatedString(combo.name))
                            Toast.makeText(context, tr("Combo name copied"), Toast.LENGTH_SHORT).show()
                        },
                        onEdit = { editing = combo },
                        onDelete = { pendingDelete = combo },
                        onStrategy = { strategy ->
                            updateCombo(combo.name) {
                                it.copy(
                                    strategy = strategy,
                                    smartPolicy = if (strategy == "smart") it.smartPolicy.ifBlank { "smart" } else it.smartPolicy,
                                )
                            }
                            resetComboState(combo.name)
                        },
                        onPickRouter = { routerFor = combo },
                        onPickJudge = { judgeFor = combo },
                        onPolicy = { policyFor = combo },
                        onClearRouter = {
                            updateCombo(combo.name) { it.copy(routerModel = "") }
                            SmartSessions.clear(combo.name)
                        },
                        onClearJudge = { updateCombo(combo.name) { it.copy(judgeModel = "") } },
                    )
                }
            }

            item {
                CapacityAdapterSection(
                    refresh = refresh,
                    allModels = allModels,
                )
            }
        }
    }

    if (showCreate) {
        ComboEditorDialog(
            existing = null,
            allModels = allModels,
            onDismiss = { showCreate = false },
            onSave = { name, models ->
                Store.mutate { this.combos.add(Combo(name = name, models = models)) }
                showCreate = false
                refresh()
            },
        )
    }

    editing?.let { combo ->
        ComboEditorDialog(
            existing = combo,
            allModels = allModels.filter { it != combo.name },
            onDismiss = { editing = null },
            onSave = { name, models ->
                val index = Store.state.combos.indexOfFirst { it.name == combo.name }
                if (index >= 0) {
                    Store.state.combos[index] = Store.state.combos[index].copy(name = name, models = models)
                    Store.save()
                    resetComboState(combo.name)
                    if (name != combo.name) resetComboState(name)
                }
                editing = null
                refresh()
            },
        )
    }

    routerFor?.let { combo ->
        ModelPickerDialog(
            title = "System One router",
            models = routerModels,
            selected = listOfNotNull(combo.routerModel.takeIf { it.isNotBlank() }),
            multi = false,
            onDismiss = { routerFor = null },
            onConfirm = { picked ->
                picked.firstOrNull()?.let { choice ->
                    updateCombo(combo.name) { it.copy(routerModel = choice) }
                    SmartSessions.clear(combo.name)
                }
                routerFor = null
            },
        )
    }

    judgeFor?.let { combo ->
        ModelPickerDialog(
            title = "Fusion judge",
            models = allModels.filter { it != combo.name },
            selected = listOfNotNull(combo.judgeModel.takeIf { it.isNotBlank() }),
            multi = false,
            onDismiss = { judgeFor = null },
            onConfirm = { picked ->
                picked.firstOrNull()?.let { choice ->
                    updateCombo(combo.name) { it.copy(judgeModel = choice) }
                }
                judgeFor = null
            },
        )
    }

    policyFor?.let { combo ->
        SmartPolicyDialog(
            combo = combo,
            onDismiss = { policyFor = null },
            onSave = { policy, sticky, threshold ->
                updateCombo(combo.name) {
                    it.copy(
                        smartPolicy = policy,
                        sessionSticky = sticky,
                        confidenceThreshold = threshold,
                    )
                }
                SmartSessions.clear(combo.name)
                policyFor = null
            },
        )
    }

    pendingDelete?.let { combo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = NineRouterColors.Surface,
            title = { Text("Delete combo?", color = NineRouterColors.TextMain) },
            text = {
                Text(
                    "Delete \"${combo.name}\"? Requests using this name will stop resolving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.mutate { this.combos.removeAll { it.name == combo.name } }
                    resetComboState(combo.name)
                    pendingDelete = null
                    refresh()
                }) { Text("Delete", color = NineRouterColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ComboCard(
    combo: Combo,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStrategy: (String) -> Unit,
    onPickRouter: () -> Unit,
    onPickJudge: () -> Unit,
    onPolicy: () -> Unit,
    onClearRouter: () -> Unit,
    onClearJudge: () -> Unit,
) {
    var expanded by remember(combo.name) { mutableStateOf(false) }
    val strategy = combo.strategy.ifBlank { "fallback" }
    val shown = if (expanded) combo.models else combo.models.take(3)

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = NineRouterColors.BrandOrange.copy(alpha = 0.14f),
                shape = NineRouterShapes.small,
            ) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_nav_combos),
                        contentDescription = null,
                        tint = NineRouterColors.BrandOrange,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                MonoText(combo.name, maxLines = 1)
                Text(
                    "${combo.models.size} models",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                )
            }
            StrategyDropdown(strategy, onStrategy)
        }

        Spacer(Modifier.height(8.dp))
        if (combo.models.isEmpty()) {
            Text(
                "No models added yet.",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextSubtle,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                shown.forEach { model ->
                    ModelChip(model)
                }
            }
            if (combo.models.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) "Show less" else "+${combo.models.size - 3} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
            }
        }

        if (strategy == "smart") {
            Spacer(Modifier.height(8.dp))
            DividerLine()
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Router", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                Spacer(Modifier.width(6.dp))
                PickerButton(
                    text = combo.routerModel.ifBlank { "Select System One model" },
                    onClick = onPickRouter,
                    modifier = Modifier.weight(1f),
                )
                if (combo.routerModel.isNotBlank()) {
                    ClearButton(onClick = onClearRouter)
                }
            }
            if (combo.routerModel.isBlank()) {
                Text(
                    "Smart Router falls back to the combo order until a router model is set.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.Warning,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Policy", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                Spacer(Modifier.width(6.dp))
                PickerButton(policyLabel(combo), onClick = onPolicy)
            }
        }

        if (strategy == "fusion") {
            Spacer(Modifier.height(8.dp))
            DividerLine()
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Judge", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                Spacer(Modifier.width(6.dp))
                PickerButton(
                    text = combo.judgeModel.ifBlank {
                        "Auto — ${combo.models.firstOrNull() ?: "first model"}"
                    },
                    onClick = onPickJudge,
                    modifier = Modifier.weight(1f),
                )
                if (combo.judgeModel.isNotBlank()) {
                    ClearButton(onClick = onClearJudge)
                }
            }
            Text(
                "Every request bills all panel models plus the judge (${combo.models.size + 1} calls).",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextSubtle,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCopy) { Text("Copy", color = NineRouterColors.TextMuted) }
            TextButton(onClick = onEdit) { Text("Edit", color = NineRouterColors.BrandOrange) }
            TextButton(onClick = onDelete) { Text("Delete", color = NineRouterColors.Danger) }
        }
    }
}

@Composable
private fun ModelChip(model: String) {
    Surface(
        color = NineRouterColors.Surface2,
        shape = RoundedCornerShape(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText(model, modifier = Modifier.weight(1f), maxLines = 1)
            if (Capabilities.supports(model, setOf("vision"))) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "vision",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.Success,
                )
            }
        }
    }
}

@Composable
private fun StrategyDropdown(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            color = NineRouterColors.Surface2,
            shape = NineRouterShapes.small,
            border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    strategyLabel(current),
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMain,
                    maxLines = 1,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = NineRouterColors.TextSubtle,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            comboStrategies.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (option.value == current) NineRouterColors.BrandOrange else NineRouterColors.TextMain,
                            )
                            Text(
                                option.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextSubtle,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}

@Composable
private fun PickerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = NineRouterColors.Surface2,
        shape = NineRouterShapes.small,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (text.startsWith("Select") || text.startsWith("Auto")) NineRouterColors.TextMuted else NineRouterColors.TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            Icons.Default.Close,
            contentDescription = t("Clear"),
            tint = NineRouterColors.TextSubtle,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ComboEditorDialog(
    existing: Combo?,
    allModels: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    val models = remember(existing) { mutableStateListOf<String>().apply { addAll(existing?.models.orEmpty()) } }
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editValue by remember { mutableStateOf("") }

    fun save() {
        val trimmed = name.trim()
        when {
            trimmed.isBlank() -> error = "Name is required"
            !Regex("^[a-zA-Z0-9_.-]+$").matches(trimmed) ->
                error = "Only letters, numbers, -, _ and . are allowed"
            Store.state.combos.any { it.name == trimmed && it.name != existing?.name } ->
                error = "A combo with this name already exists"
            else -> {
                error = ""
                onSave(trimmed, models.toList())
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.94f).fillMaxHeight(0.9f),
            color = NineRouterColors.Surface,
            shape = NineRouterShapes.large,
            border = BorderStroke(1.dp, NineRouterColors.Border),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (existing == null) "Create combo" else "Edit combo",
                        style = MaterialTheme.typography.titleMedium,
                        color = NineRouterColors.TextMain,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = NineRouterColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }

                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    OutlinedTextField(
                        name,
                        { name = it; if (error.isNotBlank()) error = "" },
                        label = { Text("Combo name") },
                        singleLine = true,
                        shape = NineRouterShapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error.isNotBlank()) {
                        Text(
                            error,
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.Danger,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    } else {
                        Text(
                            "Only letters, numbers, -, _ and . are allowed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextSubtle,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Models (${models.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = NineRouterColors.TextMain,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showPicker = true }) {
                            Text("Add model", color = NineRouterColors.BrandOrange)
                        }
                    }

                    if (models.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No models added yet.\nAdd at least one to route traffic.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NineRouterColors.TextMuted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            itemsIndexed(models, key = { index, model -> "$index:$model" }) { index, model ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(NineRouterColors.Surface2, RoundedCornerShape(8.dp))
                                        .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NineRouterColors.TextSubtle,
                                        modifier = Modifier.width(18.dp),
                                    )
                                    Text(
                                        model,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = NineRouterColors.TextMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                editingIndex = index
                                                editValue = model
                                            }
                                            .padding(vertical = 6.dp),
                                    )
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val value = models.removeAt(index)
                                                models.add(index - 1, value)
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            "Move up",
                                            tint = if (index > 0) NineRouterColors.TextMuted else NineRouterColors.TextFaint,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index < models.lastIndex) {
                                                val value = models.removeAt(index)
                                                models.add(index + 1, value)
                                            }
                                        },
                                        enabled = index < models.lastIndex,
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            "Move down",
                                            tint = if (index < models.lastIndex) NineRouterColors.TextMuted else NineRouterColors.TextFaint,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { models.removeAt(index) },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            "Remove",
                                            tint = NineRouterColors.Danger,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton("Cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
                    PrimaryButton(
                        if (existing == null) "Create" else "Save",
                        modifier = Modifier.weight(1f),
                        onClick = { save() },
                    )
                }
            }
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            title = "Add models",
            models = allModels,
            selected = models.toList(),
            multi = true,
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                models.clear()
                models.addAll(picked)
                showPicker = false
            },
        )
    }

    if (editingIndex >= 0) {
        AlertDialog(
            onDismissRequest = { editingIndex = -1 },
            containerColor = NineRouterColors.Surface,
            title = { Text("Edit model id", color = NineRouterColors.TextMain) },
            text = {
                OutlinedTextField(
                    editValue,
                    { editValue = it },
                    singleLine = true,
                    label = { Text("Model id") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = editValue.trim()
                    if (value.isNotBlank() && editingIndex in models.indices) {
                        models[editingIndex] = value
                    }
                    editingIndex = -1
                }) { Text("Save", color = NineRouterColors.BrandOrange) }
            },
            dismissButton = {
                TextButton(onClick = { editingIndex = -1 }) { Text("Cancel") }
            },
        )
    }
}

private data class ModelOption(
    val value: String,
    val group: String,
    val providerId: String,
    val label: String,
)

private fun modelOption(value: String): ModelOption {
    val slash = value.indexOf('/')
    if (slash > 0) {
        val prefix = value.substring(0, slash)
        val def = Catalog.find(prefix)
        return ModelOption(
            value = value,
            group = def?.name ?: prefix,
            providerId = def?.id ?: prefix,
            label = value.substring(slash + 1),
        )
    }
    val group = when {
        Store.state.combos.any { it.name == value } -> "Combos"
        value in Store.state.aliases -> "Aliases"
        else -> "Custom models"
    }
    return ModelOption(value, group, "", value)
}

@Composable
internal fun ModelPickerDialog(
    title: String,
    models: List<String>,
    selected: List<String>,
    multi: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>().apply { addAll(selected) } }
    val pickedKey = picked.joinToString("\u0000")
    val options = remember(models, pickedKey) {
        (models + picked).distinct()
            .map(::modelOption)
            .sortedWith(compareBy({ it.group.lowercase() }, { it.label.lowercase() }))
    }
    val q = query.trim()
    val filtered = if (q.isEmpty()) {
        options
    } else {
        options.filter {
            it.value.contains(q, ignoreCase = true) ||
                it.label.contains(q, ignoreCase = true) ||
                it.group.contains(q, ignoreCase = true)
        }
    }
    val grouped = filtered.groupBy { it.group }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.94f).fillMaxHeight(0.9f),
            color = NineRouterColors.Surface,
            shape = NineRouterShapes.large,
            border = BorderStroke(1.dp, NineRouterColors.Border),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = NineRouterColors.TextMain,
                        )
                        Text(
                            if (multi) "Choose one or more models" else "Pick one model",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMuted,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = NineRouterColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }

                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        placeholder = { Text("Search model, provider or id…") },
                        singleLine = true,
                        shape = NineRouterShapes.small,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = NineRouterColors.TextSubtle,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = t("Clear search"),
                                        tint = NineRouterColors.TextSubtle,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${filtered.size} models",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextSubtle,
                        )
                        Spacer(Modifier.weight(1f))
                        if (multi) {
                            Text(
                                "${picked.size} selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.BrandOrange,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            custom,
                            { custom = it },
                            label = { Text("Custom model id") },
                            placeholder = { Text("provider/model-id") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            shape = NineRouterShapes.small,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = {
                            val value = custom.trim()
                            if (value.isNotBlank()) {
                                if (multi) {
                                    if (value !in picked) picked.add(value)
                                    custom = ""
                                } else {
                                    onConfirm(listOf(value))
                                }
                            }
                        }) { Text("Add", color = NineRouterColors.BrandOrange) }
                    }
                    Spacer(Modifier.height(4.dp))

                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "No models match \"$q\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NineRouterColors.TextMuted,
                                )
                                TextButton(onClick = { query = "" }) {
                                    Text("Clear search", color = NineRouterColors.BrandOrange)
                                }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            grouped.forEach { (group, items) ->
                                item(key = "group:$group") {
                                    ModelGroupHeader(group, items)
                                }
                                items(items, key = { "model:${it.value}" }) { option ->
                                    val isPicked = option.value in picked
                                    ModelPickerRow(
                                        option = option,
                                        selected = isPicked,
                                        multi = multi,
                                        onClick = {
                                            if (multi) {
                                                if (isPicked) picked.remove(option.value)
                                                else picked.add(option.value)
                                            } else {
                                                onConfirm(listOf(option.value))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (multi) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton("Cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
                        PrimaryButton(
                            if (picked.isEmpty()) "Done" else "Add ${picked.size}",
                            modifier = Modifier.weight(1f),
                            onClick = { onConfirm(picked.toList()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelGroupHeader(group: String, items: List<ModelOption>) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val providerId = items.firstOrNull()?.providerId.orEmpty()
        if (providerId.isNotBlank()) {
            ProviderIcon(providerId, group, size = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            group,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = NineRouterColors.BrandOrange,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val prefix = items.firstOrNull()?.providerId.orEmpty()
        if (prefix.isNotBlank()) {
            Spacer(Modifier.width(5.dp))
            Text(
                prefix,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = NineRouterColors.TextSubtle,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            items.size.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
        )
    }
}

@Composable
private fun ModelPickerRow(
    option: ModelOption,
    selected: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.09f) else Color.Transparent,
        shape = NineRouterShapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (multi) {
                Checkbox(checked = selected, onCheckedChange = null)
                Spacer(Modifier.width(3.dp))
            } else {
                Icon(
                    if (selected) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = null,
                    tint = if (selected) NineRouterColors.Success else NineRouterColors.TextSubtle,
                    modifier = Modifier.padding(top = 2.dp).size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = NineRouterColors.TextMain,
                )
            }
        }
    }
}

@Composable
private fun SmartPolicyDialog(
    combo: Combo,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Float) -> Unit,
) {
    var policy by remember { mutableStateOf(combo.smartPolicy.ifBlank { "smart" }) }
    var threshold by remember {
        mutableIntStateOf((combo.confidenceThreshold * 100).toInt().coerceIn(50, 95))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NineRouterColors.Surface,
        title = { Text("Router policy", color = NineRouterColors.TextMain) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PolicyRow(
                    title = "Smart Switch",
                    desc = "Keep the same model while the topic is stable; switch when the router is at least 80% confident.",
                    selected = policy == "smart",
                    onClick = { policy = "smart" },
                )
                PolicyRow(
                    title = "Strict Lock",
                    desc = "Pick once on the first turn, then lock that model for the session. Best prompt-cache reuse.",
                    selected = policy == "strict",
                    onClick = { policy = "strict" },
                )
                PolicyRow(
                    title = "Per-Turn",
                    desc = "Ask the router on every request without session stickiness.",
                    selected = policy == "per-turn",
                    onClick = { policy = "per-turn" },
                )
                PolicyRow(
                    title = "Custom threshold",
                    desc = "Use your own confidence required before switching models.",
                    selected = policy == "custom",
                    onClick = { policy = "custom" },
                )
                if (policy == "custom") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(60, 70, 75, 80, 85, 90).forEach { pct ->
                            ChoiceChip("$pct%", threshold == pct) { threshold = pct }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = when (policy) {
                    "custom" -> threshold / 100f
                    "strict", "per-turn" -> 0f
                    else -> 0.8f
                }
                onSave(policy, policy != "per-turn", value)
            }) { Text("Save", color = NineRouterColors.BrandOrange) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PolicyRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.10f) else Color.Transparent,
        shape = NineRouterShapes.small,
        border = BorderStroke(
            1.dp,
            if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.45f) else NineRouterColors.BorderSubtle,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = NineRouterColors.TextMain)
                Text(desc, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
            }
        }
    }
}

@Composable
private fun CapacityAdapterSection(refresh: () -> Unit, allModels: List<String>) {
    // Subscribe in this leaf: the parent's revision is not passed down.
    val revision = storeRevision().value
    val adapter = Store.state.settings.capacityAdapter

    fun update(key: String, pool: CapacityPool) {
        Store.mutate {
            settings = settings.copy(
                capacityAdapter = if (key == "vision") {
                    settings.capacityAdapter.copy(vision = pool)
                } else {
                    settings.capacityAdapter.copy(audio = pool)
                },
            )
        }
        refresh()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        SectionHeader(
            "Capacity Adapter",
            "Auto-switch when the chosen model cannot read the input",
        )
        CapacityPoolCard(
            capKey = "vision",
            label = "Vision",
            description = "Images (png, jpg, webp, …)",
            pool = adapter.vision,
            allModels = allModels,
            onChange = { update("vision", it) },
        )
        CapacityPoolCard(
            capKey = "audio",
            label = "Audio",
            description = "Audio input",
            pool = adapter.audio,
            allModels = allModels,
            onChange = { update("audio", it) },
        )
    }
}

@Composable
private fun CapacityPoolCard(
    capKey: String,
    label: String,
    description: String,
    pool: CapacityPool,
    allModels: List<String>,
    onChange: (CapacityPool) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                Text(description, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
            }
            Switch(checked = pool.enabled, onCheckedChange = { onChange(pool.copy(enabled = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Round robin",
                style = MaterialTheme.typography.labelSmall,
                color = if (pool.enabled) NineRouterColors.TextMuted else NineRouterColors.TextFaint,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = pool.roundRobin,
                enabled = pool.enabled,
                onCheckedChange = { onChange(pool.copy(roundRobin = it)) },
            )
        }

        if (pool.models.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            pool.models.forEachIndexed { index, model ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextSubtle,
                        modifier = Modifier.width(18.dp),
                    )
                    MonoText(model, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            if (index > 0) {
                                val list = pool.models.toMutableList()
                                val value = list.removeAt(index)
                                list.add(index - 1, value)
                                onChange(pool.copy(models = list))
                            }
                        },
                        enabled = index > 0,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            "Move up",
                            tint = if (index > 0) NineRouterColors.TextMuted else NineRouterColors.TextFaint,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (index < pool.models.lastIndex) {
                                val list = pool.models.toMutableList()
                                val value = list.removeAt(index)
                                list.add(index + 1, value)
                                onChange(pool.copy(models = list))
                            }
                        },
                        enabled = index < pool.models.lastIndex,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            "Move down",
                            tint = if (index < pool.models.lastIndex) NineRouterColors.TextMuted else NineRouterColors.TextFaint,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(
                        onClick = { onChange(pool.copy(models = pool.models.filterIndexed { i, _ -> i != index })) },
                        enabled = pool.enabled,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            "Remove",
                            tint = if (pool.enabled) NineRouterColors.Danger else NineRouterColors.TextFaint,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        } else if (pool.enabled) {
            Text(
                "Add at least one model to activate this adapter.",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.Warning,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        TextButton(onClick = { showPicker = true }, enabled = pool.enabled) {
            Text("Add model", color = if (pool.enabled) NineRouterColors.BrandOrange else NineRouterColors.TextFaint)
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            title = "Add $label models",
            models = allModels.filter { Capabilities.supports(it, setOf(capKey)) }.ifEmpty { allModels },
            selected = pool.models,
            multi = true,
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                onChange(pool.copy(models = picked.distinct()))
                showPicker = false
            },
        )
    }
}

private fun comboModelOptions(): List<String> {
    val out = linkedSetOf<String>()
    Store.state.combos.forEach { out.add(it.name) }
    Store.state.connections.forEach { conn ->
        Catalog.find(conn.provider)?.models?.forEach { out.add("${conn.provider}/$it") }
    }
    Store.state.nodes.forEach { node ->
        node.models.forEach { out.add("${node.prefix}/$it") }
    }
    out.addAll(Store.state.aliases.keys)
    return out.sorted()
}

private fun systemOneOptions(): List<String> {
    val ids = Catalog.providers()
        .filter { "systemone" in it.serviceKinds }
        .map { it.id }
        .toSet()
    val out = linkedSetOf<String>()
    Store.state.connections.filter { it.provider in ids }.forEach { conn ->
        Catalog.find(conn.provider)?.models?.forEach { out.add("${conn.provider}/$it") }
    }
    return out.sorted()
}
