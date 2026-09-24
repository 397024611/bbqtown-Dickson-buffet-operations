package com.bbqtown.dickson.ops

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class PremiumOpsActivity : ComponentActivity() {
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("bbqtown_ops_v2", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = BrandInk,
                    secondary = BrandGold,
                    background = BrandCream,
                    surface = BrandSurface
                )
            ) {
                PremiumRoot(
                    initialRole = prefs.getString("role", "").orEmpty(),
                    saveRole = { prefs.edit().putString("role", it).apply() },
                    openWeb = { path -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bbqtowndickson.com$path"))) }
                )
            }
        }
    }
}

@Composable
private fun PremiumRoot(initialRole: String, saveRole: (String) -> Unit, openWeb: (String) -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val api = remember { FastApi(ctx) }
    var role by remember { mutableStateOf(initialRole) }
    var page by remember { mutableStateOf(if (role.isBlank()) "role" else "home") }

    when (page) {
        "role" -> PremiumRolePicker(role) {
            role = it
            saveRole(it)
            page = "home"
        }
        "prep" -> PremiumPrep(api, role) { page = "home" }
        "dishes" -> PremiumDishSetup(api) { page = "home" }
        else -> when (role) {
            "foh" -> PremiumFloor(api, onDevice = { page = "role" }, onPrep = { page = "prep" })
            "s1" -> PremiumKitchen(api, 1, onDevice = { page = "role" }, onPrep = { page = "prep" })
            "s2" -> PremiumKitchen(api, 2, onDevice = { page = "role" }, onPrep = { page = "prep" })
            "manager" -> PremiumManager(openWeb, onDevice = { page = "role" }, onPrep = { page = "prep" }, onDishes = { page = "dishes" })
            else -> page = "role"
        }
    }
}

private data class RoleChoice(val id: String, val title: String, val subtitle: String, val badge: String)

