package com.bbqtown.dickson.ops

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private val FInk=Color(0xFF142018); private val FCanvas=Color(0xFFF6F4EE); private val FLine=Color(0xFFE2E7E1)
private val FMuted=Color(0xFF69736C); private val FGreen=Color(0xFF267548); private val FGreenSoft=Color(0xFFE7F4EA)
private val FGold=Color(0xFFC58818); private val FGoldSoft=Color(0xFFFFF0D3); private val FRed=Color(0xFFB82D2D); private val FRedSoft=Color(0xFFFFE8E5)
private val FBlue=Color(0xFF536FD3); private val FClosed=Color(0xFF7B837D)

class FastOpsActivity:ComponentActivity(){
    private lateinit var prefs:android.content.SharedPreferences
    private var tone:ToneGenerator?=null
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs=getSharedPreferences("bbqtown_ops_v2",Context.MODE_PRIVATE)
        setContent{MaterialTheme(colorScheme=lightColorScheme(primary=FInk,secondary=FGold,background=FCanvas,surface=Color.White)){FastRoot(prefs.getString("role","").orEmpty(),{prefs.edit().putString("role",it).apply()},{p->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://bbqtowndickson.com$p")))},{alert()})}}
    }
    private fun alert(){try{if(tone==null)tone=ToneGenerator(AudioManager.STREAM_MUSIC,100);tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,500)}catch(_:Exception){};try{val v=getSystemService(Context.VIBRATOR_SERVICE) as Vibrator;if(android.os.Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createOneShot(250,180))else{@Suppress("DEPRECATION") v.vibrate(250)}}catch(_:Exception){}}
    override fun onDestroy(){tone?.release();tone=null;super.onDestroy()}
}

data class FFood(val id:Int,val name:String,val category:String,val section:Int,val status:String,val kitchen:String,val start:Long,val requestedAt:Long)
data class FSnap(val foods:List<FFood>,val updatedAt:Long)
data class FPrep(val id:Long,val section:String,val item:String,val quantity:String,val notes:String,val completed:Boolean,val pending:Boolean=false,val failed:Boolean=false)

@Composable private fun FastRoot(initial:String,save:(String)->Unit,openWeb:(String)->Unit,alert:()->Unit){
    val ctx=LocalContext.current.applicationContext;val api=remember{FastApi(ctx)};var role by remember{mutableStateOf(initial)};var page by remember{mutableStateOf(if(role.isBlank())"role" else "home")}
    when(page){
        "role"->RolePick(role,{role=it;save(it);page="home"},{if(role.isNotBlank())page="home"})
        "prep"->PrepFast(api,role){page="home"}
        "dishes"->DishFast(api){page="home"}
        else->when(role){"foh"->FloorFast(api,{page="role"},{page="prep"});"s1"->KitchenFast(api,1,alert,{page="role"},{page="prep"});"s2"->KitchenFast(api,2,alert,{page="role"},{page="prep"});"manager"->ManagerFast(openWeb,{page="role"},{page="prep"},{page="dishes"});else->page="role"}
    }
}

@Composable private fun Header(title:String,sub:String,onDevice:()->Unit,trailing:@Composable RowScope.()->Unit={}){Surface(color=FInk){Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,color=Color.White,fontWeight=FontWeight.Black,fontSize=22.sp);Text(sub,color=Color(0xFFB9C5BC),fontSize=11.sp)};trailing();TextButton(onClick=onDevice){Text("DEVICE",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Black)}}}}
@Composable private fun RolePick(current:String,onPick:(String)->Unit,onCancel:()->Unit){val roles=listOf("foh" to "FOH FLOOR","s1" to "KITCHEN 1","s2" to "KITCHEN 2","manager" to "STORE TOOLS");Surface(Modifier.fillMaxSize(),color=FInk){Column(Modifier.fillMaxWidth().widthIn(max=800.dp).padding(28.dp)){Text("BBQ TOWN",color=FGold,fontWeight=FontWeight.Black);Text("OPERATIONS",color=Color.White,fontWeight=FontWeight.Black,fontSize=34.sp);Spacer(Modifier.height(24.dp));roles.forEach{r->Surface(Modifier.fillMaxWidth().padding(vertical=6.dp).clickable{onPick(r.first)},color=if(current==r.first)Color(0xFF263B2B)else Color(0xFF202C23),shape=RoundedCornerShape(17.dp),border=BorderStroke(1.dp,if(current==r.first)FGold else Color(0xFF344139))){Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(10.dp).background(if(current==r.first)FGold else FMuted,CircleShape));Spacer(Modifier.width(14.dp));Text(r.second,color=Color.White,fontWeight=FontWeight.Black,fontSize=18.sp)}}};if(current.isNotBlank())TextButton(onClick=onCancel){Text("CANCEL",color=Color.White)}}}}

