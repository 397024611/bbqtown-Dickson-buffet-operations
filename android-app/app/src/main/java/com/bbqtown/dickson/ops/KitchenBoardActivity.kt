package com.bbqtown.dickson.ops

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

object KitchenBoardPresence {
    @Volatile var active: Boolean = false
}

class KitchenBoardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = BrandInk,
                    secondary = BrandGold,
                    background = BrandInk,
                    surface = BrandSurface
                )
            ) {
                KitchenBoardScreen(
                    onDevice = {
                        getSharedPreferences("bbqtown_ops_v2", Context.MODE_PRIVATE)
                            .edit().putString("role", "").apply()
                        startActivity(Intent(this, PremiumOpsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        KitchenBoardPresence.active = true
    }

    override fun onStop() {
        KitchenBoardPresence.active = false
        super.onStop()
    }
}

@Composable
private fun KitchenBoardScreen(onDevice: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val api = remember { FastApi(ctx) }
    val prefs = remember { ctx.getSharedPreferences("bbqtown_ops_v2", Context.MODE_PRIVATE) }
    val section = remember {
        when (prefs.getString("role", "")) {
            "s2" -> 2
            else -> 1
        }
    }
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(api.cachedLive(section)) }
    var pending by remember { mutableIntStateOf(api.pendingLiveCount()) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(section) {
        while (true) {
            now = System.currentTimeMillis()
            pending = api.pendingLiveCount()
            try {
                val fresh = api.loadLive(section)
                snap = fresh
                api.cacheLive(fresh, section)
                pending = api.pendingLiveCount()
                error = if (pending > 0) "$pending offline action(s) queued" else null
            } catch (e: Exception) {
                pending = api.pendingLiveCount()
                error = e.message
            }
            delay(4000)
        }
    }

    fun act(spec: String, id: Int) {
        val before = snap
        snap = kitchenOptimistic(snap, spec, id)
        snap?.let { api.cacheLive(it, section) }
        pending = api.pendingLiveCount() + 1
        scope.launch {
            try {
                val server = api.action(before, spec, id, section)
                snap = server
                api.cacheLive(server, section)
                pending = api.pendingLiveCount()
                error = if (pending > 0) "$pending offline action(s) queued" else null
            } catch (e: Exception) {
                pending = api.pendingLiveCount()
                error = e.message
            }
        }
    }

    val tasks = (snap?.foods ?: emptyList()).filter { it.section == section && it.kitchen != "idle" }
    val requested = tasks.filter { it.kitchen == "requested" }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val preparing = tasks.filter { it.kitchen == "preparing" }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val ready = tasks.filter { it.kitchen == "ready" }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val urgent = requested.count { it.status == "EMPTY" || (it.requestedAt > 0 && now - it.requestedAt >= 5 * 60 * 1000L) }
    val hasEmpty = requested.any { it.status == "EMPTY" }

    Column(Modifier.fillMaxSize().background(BrandInk)) {
        KitchenTopBar(
            section = section,
            urgent = urgent,
            requested = requested.size,
            preparing = preparing.size,
            ready = ready.size,
            pending = pending,
            error = error,
            onDevice = onDevice
        )

        if (requested.isNotEmpty()) {
            KitchenAttentionBanner(requested.size, hasEmpty)
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 820.dp) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KitchenLane(
                        title = "NEW REQUESTS",
                        subtitle = "Waiting to start",
                        accent = BrandGold,
                        foods = requested,
                        now = now,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onAction = { f -> act("kitchen_prepare", f.id) }
                    )
                    KitchenLane(
                        title = "PREPARING",
                        subtitle = "In progress",
                        accent = Color(0xFF6077D6),
                        foods = preparing,
                        now = now,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onAction = { f -> act("kitchen_ready", f.id) }
                    )
                    KitchenLane(
                        title = "READY TO REFILL",
                        subtitle = "Waiting for FOH",
                        accent = BrandGreen,
                        foods = ready,
                        now = now,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onAction = null
                    )
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        KitchenLane("NEW REQUESTS", "Waiting to start", BrandGold, requested, now, Modifier.width(310.dp).fillParentMaxHeight()) { f -> act("kitchen_prepare", f.id) }
                    }
                    item {
                        KitchenLane("PREPARING", "In progress", Color(0xFF6077D6), preparing, now, Modifier.width(310.dp).fillParentMaxHeight()) { f -> act("kitchen_ready", f.id) }
                    }
                    item {
                        KitchenLane("READY TO REFILL", "Waiting for FOH", BrandGreen, ready, now, Modifier.width(310.dp).fillParentMaxHeight(), null)
                    }
                }
            }
        }
    }
}