@Composable
private fun PremiumRolePicker(current: String, onPick: (String) -> Unit) {
    val roles = listOf(
        RoleChoice("foh", "FOH FLOOR", "Buffet status, refill calls and open / close", "FLOOR"),
        RoleChoice("s1", "KITCHEN 1", "Live refill queue and prep workflow", "KDS 1"),
        RoleChoice("s2", "KITCHEN 2", "Live refill queue and prep workflow", "KDS 2"),
        RoleChoice("manager", "STORE TOOLS", "Dish setup, prep, tables, bookings and vouchers", "ADMIN")
    )

    Column(Modifier.fillMaxSize().background(BrandCream)) {
        Surface(color = BrandInk, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().widthIn(max = 900.dp).padding(horizontal = 28.dp, vertical = 28.dp)) {
                Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
                    OfficialBbqTownLogo(Modifier.width(260.dp).height(104.dp).padding(12.dp))
                }
                Spacer(Modifier.height(22.dp))
                Text("DICKSON OPERATIONS", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Choose this tablet's working mode", color = Color.White.copy(alpha = .62f), fontSize = 12.sp)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 900.dp),
            contentPadding = PaddingValues(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(roles) { r ->
                val selected = current == r.id
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(r.id) },
                    color = BrandSurface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) BrandGold else BrandLine),
                    shadowElevation = if (selected) 5.dp else 1.dp
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = if (selected) BrandGold else BrandInk, shape = RoundedCornerShape(14.dp)) {
                            Text(r.badge, color = if (selected) BrandInk else Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, color = BrandInk, fontWeight = FontWeight.Black, fontSize = 19.sp)
                            Text(r.subtitle, color = BrandMuted, fontSize = 11.sp)
                        }
                        Text("›", color = BrandGold, fontWeight = FontWeight.Black, fontSize = 32.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumHeader(title: String, subtitle: String, onDevice: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Surface(color = BrandInk) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                OfficialBbqTownLogo(Modifier.width(112.dp).height(44.dp).padding(5.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(subtitle, color = Color.White.copy(alpha = .55f), fontSize = 10.sp)
            }
            trailing()
            TextButton(onClick = onDevice) { Text("DEVICE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun PremiumSyncChip(pending: Int, error: String?) {
    val bg = when {
        error != null -> BrandRed.copy(alpha = .16f)
        pending > 0 -> BrandGold.copy(alpha = .18f)
        else -> BrandGreen.copy(alpha = .16f)
    }
    val fg = when {
        error != null -> Color(0xFFFFB2AA)
        pending > 0 -> Color(0xFFFFD58B)
        else -> Color(0xFFA8E8BC)
    }
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pending > 0) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = fg)
            else Box(Modifier.size(7.dp).background(fg, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (error != null) "SYNC ISSUE" else if (pending > 0) "$pending SYNCING" else "SYNCED", color = fg, fontWeight = FontWeight.Black, fontSize = 8.sp)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = BrandSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BrandLine)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = BrandMuted, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            Spacer(Modifier.height(3.dp))
            Text(value, color = accent, fontWeight = FontWeight.Black, fontSize = 22.sp)
        }
    }
}

@Composable
private fun PremiumFloor(api: FastApi, onDevice: () -> Unit, onPrep: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(api.cachedLive()) }
    var pending by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var cat by remember { mutableStateOf("ALL") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            if (pending == 0) try {
                val fresh = api.loadLive(); snap = fresh; api.cacheLive(fresh); error = null
            } catch (e: Exception) { error = e.message }
            delay(7000)
        }
    }

    fun act(spec: String, id: Int = 0) {
        val before = snap
        snap = premiumOptimistic(snap, spec, id)
        snap?.let(api::cacheLive)
        pending++
        scope.launch {
            try { val server = api.action(before, spec, id); snap = server; api.cacheLive(server); error = null }
            catch (e: Exception) { error = e.message }
            pending--
        }
    }

    val foods = (snap?.foods ?: emptyList()).sortedBy { it.name.lowercase(Locale.ROOT) }
    val low = foods.count { it.status == "LOW" }
    val empty = foods.count { it.status == "EMPTY" }
    val ready = foods.count { it.kitchen == "ready" }
    val cats = listOf("ALL") + foods.map { it.category }.distinct().sortedBy { it.lowercase(Locale.ROOT) }

    Column(Modifier.fillMaxSize().background(BrandCream)) {
        PremiumHeader("FOH FLOOR", "Buffet command centre · local-first", onDevice) {
            PremiumSyncChip(pending, error)
            TextButton(onClick = onPrep) { Text("PREP", color = BrandGold, fontWeight = FontWeight.Black) }
        }
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MetricCard("LOW", low.toString(), BrandGold, Modifier.weight(1f))
                    MetricCard("EMPTY", empty.toString(), BrandRed, Modifier.weight(1f))
                    MetricCard("READY", ready.toString(), BrandGreen, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PremiumAction("OPEN ALL", "Start buffet service", BrandGreen, Modifier.weight(1f)) { act("start_all") }
                    PremiumAction("CLOSE ALL", "End buffet service", BrandRed, Modifier.weight(1f)) { act("close_all") }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(cats) { c ->
                        FilterChip(selected = c == cat, onClick = { cat = c }, label = { Text(c, fontWeight = FontWeight.Bold, fontSize = 10.sp) })
                    }
                }
            }
            items(foods.filter { cat == "ALL" || it.category == cat }, key = { it.id }) { f ->
                PremiumFoodCard(f, now) { act(it, f.id) }
            }
        }
    }
}