@Composable private fun SyncChip(pending:Int,error:String?){when{pending>0->AssistChip(onClick={},label={Text("$pending syncing",fontSize=10.sp)},leadingIcon={CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=2.dp)});error!=null->AssistChip(onClick={},label={Text("Sync issue",fontSize=10.sp)},colors=AssistChipDefaults.assistChipColors(labelColor=FRed));else->AssistChip(onClick={},label={Text("Synced",fontSize=10.sp)},colors=AssistChipDefaults.assistChipColors(labelColor=FGreen))}}

@Composable private fun FloorFast(api:FastApi,onDevice:()->Unit,onPrep:()->Unit){
    val scope=rememberCoroutineScope();var snap by remember{mutableStateOf(api.cachedLive())};var pending by remember{mutableIntStateOf(0)};var error by remember{mutableStateOf<String?>(null)};var cat by remember{mutableStateOf("ALL")}
    LaunchedEffect(Unit){while(true){if(pending==0)try{val f=api.loadLive();snap=f;api.cacheLive(f);error=null}catch(e:Exception){error=e.message};delay(7000)}}
    fun action(spec:String,id:Int=0){val before=snap;snap=optimisticFast(snap,spec,id);snap?.let{api.cacheLive(it)};pending++;scope.launch{try{val server=api.action(before,spec,id);snap=server;api.cacheLive(server);error=null}catch(e:Exception){error=e.message};pending--}}
    val foods=(snap?.foods?:emptyList()).sortedBy{it.name.lowercase(Locale.ROOT)};val cats=listOf("ALL")+foods.map{it.category}.distinct().sortedBy{it.lowercase(Locale.ROOT)}
    Column(Modifier.fillMaxSize().background(FCanvas)){Header("FOH FLOOR","Instant local actions · background sync",onDevice){SyncChip(pending,error);TextButton(onClick=onPrep){Text("PREP",color=FGold,fontWeight=FontWeight.Black)}};Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)){BigAction("OPEN ALL",FGreen,Modifier.weight(1f)){action("start_all")};BigAction("CLOSE ALL",FRed,Modifier.weight(1f)){action("close_all")}};LazyRow(contentPadding=PaddingValues(horizontal=14.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){items(cats){c->FilterChip(selected=c==cat,onClick={cat=c},label={Text(c,fontSize=10.sp)})}};LazyColumn(contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(foods.filter{cat=="ALL"||it.category==cat},key={it.id}){f->FoodCard(f){action(it,f.id)}}}}
}
@Composable private fun BigAction(t:String,c:Color,m:Modifier,on:()->Unit){Surface(m.height(62.dp).clickable(onClick=on),color=c,shape=RoundedCornerShape(15.dp)){Box(contentAlignment=Alignment.Center){Text(t,color=Color.White,fontWeight=FontWeight.Black)}}}
@Composable private fun FoodCard(f:FFood,on:(String)->Unit){val c=when(f.status){"LOW"->FGold;"EMPTY"->FRed;"CLOSED"->FClosed;else->FGreen};Surface(color=Color.White,shape=RoundedCornerShape(15.dp),border=BorderStroke(1.dp,FLine)){Column(Modifier.padding(14.dp)){Row{Column(Modifier.weight(1f)){Text(f.name,fontWeight=FontWeight.Black,fontSize=16.sp);Text("${f.category} · Kitchen ${f.section}",color=FMuted,fontSize=10.sp)};Status(f.status,c)};Spacer(Modifier.height(9.dp));if(f.kitchen=="ready")Button(onClick={on("refilled")},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=FGreen)){Text("REFILLED · GOOD",fontWeight=FontWeight.Black)}else Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){State("GOOD",FGreen,FGreenSoft,f.status=="GOOD",Modifier.weight(1f)){on("status:GOOD")};State("LOW",FGold,FGoldSoft,f.status=="LOW",Modifier.weight(1f)){on("status:LOW")};State("EMPTY",FRed,FRedSoft,f.status=="EMPTY",Modifier.weight(1f)){on("status:EMPTY")}}}}}
@Composable private fun State(t:String,c:Color,soft:Color,active:Boolean,m:Modifier,on:()->Unit){Surface(m.height(46.dp).clickable(onClick=on),color=if(active)c else soft,shape=RoundedCornerShape(10.dp)){Box(contentAlignment=Alignment.Center){Text(t,color=if(active)Color.White else c,fontWeight=FontWeight.Black,fontSize=11.sp)}}}
@Composable private fun Status(t:String,c:Color){Surface(color=c.copy(alpha=.13f),shape=RoundedCornerShape(50)){Text(t,color=c,fontWeight=FontWeight.Black,fontSize=9.sp,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp))}}