@Composable
private fun KitchenAttentionBanner(count: Int, hasEmpty: Boolean) {
    val accent = if (hasEmpty) BrandRed else Color(0xFFF59E0B)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = accent,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 7.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color.White, shape = CircleShape) {
                Text("!", color = accent, fontWeight = FontWeight.Black, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (hasEmpty) "EMPTY DISH · ACTION REQUIRED" else "REFILL REQUEST · ACTION REQUIRED",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
                Text(
                    if (hasEmpty) "Start preparing immediately" else "Kitchen has a new LOW request waiting",
                    color = Color.White.copy(alpha = .88f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
            Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                Text(count.toString(), color = accent, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun KitchenTopBar(
    section: Int,
    urgent: Int,
    requested: Int,
    preparing: Int,
    ready: Int,
    pending: Int,
    error: String?,
    onDevice: () -> Unit
) {
    Surface(color = BrandInk) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OfficialBbqTownLogo(Modifier.width(120.dp).height(46.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("KITCHEN $section", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text("LIVE KDS · tasks move across the board as staff work", color = Color.White.copy(alpha = .52f), fontSize = 9.sp)
                }
                KitchenSyncChip(pending, error)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDevice) {
                    Text("DEVICE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitchenCount("URGENT", urgent, BrandRed, Modifier.weight(1f))
                KitchenCount("NEW", requested, BrandGold, Modifier.weight(1f))
                KitchenCount("PREPARING", preparing, Color(0xFF6077D6), Modifier.weight(1f))
                KitchenCount("READY", ready, BrandGreen, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun KitchenCount(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White.copy(alpha = .07f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(label, color = Color.White.copy(alpha = .65f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(value.toString(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun KitchenSyncChip(pending: Int, error: String?) {
    val accent = when {
        error != null -> BrandRed
        pending > 0 -> BrandGold
        else -> BrandGreen
    }
    Surface(color = accent.copy(alpha = .16f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pending > 0) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = accent)
            else Box(Modifier.size(7.dp).background(accent, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                if (error != null) "SYNC ISSUE" else if (pending > 0) "SYNCING" else "SYNCED",
                color = if (error != null) Color(0xFFFFB2AA) else Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 8.sp
            )
        }
    }
}

@Composable
private fun KitchenLane(
    title: String,
    subtitle: String,
    accent: Color,
    foods: List<FFood>,
    now: Long,
    modifier: Modifier = Modifier,
    onAction: ((FFood) -> Unit)?
) {
    Surface(
        modifier = modifier,
        color = BrandInk2,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(5.dp).height(34.dp).background(accent, RoundedCornerShape(50)))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Text(subtitle, color = Color.White.copy(alpha = .45f), fontSize = 8.sp)
                }
                Surface(color = accent.copy(alpha = .17f), shape = CircleShape) {
                    Text(foods.size.toString(), color = accent, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
            Spacer(Modifier.height(9.dp))
            if (foods.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("NO TASKS", color = Color.White.copy(alpha = .30f), fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(foods, key = { it.id }) { food ->
                        KitchenTaskCard(food, now, accent, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun KitchenTaskCard(food: FFood, now: Long, laneAccent: Color, onAction: ((FFood) -> Unit)?) {
    val elapsed = if (food.requestedAt > 0) now - food.requestedAt else 0L
    val overdue = food.kitchen == "requested" && elapsed >= 5 * 60 * 1000L
    val urgent = food.status == "EMPTY" || overdue
    val border = if (urgent) BrandRed else laneAccent

    Surface(
        color = BrandSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (urgent) 2.dp else 1.dp, border)
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(food.name, color = BrandInk, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            food.status == "EMPTY" -> "EMPTY · URGENT"
                            food.kitchen == "ready" -> "READY · WAITING FOR FOH"
                            food.kitchen == "preparing" -> "LOW · IN PROGRESS"
                            else -> "LOW · PREP NEXT"
                        },
                        color = if (food.status == "EMPTY") BrandRed else laneAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp
                    )
                }
                Surface(color = border.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
                    Text(food.kitchen.uppercase(), color = border, fontWeight = FontWeight.Black, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
            if (elapsed > 0) {
                Spacer(Modifier.height(7.dp))
                Text(
                    "WAIT ${kitchenAge(elapsed)}${if (overdue) " · OVERDUE" else ""}",
                    color = if (overdue) BrandRed else BrandMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                food.kitchen == "requested" && onAction != null -> Button(
                    onClick = { onAction(food) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (urgent) BrandRed else BrandGold),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("START PREPARING", color = if (urgent) Color.White else BrandInk, fontWeight = FontWeight.Black, fontSize = 12.sp) }

                food.kitchen == "preparing" && onAction != null -> Button(
                    onClick = { onAction(food) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("MARK READY", fontWeight = FontWeight.Black, fontSize = 11.sp) }

                else -> Surface(color = BrandGreen.copy(alpha = .10f), shape = RoundedCornerShape(10.dp)) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text("READY · WAITING FOR FOH", color = BrandGreen, fontWeight = FontWeight.Black, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

private fun kitchenOptimistic(s: FSnap?, spec: String, id: Int): FSnap? {
    if (s == null) return null
    return s.copy(foods = s.foods.map { food ->
        if (food.id != id) food else when (spec) {
            "kitchen_prepare" -> food.copy(kitchen = "preparing")
            "kitchen_ready" -> food.copy(kitchen = "ready")
            else -> food
        }
    })
}

private fun kitchenAge(ms: Long): String {
    val totalMin = (ms.coerceAtLeast(0) / 60_000L).toInt()
    return if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m"
}