@Composable
private fun PremiumAction(title: String, subtitle: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), color = color, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(subtitle, color = Color.White.copy(alpha = .72f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun PremiumFoodCard(food: FFood, now: Long, onAction: (String) -> Unit) {
    val accent = when (food.status) {
        "LOW" -> BrandGold
        "EMPTY" -> BrandRed
        "CLOSED" -> BrandMuted
        else -> BrandGreen
    }
    val age = if (food.start > 0) now - food.start else 0L
    val ageColor = when {
        age >= 4 * 60 * 60 * 1000L -> BrandRed
        age >= 2 * 60 * 60 * 1000L -> BrandGold
        else -> BrandMuted
    }
    Surface(color = BrandSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, BrandLine), shadowElevation = 1.dp) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.width(5.dp).height(42.dp).background(accent, RoundedCornerShape(50)))
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(food.name, color = BrandInk, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text("${food.category} · KITCHEN ${food.section}", color = BrandMuted, fontSize = 9.sp)
                    if (food.start > 0 && food.status != "CLOSED") Text("AGE ${formatAge(age)}", color = ageColor, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
                PremiumBadge(food.status, accent)
            }
            Spacer(Modifier.height(11.dp))
            if (food.kitchen == "ready") {
                Button(onClick = { onAction("refilled") }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandGreen), shape = RoundedCornerShape(12.dp)) {
                    Text("✓ REFILLED · MARK GOOD", fontWeight = FontWeight.Black)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    StateButton("GOOD", BrandGreen, food.status == "GOOD", Modifier.weight(1f)) { onAction("status:GOOD") }
                    StateButton("LOW", BrandGold, food.status == "LOW", Modifier.weight(1f)) { onAction("status:LOW") }
                    StateButton("EMPTY", BrandRed, food.status == "EMPTY", Modifier.weight(1f)) { onAction("status:EMPTY") }
                }
                if (food.kitchen != "idle") {
                    Spacer(Modifier.height(8.dp))
                    Text("KITCHEN: ${food.kitchen.uppercase()}", color = if (food.kitchen == "ready") BrandGreen else BrandGold, fontWeight = FontWeight.Black, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun StateButton(text: String, color: Color, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.height(44.dp).clickable(onClick = onClick), color = if (active) color else color.copy(alpha = .10f), shape = RoundedCornerShape(11.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(text, color = if (active) Color.White else color, fontWeight = FontWeight.Black, fontSize = 10.sp) }
    }
}

@Composable
private fun PremiumBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, fontWeight = FontWeight.Black, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun PremiumKitchen(api: FastApi, section: Int, onDevice: () -> Unit, onPrep: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(api.cachedLive()) }
    var pending by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            if (pending == 0) try { val fresh = api.loadLive(); snap = fresh; api.cacheLive(fresh); error = null } catch (e: Exception) { error = e.message }
            delay(4500)
        }
    }

    fun act(spec: String, id: Int) {
        val before = snap
        snap = premiumOptimistic(snap, spec, id)
        snap?.let(api::cacheLive)
        pending++
        scope.launch {
            try { val server = api.action(before, spec, id); snap = server; api.cacheLive(server); error = null } catch (e: Exception) { error = e.message }
            pending--
        }
    }

    val tasks = (snap?.foods ?: emptyList()).filter { it.section == section && it.kitchen != "idle" }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
    val urgent = tasks.count { it.status == "EMPTY" || (it.requestedAt > 0 && now - it.requestedAt >= 5 * 60 * 1000L) }

    Column(Modifier.fillMaxSize().background(BrandInk)) {
        PremiumHeader("KITCHEN $section", "Live KDS · ${tasks.size} active · $urgent urgent", onDevice) {
            PremiumSyncChip(pending, error)
            Button(onClick = onPrep, colors = ButtonDefaults.buttonColors(containerColor = BrandGold), shape = RoundedCornerShape(10.dp)) { Text("PREP", color = BrandInk, fontWeight = FontWeight.Black) }
        }
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ALL CLEAR", color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
                    Text("No refill requests", color = Color.White.copy(alpha = .45f), fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                items(tasks, key = { it.id }) { f ->
                    val elapsed = if (f.requestedAt > 0) now - f.requestedAt else 0L
                    val urgentTask = f.status == "EMPTY" || elapsed >= 5 * 60 * 1000L
                    Surface(color = BrandSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(2.dp, if (urgentTask) BrandRed else BrandGold)) {
                        Column(Modifier.padding(17.dp)) {
                            Row {
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, color = BrandInk, fontWeight = FontWeight.Black, fontSize = 23.sp)
                                    Text(if (f.status == "EMPTY") "EMPTY · PRIORITY" else "LOW · REFILL REQUEST", color = if (urgentTask) BrandRed else BrandGold, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                    if (elapsed > 0) Text("WAIT ${formatAge(elapsed)}${if (elapsed >= 5 * 60 * 1000L) " · SLA" else ""}", color = if (elapsed >= 5 * 60 * 1000L) BrandRed else BrandMuted, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }
                                PremiumBadge(f.kitchen.uppercase(), when (f.kitchen) { "ready" -> BrandGreen; "preparing" -> Color(0xFF536FD3); else -> BrandGold })
                            }
                            Spacer(Modifier.height(13.dp))
                            when (f.kitchen) {
                                "requested" -> Button(onClick = { act("kitchen_prepare", f.id) }, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandInk), shape = RoundedCornerShape(12.dp)) { Text("START PREPARING", fontWeight = FontWeight.Black) }
                                "preparing" -> Button(onClick = { act("kitchen_ready", f.id) }, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandGreen), shape = RoundedCornerShape(12.dp)) { Text("MARK READY", fontWeight = FontWeight.Black) }
                                else -> Surface(color = BrandGreen.copy(alpha = .11f), shape = RoundedCornerShape(12.dp)) { Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) { Text("READY · WAITING FOR FOH", color = BrandGreen, fontWeight = FontWeight.Black) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumManager(openWeb: (String) -> Unit, onDevice: () -> Unit, onPrep: () -> Unit, onDishes: () -> Unit) {
    Column(Modifier.fillMaxSize().background(BrandCream)) {
        PremiumHeader("STORE TOOLS", "Manager workspace", onDevice)
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            item { Text("DAILY OPERATIONS", color = BrandMuted, fontWeight = FontWeight.Black, fontSize = 10.sp) }
            item { ManagerTool("DISH SETUP", "Add, edit and remove buffet dishes", BrandGold, onDishes) }
            item { ManagerTool("KITCHEN PREP", "Tomorrow's preparation checklist", BrandInk, onPrep) }
            item { ManagerTool("TABLES", "Seating and table status", Color(0xFF536FD3)) { openWeb("/tables.html") } }
            item { ManagerTool("BOOKINGS", "Reservation list", BrandGreen) { openWeb("/reservations.html") } }
            item { ManagerTool("VERIFY VOUCHER", "Redeem customer vouchers", Color(0xFF536FD3)) { openWeb("/vouchers") } }
        }
    }
}

@Composable
private fun ManagerTool(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), color = BrandSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, BrandLine)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(13.dp).background(color, CircleShape))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = BrandInk, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(subtitle, color = BrandMuted, fontSize = 10.sp)
            }
            Text("›", color = color, fontWeight = FontWeight.Black, fontSize = 30.sp)
        }
    }
}