@Composable private fun KitchenFast(api:FastApi,section:Int,alert:()->Unit,onDevice:()->Unit,onPrep:()->Unit){
    val scope=rememberCoroutineScope();var snap by remember{mutableStateOf(api.cachedLive(section))};var pending by remember{mutableIntStateOf(0)};var error by remember{mutableStateOf<String?>(null)};var oldNew by remember{mutableIntStateOf(-1)}
    LaunchedEffect(Unit){while(true){if(pending==0)try{val f=api.loadLive(section);snap=f;api.cacheLive(f,section);error=null}catch(e:Exception){error=e.message};delay(4500)}}
    val tasks=(snap?.foods?:emptyList()).filter{it.section==section&&it.kitchen!="idle"}.sortedBy{it.name.lowercase(Locale.ROOT)};val nc=tasks.count{it.kitchen=="requested"};LaunchedEffect(nc){if(oldNew>=0&&nc>oldNew)alert();oldNew=nc}
    fun action(spec:String,id:Int){val before=snap;snap=optimisticFast(snap,spec,id);snap?.let{api.cacheLive(it,section)};pending++;scope.launch{try{val s=api.action(before,spec,id,section);snap=s;api.cacheLive(s,section);error=null}catch(e:Exception){error=e.message};pending--}}
    Column(Modifier.fillMaxSize().background(FInk)){Header("KITCHEN $section","Local-first KDS",onDevice){SyncChip(pending,error);Button(onClick=onPrep,colors=ButtonDefaults.buttonColors(containerColor=FGold)){Text("PREP",color=FInk,fontWeight=FontWeight.Black)}};LazyColumn(contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){items(tasks,key={it.id}){f->Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=BorderStroke(2.dp,if(f.status=="EMPTY")FRed else FGold)){Column(Modifier.padding(16.dp)){Row{Column(Modifier.weight(1f)){Text(f.name,fontWeight=FontWeight.Black,fontSize=21.sp);Text(if(f.status=="EMPTY")"EMPTY · URGENT" else "LOW · PREP NEXT",color=if(f.status=="EMPTY")FRed else FGold,fontWeight=FontWeight.Black,fontSize=10.sp)};Status(f.kitchen.uppercase(),if(f.kitchen=="ready")FGreen else if(f.kitchen=="preparing")FBlue else FGold)};Spacer(Modifier.height(12.dp));when(f.kitchen){"requested"->Button(onClick={action("kitchen_prepare",f.id)},modifier=Modifier.fillMaxWidth().height(56.dp),colors=ButtonDefaults.buttonColors(containerColor=FInk)){Text("START PREPARING",fontWeight=FontWeight.Black)};"preparing"->Button(onClick={action("kitchen_ready",f.id)},modifier=Modifier.fillMaxWidth().height(56.dp),colors=ButtonDefaults.buttonColors(containerColor=FGreen)){Text("MARK READY",fontWeight=FontWeight.Black)};else->Surface(color=FGreenSoft,shape=RoundedCornerShape(11.dp)){Box(Modifier.fillMaxWidth().height(56.dp),contentAlignment=Alignment.Center){Text("READY · WAITING FOR FOH",color=FGreen,fontWeight=FontWeight.Black)}}}}}}}}
}