@Composable
private fun PremiumPrep(api: FastApi, role: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val date = remember { premiumTomorrow() }
    val section = when (role) { "s1" -> "S1"; "s2" -> "S2"; else -> "ALL" }
    var list by remember { mutableStateOf(api.cachedPrep(date, section)) }
    var adding by remember { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(list.count { it.pending }) }
    var error by remember { mutableStateOf<String?>(null) }

    fun sorted(items: List<FPrep>) = items.sortedWith(compareBy<FPrep>({ it.completed }, { !it.pending }, { it.id }))

    LaunchedEffect(date, section) {
        try { val fresh = api.loadPrep(date, section); list = sorted(fresh); api.cachePrep(date, section, list); error = null }
        catch (e: Exception) { error = e.message }
    }

    fun toggle(item: FPrep) {
        if (item.id < 0 && item.failed) return
        val target = !item.completed
        list = sorted(list.map { if (it.id == item.id) it.copy(completed = target, pending = true, failed = false) else it })
        api.cachePrep(date, section, list)
        if (item.id <= 0) return
        pending++
        scope.launch {
            try {
                val server = api.togglePrep(item.id, target)
                list = sorted(list.map { if (it.id == item.id) server else it }); api.cachePrep(date, section, list); error = null
            } catch (e: Exception) {
                list = sorted(list.map { if (it.id == item.id) it.copy(pending = false, failed = true) else it }); api.cachePrep(date, section, list); error = e.message
            }
            pending--
        }
    }

    fun sendTemp(temp: FPrep) {
        pending++
        scope.launch {
            try {
                val created = api.addPrep(date, temp.item, temp.quantity, temp.notes, section)
                val latest = list.find { it.id == temp.id }
                list = sorted(list.map { if (it.id == temp.id) created.copy(completed = latest?.completed ?: false, pending = false, failed = false) else it })
                if (latest?.completed == true) {
                    val toggled = api.togglePrep(created.id, true)
                    list = sorted(list.map { if (it.id == created.id) toggled else it })
                }
                api.cachePrep(date, section, list); error = null
            } catch (e: Exception) {
                list = sorted(list.map { if (it.id == temp.id) it.copy(pending = false, failed = true) else it }); api.cachePrep(date, section, list); error = e.message
            }
            pending--
        }
    }

    val done = list.count { it.completed }
    val progress = if (list.isEmpty()) 0f else done.toFloat() / list.size.toFloat()

    Column(Modifier.fillMaxSize().background(BrandCream)) {
        Surface(color = BrandInk) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ BACK", color = Color.White, fontWeight = FontWeight.Black) }
                Column(Modifier.weight(1f)) {
                    Text("TOMORROW PREP", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("$date · $section", color = Color.White.copy(alpha = .52f), fontSize = 9.sp)
                }
                PremiumSyncChip(pending, error)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { adding = true }, colors = ButtonDefaults.buttonColors(containerColor = BrandGold), shape = RoundedCornerShape(10.dp)) { Text("+ ADD", color = BrandInk, fontWeight = FontWeight.Black) }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row { Text("$done / ${list.size} COMPLETE", color = BrandInk, fontWeight = FontWeight.Black); Spacer(Modifier.weight(1f)); Text("${(progress * 100).toInt()}%", color = BrandGreen, fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(7.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(7.dp), color = BrandGreen, trackColor = BrandLine)
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted(list), key = { it.id }) { item ->
                val rowClick: () -> Unit = if (item.id < 0 && item.failed) {
                    { list = sorted(list.map { if (it.id == item.id) it.copy(pending = true, failed = false) else it }); api.cachePrep(date, section, list); sendTemp(item.copy(pending = true, failed = false)) }
                } else { { toggle(item) } }
                Surface(color = if (item.completed) BrandGreen.copy(alpha = .09f) else BrandSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if (item.failed) BrandRed else BrandLine)) {
                    Row(Modifier.fillMaxWidth().clickable(onClick = rowClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.completed, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = BrandGreen))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.item, color = if (item.completed) BrandGreen else BrandInk, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            val meta = listOf(item.quantity, item.section, item.notes).filter { it.isNotBlank() }.joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, color = BrandMuted, fontSize = 10.sp)
                        }
                        when {
                            item.failed -> Text("TAP TO RETRY", color = BrandRed, fontWeight = FontWeight.Black, fontSize = 8.sp)
                            item.pending -> CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = BrandGold)
                            item.completed -> Text("DONE", color = BrandGreen, fontWeight = FontWeight.Black, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }

    if (adding) PremiumPrepDialog(onDismiss = { adding = false }) { name, qty, notes ->
        val temp = FPrep(-System.currentTimeMillis(), section, name, qty, notes, false, pending = true)
        list = sorted(listOf(temp) + list); api.cachePrep(date, section, list); adding = false; sendTemp(temp)
    }
}

@Composable
private fun PremiumPrepDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add prep item", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Item") }, singleLine = true)
                OutlinedTextField(qty, { qty = it }, label = { Text("Quantity") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), qty.trim(), notes.trim()) }, colors = ButtonDefaults.buttonColors(containerColor = BrandInk)) { Text("ADD") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun PremiumDishSetup(api: FastApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(api.cachedLive()) }
    var add by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<FFood?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { try { snap = api.loadLive(); snap?.let(api::cacheLive); error = null } catch (e: Exception) { error = e.message } }

    Column(Modifier.fillMaxSize().background(BrandCream)) {
        Surface(color = BrandInk) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ BACK", color = Color.White, fontWeight = FontWeight.Black) }
                Column(Modifier.weight(1f)) { Text("DISH SETUP", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp); Text("Live buffet menu configuration", color = Color.White.copy(alpha = .52f), fontSize = 9.sp) }
                Button(onClick = { add = true }, colors = ButtonDefaults.buttonColors(containerColor = BrandGold)) { Text("+ ADD", color = BrandInk, fontWeight = FontWeight.Black) }
            }
        }
        error?.let { Text(it, color = BrandRed, modifier = Modifier.padding(14.dp)) }
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((snap?.foods ?: emptyList()).sortedBy { it.name.lowercase(Locale.ROOT) }, key = { it.id }) { f ->
                Surface(Modifier.fillMaxWidth().clickable { edit = f }, color = BrandSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BrandLine)) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(f.name, color = BrandInk, fontWeight = FontWeight.Black, fontSize = 15.sp); Text("${f.category} · KITCHEN ${f.section}", color = BrandMuted, fontSize = 9.sp) }
                        Text("EDIT ›", color = BrandGold, fontWeight = FontWeight.Black, fontSize = 9.sp)
                    }
                }
            }
        }
    }

    if (add) PremiumDishDialog(null, dismiss = { add = false }, save = { n, c, s -> scope.launch { try { snap = api.addDish(snap, n, c, s); snap?.let(api::cacheLive); add = false; error = null } catch (e: Exception) { error = e.message } } })
    edit?.let { f ->
        PremiumDishDialog(f, dismiss = { edit = null }, save = { n, c, s -> scope.launch { try { snap = api.updateDish(snap, f.id, n, c, s); snap?.let(api::cacheLive); edit = null; error = null } catch (e: Exception) { error = e.message } } }, delete = { scope.launch { try { snap = api.deleteDish(snap, f.id); snap?.let(api::cacheLive); edit = null; error = null } catch (e: Exception) { error = e.message } } })
    }
}