@Composable private fun ManagerFast(openWeb:(String)->Unit,onDevice:()->Unit,onPrep:()->Unit,onDishes:()->Unit){Column(Modifier.fillMaxSize().background(FCanvas)){Header("STORE TOOLS","Manager controls",onDevice);Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Tool("DISH SETUP","Add, edit or remove dishes",FGold,onDishes);Tool("KITCHEN PREP","Tomorrow prep list",FInk,onPrep);Tool("TABLES","Seating and table status",FBlue){openWeb("/tables.html")};Tool("BOOKINGS","Reservations",FGreen){openWeb("/reservations.html")};Tool("VERIFY VOUCHER","Voucher redemption",FBlue){openWeb("/vouchers")};Tool("SYSTEM HEALTH","Devices, sync and app versions",FRed){openWeb("/system-health.html")}}}}
@Composable private fun Tool(t:String,s:String,c:Color,on:()->Unit){Surface(Modifier.fillMaxWidth().clickable(onClick=on),color=Color.White,shape=RoundedCornerShape(15.dp),border=BorderStroke(1.dp,FLine)){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(12.dp).background(c,CircleShape));Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(t,fontWeight=FontWeight.Black,fontSize=16.sp);Text(s,color=FMuted,fontSize=10.sp)};Text("›",color=c,fontSize=30.sp)}}}

@Composable private fun PrepFast(api:FastApi,role:String,onBack:()->Unit){
    val scope=rememberCoroutineScope();val date=remember{tomorrowFast()};val section=when(role){"s1"->"S1";"s2"->"S2";else->"ALL"};var list by remember{mutableStateOf(api.cachedPrep(date,section))};var add by remember{mutableStateOf(false)};var pending by remember{mutableIntStateOf(list.count{it.pending})};var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(date,section){try{val fresh=api.loadPrep(date,section);list=fresh;api.cachePrep(date,section,fresh);error=null}catch(e:Exception){error=e.message}}
    fun sort(x:List<FPrep>)=x.sortedWith(compareBy<FPrep>({it.completed},{!it.pending},{it.id}))
    fun toggle(item:FPrep){val target=!item.completed;list=sort(list.map{if(it.id==item.id)it.copy(completed=target,pending=true,failed=false)else it});api.cachePrep(date,section,list);pending++;scope.launch{try{if(item.id>0){val server=api.togglePrep(item.id,target);list=sort(list.map{if(it.id==item.id)server else it});api.cachePrep(date,section,list)};error=null}catch(e:Exception){list=sort(list.map{if(it.id==item.id)it.copy(pending=false,failed=true)else it});api.cachePrep(date,section,list);error=e.message};pending--}}
    Column(Modifier.fillMaxSize().background(FCanvas)){Surface(color=FInk){Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick=onBack){Text("‹ BACK",color=Color.White,fontWeight=FontWeight.Black)};Column(Modifier.weight(1f)){Text("TOMORROW PREP",color=Color.White,fontWeight=FontWeight.Black,fontSize=21.sp);Text("$date · $section",color=Color(0xFFB9C5BC),fontSize=10.sp)};SyncChip(pending,error);Button(onClick={add=true},colors=ButtonDefaults.buttonColors(containerColor=FGold)){Text("+ ADD",color=FInk,fontWeight=FontWeight.Black)}}};val done=list.count{it.completed};Row(Modifier.fillMaxWidth().padding(16.dp)){Text("$done / ${list.size} complete",fontWeight=FontWeight.Black);Spacer(Modifier.weight(1f));Text(if(list.isEmpty())"—" else "${done*100/list.size}%",fontWeight=FontWeight.Black)};LazyColumn(contentPadding=PaddingValues(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(sort(list),key={it.id}){i->Surface(color=if(i.completed)FGreenSoft else Color.White,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,if(i.failed)FRed else FLine)){Row(Modifier.fillMaxWidth().clickable{toggle(i)}.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(i.completed,null,colors=CheckboxDefaults.colors(checkedColor=FGreen));Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text(i.item,fontWeight=FontWeight.Black,fontSize=15.sp,color=if(i.completed)FGreen else FInk);val meta=listOf(i.quantity,i.section,i.notes).filter{it.isNotBlank()}.joinToString(" · ");if(meta.isNotBlank())Text(meta,color=FMuted,fontSize=10.sp)};when{ i.failed->Text("RETRY",color=FRed,fontWeight=FontWeight.Black,fontSize=9.sp);i.pending->CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp);i.completed->Text("DONE",color=FGreen,fontWeight=FontWeight.Black,fontSize=9.sp)}}}}}}
    if(add)PrepDialog(onDismiss={add=false}){name,qty,notes->val tempId=-System.currentTimeMillis();val temp=FPrep(tempId,section,name,qty,notes,false,pending=true);list=sort(listOf(temp)+list);api.cachePrep(date,section,list);add=false;pending++;scope.launch{try{val created=api.addPrep(date,name,qty,notes,section);val tempState=list.find{it.id==tempId};list=sort(list.map{if(it.id==tempId)created.copy(completed=tempState?.completed?:false,pending=false)else it});if(tempState?.completed==true){val toggled=api.togglePrep(created.id,true);list=sort(list.map{if(it.id==created.id)toggled else it})};api.cachePrep(date,section,list);error=null}catch(e:Exception){list=sort(list.map{if(it.id==tempId)it.copy(pending=false,failed=true)else it});api.cachePrep(date,section,list);error=e.message};pending--}}
}
@Composable private fun PrepDialog(onDismiss:()->Unit,onSave:(String,String,String)->Unit){var name by remember{mutableStateOf("")};var qty by remember{mutableStateOf("")};var notes by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text("Add prep item",fontWeight=FontWeight.Black)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text("Item")},singleLine=true);OutlinedTextField(qty,{qty=it},label={Text("Quantity")},singleLine=true);OutlinedTextField(notes,{notes=it},label={Text("Notes")})}},confirmButton={Button(onClick={if(name.isNotBlank())onSave(name.trim(),qty.trim(),notes.trim())},colors=ButtonDefaults.buttonColors(containerColor=FInk)){Text("ADD")}},dismissButton={TextButton(onClick=onDismiss){Text("CANCEL")}})}

@Composable private fun DishFast(api:FastApi,onBack:()->Unit){val scope=rememberCoroutineScope();var snap by remember{mutableStateOf(api.cachedLive())};var edit by remember{mutableStateOf<FFood?>(null)};var add by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};LaunchedEffect(Unit){try{snap=api.loadLive();snap?.let{api.cacheLive(it)}}catch(e:Exception){error=e.message}};Column(Modifier.fillMaxSize().background(FCanvas)){Surface(color=FInk){Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick=onBack){Text("‹ BACK",color=Color.White)};Text("DISH SETUP",color=Color.White,fontWeight=FontWeight.Black,fontSize=21.sp,modifier=Modifier.weight(1f));Button(onClick={add=true},colors=ButtonDefaults.buttonColors(containerColor=FGold)){Text("+ ADD",color=FInk)}}};error?.let{Text(it,color=FRed,modifier=Modifier.padding(10.dp))};LazyColumn(contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){items(snap?.foods?:emptyList(),key={it.id}){f->Surface(Modifier.fillMaxWidth().clickable{edit=f},color=Color.White,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,FLine)){Row(Modifier.padding(14.dp)){Column(Modifier.weight(1f)){Text(f.name,fontWeight=FontWeight.Black);Text("${f.category} · Kitchen ${f.section}",color=FMuted,fontSize=10.sp)};Text("EDIT ›",color=FGold,fontWeight=FontWeight.Black,fontSize=10.sp)}}}}};if(add)DishDialog(null,{add=false},{n,c,s->scope.launch{try{snap=api.addDish(snap,n,c,s);snap?.let{api.cacheLive(it)};add=false}catch(e:Exception){error=e.message}}});edit?.let{f->DishDialog(f,{edit=null},{n,c,s->scope.launch{try{snap=api.updateDish(snap,f.id,n,c,s);snap?.let{api.cacheLive(it)};edit=null}catch(e:Exception){error=e.message}}},{scope.launch{try{snap=api.deleteDish(snap,f.id);snap?.let{api.cacheLive(it)};edit=null}catch(e:Exception){error=e.message}}})}}
@Composable private fun DishDialog(food:FFood?,dismiss:()->Unit,save:(String,String,Int)->Unit,delete:(()->Unit)?=null){var name by remember{mutableStateOf(food?.name.orEmpty())};var cat by remember{mutableStateOf(food?.category.orEmpty())};var sec by remember{mutableIntStateOf(food?.section?:1)};AlertDialog(onDismissRequest=dismiss,title={Text(if(food==null)"Add dish" else "Edit dish",fontWeight=FontWeight.Black)},text={Column{OutlinedTextField(name,{name=it},label={Text("Dish name")});OutlinedTextField(cat,{cat=it},label={Text("Category")});Row{FilterChip(sec==1,{sec=1},{Text("KITCHEN 1")});Spacer(Modifier.width(8.dp));FilterChip(sec==2,{sec=2},{Text("KITCHEN 2")})};if(delete!=null)TextButton(onClick=delete){Text("REMOVE DISH",color=FRed)}}},confirmButton={Button(onClick={if(name.isNotBlank())save(name.trim(),cat.trim().ifBlank{"Other"},sec)}){Text("SAVE")}},dismissButton={TextButton(onClick=dismiss){Text("CANCEL")}})}