@Composable
private fun PremiumDishDialog(food: FFood?, dismiss: () -> Unit, save: (String, String, Int) -> Unit, delete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(food?.name.orEmpty()) }
    var cat by remember { mutableStateOf(food?.category.orEmpty()) }
    var sec by remember { mutableIntStateOf(food?.section ?: 1) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (food == null) "Add dish" else "Edit dish", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Dish name") })
                OutlinedTextField(cat, { cat = it }, label = { Text("Category") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(sec == 1, { sec = 1 }, { Text("KITCHEN 1") })
                    FilterChip(sec == 2, { sec = 2 }, { Text("KITCHEN 2") })
                }
                if (delete != null) TextButton(onClick = delete) { Text("REMOVE DISH", color = BrandRed, fontWeight = FontWeight.Black) }
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) save(name.trim(), cat.trim().ifBlank { "Other" }, sec) }, colors = ButtonDefaults.buttonColors(containerColor = BrandInk)) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("CANCEL") } }
    )
}

private fun premiumOptimistic(s: FSnap?, spec: String, id: Int): FSnap? {
    if (s == null) return null
    val now = System.currentTimeMillis()
    if (spec == "start_all") return s.copy(foods = s.foods.map { it.copy(status = "GOOD", kitchen = "idle", start = now, requestedAt = 0) })
    if (spec == "close_all") return s.copy(foods = s.foods.map { it.copy(status = "CLOSED", kitchen = "idle", requestedAt = 0) })
    return s.copy(foods = s.foods.map { f ->
        if (f.id != id) f else when {
            spec.startsWith("status:") -> {
                val n = spec.substringAfter(':')
                if (n == "LOW" || n == "EMPTY") f.copy(status = n, kitchen = if (f.kitchen == "idle") "requested" else f.kitchen, requestedAt = if (f.kitchen == "idle") now else f.requestedAt)
                else f.copy(status = "GOOD", kitchen = if (f.kitchen == "requested") "idle" else f.kitchen)
            }
            spec == "kitchen_prepare" -> f.copy(kitchen = "preparing")
            spec == "kitchen_ready" -> f.copy(kitchen = "ready")
            spec == "refilled" -> f.copy(status = "GOOD", kitchen = "idle", start = now, requestedAt = 0)
            else -> f
        }
    })
}

private fun formatAge(ms: Long): String {
    val totalMin = (ms.coerceAtLeast(0) / 60000).toInt()
    return if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m"
}

private fun premiumTomorrow(): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Australia/Sydney"))
    cal.add(Calendar.DAY_OF_YEAR, 1)
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
}