private fun optimisticFast(s:FSnap?,spec:String,id:Int):FSnap?{if(s==null)return null;val now=System.currentTimeMillis();if(spec=="start_all")return s.copy(foods=s.foods.map{it.copy(status="GOOD",kitchen="idle",start=now,requestedAt=0)});if(spec=="close_all")return s.copy(foods=s.foods.map{it.copy(status="CLOSED",kitchen="idle",requestedAt=0)});return s.copy(foods=s.foods.map{f->if(f.id!=id)f else when{spec.startsWith("status:")->{val n=spec.substringAfter(':');if(n=="LOW"||n=="EMPTY")f.copy(status=n,kitchen=if(f.kitchen=="idle")"requested" else f.kitchen,requestedAt=if(f.kitchen=="idle")now else f.requestedAt)else f.copy(status="GOOD",kitchen=if(f.kitchen=="requested")"idle" else f.kitchen)};spec=="kitchen_prepare"->f.copy(kitchen="preparing");spec=="kitchen_ready"->f.copy(kitchen="ready");spec=="refilled"->f.copy(status="GOOD",kitchen="idle",start=now,requestedAt=0);else->f}})}

class FastApi(ctx:Context){
    private val app=ctx.applicationContext
    private val base="https://bbqtowndickson.com"
    private val prefs=app.getSharedPreferences("bbqtown_ops_cache",Context.MODE_PRIVATE)
    private val media="application/json; charset=utf-8".toMediaType()
    private val client=OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS).readTimeout(6,TimeUnit.SECONDS).writeTimeout(6,TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    @Volatile private var failures=0
    @Volatile private var backoffUntil=0L

    private fun liveKey(section:Int)=if(section==1||section==2)"live:$section" else "live:all"
    fun cachedLive(section:Int=0):FSnap?=runCatching{
        val raw=prefs.getString(liveKey(section),null) ?: if(section==0)prefs.getString("live",null) else null
        raw?.let{parseSnap(JSONObject(it))}
    }.getOrNull()
    fun cacheLive(s:FSnap,section:Int=0){prefs.edit().putString(liveKey(section),snapJson(s).toString()).apply();if(section==0)prefs.edit().putString("live",snapJson(s).toString()).apply()}

    private fun queue():JSONArray=runCatching{JSONArray(prefs.getString("live_queue","[]"))}.getOrDefault(JSONArray())
    @Synchronized private fun saveQueue(a:JSONArray){prefs.edit().putString("live_queue",a.toString()).apply()}
    fun pendingLiveCount():Int=queue().length()
    @Synchronized private fun enqueueLive(body:JSONObject){val a=queue();a.put(JSONObject(body.toString()));saveQueue(a)}

    private fun recordSuccess(){failures=0;backoffUntil=0L}
    private fun recordFailure(){failures=(failures+1).coerceAtMost(20);val baseDelay=when{failures<=1->5_000L;failures==2->10_000L;failures==3->20_000L;failures==4->40_000L;else->60_000L};backoffUntil=System.currentTimeMillis()+baseDelay+kotlin.random.Random.nextLong(0,1_500)}
    private fun inBackoff()=System.currentTimeMillis()<backoffUntil

    suspend fun loadLive(section:Int=0):FSnap=withContext(Dispatchers.IO){
        var cached=cachedLive(section)
        if(inBackoff()&&cached!=null)return@withContext cached
        try{flushLiveQueue();cached=cachedLive(section)?:cached}catch(e:Exception){if(cached!=null)return@withContext cached else throw e}
        DeviceSession.ensureAccessBlocking(app,pendingLiveCount())
        val qs=buildString{append("logs=0");if(section==1||section==2)append("&section=$section");if(cached!=null&&cached.updatedAt>0)append("&since=${cached.updatedAt}")}
        val root=JSONObject(req("GET","/api/ops/live?$qs",null))
        val result=if(root.optBoolean("unchanged")&&cached!=null)cached else parseSnap(root)
        cacheLive(result,section);result
    }

    suspend fun action(s:FSnap?,spec:String,id:Int,section:Int=0):FSnap=withContext(Dispatchers.IO){
        val b=JSONObject().put("expectedUpdatedAt",s?.updatedAt?:0).put("mutationId",java.util.UUID.randomUUID().toString()).put("includeLogs",false)
        if(section==1||section==2)b.put("responseSection",section)
        if(spec.startsWith("status:"))b.put("action","status").put("status",spec.substringAfter(':')).put("id",id)else b.put("action",spec).put("id",id)
        enqueueLive(b)
        try{val result=flushLiveQueue()?:cachedLive(section)?:throw Exception("Queued for sync");cacheLive(result,section);result}catch(e:Exception){throw Exception("Saved offline · will retry automatically: ${e.message?:"network unavailable"}")}
    }

    suspend fun addDish(s:FSnap?,n:String,c:String,sec:Int)=withContext(Dispatchers.IO){postLiveDirect(JSONObject().put("expectedUpdatedAt",s?.updatedAt?:0).put("mutationId",java.util.UUID.randomUUID().toString()).put("includeLogs",false).put("action","dish_add").put("name",n).put("category",c).put("section",sec))}
    suspend fun updateDish(s:FSnap?,id:Int,n:String,c:String,sec:Int)=withContext(Dispatchers.IO){postLiveDirect(JSONObject().put("expectedUpdatedAt",s?.updatedAt?:0).put("mutationId",java.util.UUID.randomUUID().toString()).put("includeLogs",false).put("action","dish_update").put("id",id).put("name",n).put("category",c).put("section",sec))}
    suspend fun deleteDish(s:FSnap?,id:Int)=withContext(Dispatchers.IO){postLiveDirect(JSONObject().put("expectedUpdatedAt",s?.updatedAt?:0).put("mutationId",java.util.UUID.randomUUID().toString()).put("includeLogs",false).put("action","dish_delete").put("id",id))}

    @Synchronized private fun flushLiveQueue():FSnap?{
        if(inBackoff())throw Exception("Sync backing off")
        var a=queue();var last:FSnap?=null
        while(a.length()>0){
            val body=a.optJSONObject(0)?:JSONObject()
            val result=postLiveDirect(body)
            val section=body.optInt("responseSection",0)
            cacheLive(result,section)
            val next=JSONArray();for(i in 1 until a.length())next.put(a.get(i));a=next;saveQueue(a);last=result
        }
        return last
    }

    private fun postLiveDirect(b:JSONObject):FSnap{
        var text=req("POST","/api/ops/live",b.toString(),allow409=true)
        var root=JSONObject(text)
        if(root.optString("error")=="STATE_CONFLICT"){
            b.put("expectedUpdatedAt",root.optLong("updatedAt",0))
            text=req("POST","/api/ops/live",b.toString());root=JSONObject(text)
        }
        if(root.has("error"))throw Exception(root.optString("error"))
        return parseSnap(root)
    }

    suspend fun loadPrep(date:String,sec:String):List<FPrep> = withContext(Dispatchers.IO){val r=JSONObject(req("GET","/api/ops/prep?date=${Uri.encode(date)}&section=${Uri.encode(sec)}",null));parsePrepArray(r.optJSONArray("items")?:JSONArray())}
    suspend fun addPrep(date:String,item:String,qty:String,notes:String,sec:String):FPrep=withContext(Dispatchers.IO){val r=JSONObject(req("POST","/api/ops/prep",JSONObject().put("action","add").put("mutationId",java.util.UUID.randomUUID().toString()).put("prepDate",date).put("item",item).put("quantity",qty).put("notes",notes).put("section",sec).put("createdBy","Kitchen").toString()));parsePrep(r.getJSONObject("item"))}
    suspend fun togglePrep(id:Long,done:Boolean):FPrep=withContext(Dispatchers.IO){val r=JSONObject(req("POST","/api/ops/prep",JSONObject().put("action","toggle").put("mutationId",java.util.UUID.randomUUID().toString()).put("id",id).put("completed",done).put("completedBy","Kitchen").toString()));parsePrep(r.getJSONObject("item"))}
    fun cachedPrep(date:String,sec:String):List<FPrep> = runCatching{parsePrepArray(JSONArray(prefs.getString("prep:$date:$sec","[]")))}.getOrDefault(emptyList())
    fun cachePrep(date:String,sec:String,list:List<FPrep>){val a=JSONArray();list.forEach{a.put(prepJson(it))};prefs.edit().putString("prep:$date:$sec",a.toString()).apply()}

    private fun req(method:String,path:String,body:String?,allow409:Boolean=false):String{
        if(inBackoff())throw Exception("Sync temporarily paused after network errors")
        val pending=pendingLiveCount()
        var token=runCatching{DeviceSession.ensureAccessBlocking(app,pending)}.getOrElse{recordFailure();throw it}
        if(token.isBlank())throw Exception("This tablet needs Manager pairing")
        fun call(access:String):Pair<Int,String>{
            val rb=Request.Builder().url(base+path).header("User-Agent","BBQTownOpsAndroid/${BuildConfig.VERSION_NAME} Native").header("Accept","application/json").header("x-bbqtown-device-token",access)
            if(method=="POST")rb.post((body?:"{}").toRequestBody(media))else rb.get()
            client.newCall(rb.build()).execute().use{r->return r.code to r.body?.string().orEmpty()}
        }
        try{
            var result=call(token)
            if(result.first==401){token=DeviceSession.ensureAccessBlocking(app,pending,force=true);if(token.isBlank())throw Exception("Device pairing expired");result=call(token)}
            val code=result.first;val text=result.second
            if(code in 200..299||(allow409&&code==409)){recordSuccess();return text}
            val message=runCatching{JSONObject(text).optString("error","Network error $code")}.getOrDefault("Network error $code")
            if(code==429||code>=500)recordFailure()
            if(code==401)DeviceSession.clearPairing(app)
            throw Exception(message)
        }catch(e:Exception){if(!e.message.orEmpty().contains("pair",ignoreCase=true))recordFailure();throw e}
    }

    private fun parseSnap(root:JSONObject):FSnap{val st=root.optJSONObject("state")?:JSONObject();val a=st.optJSONArray("foods")?:JSONArray();val f=buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(FFood(x.optInt("id"),x.optString("name","Dish"),x.optString("category","Other"),x.optInt("section",1),x.optString("status","GOOD"),x.optString("kitchen","idle"),x.optLong("start",0),x.optLong("requestedAt",0)))}};return FSnap(f.sortedBy { it.name.lowercase(Locale.ROOT) },root.optLong("updatedAt",0))}
    private fun snapJson(s:FSnap):JSONObject{val a=JSONArray();s.foods.forEach{f->a.put(JSONObject().put("id",f.id).put("name",f.name).put("category",f.category).put("section",f.section).put("status",f.status).put("kitchen",f.kitchen).put("start",f.start).put("requestedAt",f.requestedAt))};return JSONObject().put("state",JSONObject().put("foods",a)).put("updatedAt",s.updatedAt)}
    private fun parsePrepArray(a:JSONArray)=buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(parsePrep(x))}}
    private fun parsePrep(x:JSONObject)=FPrep(x.optLong("id"),x.optString("section"),x.optString("item"),x.optString("quantity"),x.optString("notes"),x.optBoolean("completed"),false,false)
    private fun prepJson(x:FPrep)=JSONObject().put("id",x.id).put("section",x.section).put("item",x.item).put("quantity",x.quantity).put("notes",x.notes).put("completed",x.completed).put("pending",x.pending).put("failed",x.failed)
}
private fun tomorrowFast():String{val z=TimeZone.getTimeZone("Australia/Sydney");val c=Calendar.getInstance(z);c.add(Calendar.DAY_OF_MONTH,1);return SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{timeZone=z}.format(c.time)}