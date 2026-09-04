package io.github.hatake716.heiseicamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.StreetViewPanoramaOptions
import com.google.android.gms.maps.StreetViewPanoramaView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF101411)
private val Panel = Color(0xFF1B211C)
private val Cream = Color(0xFFEDF0DF)
private val Muted = Color(0xFFA4AE9E)
private val Leaf = Color(0xFFD1DEA0)
private val Amber = Color(0xFFFFB277)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeiseiCameraApp(incomingLink: String?, consumeLink: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val session = rememberSaveable(saver = Saver<CameraSession, Bundle>(
        save = { value -> Bundle().apply {
            putString("pano", value.selectedPano)
            putBoolean("current", value.currentMode)
            putBoolean("tracking", value.tracking)
        } },
        restore = { state -> CameraSession(context, scope).apply {
            tracking = state.getBoolean("tracking", true)
            val id = state.getString("pano")
            if (id != null) open(id) else if (state.getBoolean("current")) chooseCurrent()
        } }
    )) { CameraSession(context, scope) }
    val motion = remember {
        MotionLocation(context, session::onLocation, session::onDirection) { session.locationStatus = it }
    }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var linkText by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        motion.start()
        if (!hasLocationPermission(context)) session.message = "現在地を使うには位置情報を許可してください。リンクから過去の風景を開くこともできます。"
    }

    fun requestLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) motion.start()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun openMaps() {
        val id = session.selectedPano
        val position = session.freshFix()
        val uri = if (id != null) mapsUrl(panoId = id)
        else if (position != null) mapsUrl(latitude = position.latitude, longitude = position.longitude)
        else "https://www.google.com/maps/"
        if (position == null && id == null) requestLocation()
        openExternal(context, uri) { session.message = it }
    }

    LaunchedEffect(Unit) {
        if (!motion.hasOrientationSensor) session.tracking = false
    }
    LaunchedEffect(incomingLink) {
        incomingLink?.let {
            session.importLink(it)
            consumeLink()
        }
    }
    DisposableEffect(lifecycle, motion) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> motion.start()
                Lifecycle.Event.ON_PAUSE -> { motion.stop(); session.pauseInputs() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) motion.start()
        onDispose { lifecycle.removeObserver(observer); motion.stop() }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Leaf, background = Ink, surface = Panel,
        onPrimary = Ink, onBackground = Cream, onSurface = Cream, secondary = Amber)) {
        Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val compact = maxHeight < 540.dp
                Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 24.dp else 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 12.dp, bottom = if (compact) 0.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(if (compact) 26.dp else 34.dp).border(1.dp, Leaf.copy(alpha = .65f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text("H", color = Leaf, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("平成カメラ", fontSize = if (compact) 17.sp else 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            if (!compact) Text("あの頃を、いまここで。", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                        }
                        IconButton(onClick = { dialog = "about" }) { Icon(Icons.Outlined.Info, "使い方と接続情報", tint = Muted) }
                    }
                    if (!compact) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(if (session.selectedPano != null) "選んだ風景" else if (session.currentMode) "現在地の風景" else "過去を探す", Leaf)
                            Spacer(Modifier.weight(1f))
                            Text("STREET VIEW FINDER", fontSize = 9.sp, color = Muted, letterSpacing = 1.3.sp)
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0xFF394336), RoundedCornerShape(20.dp)).background(Color(0xFF151C17))) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (BuildConfig.HAS_MAPS_KEY) PanoramaSurface(session, Modifier.fillMaxSize())
                            if (session.activePano == null) {
                                EmptyFinder(session, compact, onMaps = ::openMaps, onImport = { dialog = "import" })
                            } else {
                                // The SDK's attribution at the bottom remains unobscured.
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(if (session.tracking) "向きに追従" else "手動で見回す", Cream)
                                    Spacer(Modifier.weight(1f))
                                    session.distanceToPanorama()?.let { StatusPill("撮影地点まで ${it}m", Cream) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().background(Color(0xFF151A15)).padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (!compact) Text("STREET VIEW", fontSize = 9.sp, color = Muted, letterSpacing = 1.5.sp)
                                Text(session.date?.eraLabel ?: "撮影年月", fontSize = 12.sp, color = Cream)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                FilmDate(session.date?.display ?: "----.--", Modifier.width(if (compact) 120.dp else 155.dp).height(if (compact) 20.dp else 26.dp))
                                if (!compact && session.date == null) Text(if (session.activePano == null) "風景を選んでください" else "年月不明", color = Muted, fontSize = 9.sp)
                            }
                        }
                    }
                    if (!compact) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.MyLocation, null, Modifier.size(13.dp), tint = Muted)
                            Spacer(Modifier.width(6.dp))
                            Text(session.freshFix()?.let { "位置精度 ±${it.accuracyMeters.toInt()}m" } ?: "位置情報を許可して街へ", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text(session.direction?.let { (if (session.freshFix() == null) "磁北 " else "") + "%03d°".format(it.bearing.toInt()) } ?: "---°", color = Leaf, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                    session.message?.let { message ->
                        Row(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(if (compact) 4.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, color = Cream, fontSize = if (compact) 10.sp else 12.sp, modifier = Modifier.weight(1f), maxLines = if (compact) 1 else 4)
                            IconButton(onClick = { session.message = null }, Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, "メッセージを閉じる", Modifier.size(16.dp)) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SmallControl(Icons.Outlined.History, "過去を選ぶ", ::openMaps, compact = compact)
                        SmallControl(Icons.Outlined.Link, "リンクを開く", { dialog = "import" }, compact = compact)
                        SmallControl(Icons.Outlined.MyLocation, "現在地", { requestLocation(); session.chooseCurrent() }, compact = compact)
                        SmallControl(if (session.tracking) Icons.Outlined.Explore else Icons.Outlined.PanToolAlt,
                            if (session.tracking) "向きに追従" else "手動", {
                                if (motion.hasOrientationSensor) session.toggleTracking()
                                else session.message = "方位センサーがないため、風景を指で動かして見回せます。"
                            }, selected = session.tracking, compact = compact)
                    }
                    if (!compact) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.width(74.dp).clip(RoundedCornerShape(12.dp)).clickable { dialog = "bookmarks" }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.CollectionsBookmark, "しおり一覧", tint = Cream, modifier = Modifier.size(25.dp))
                                Text("しおり ${session.bookmarks.size}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(76.dp).semantics { contentDescription = "この風景をしおりに保存" }
                                    .border(2.dp, if (session.activePano != null) Cream else Muted.copy(alpha = .4f), CircleShape)
                                    .padding(6.dp).clip(CircleShape)
                                    .background(if (session.activePano != null) Cream else Color(0xFF424A3F))
                                    .clickable(enabled = session.activePano != null) {
                                        if (session.saveBookmark()) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            scope.launch { snackbar.showSnackbar("この風景と向きをしおりに保存しました") }
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.BookmarkAdd, null, tint = Ink, modifier = Modifier.size(26.dp))
                                }
                                Text("風景をしおりに", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            Column(Modifier.width(74.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("HEISEI", color = Leaf, fontSize = 10.sp, letterSpacing = 1.sp)
                                Text("1989–2019", color = Muted, fontSize = 9.sp)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { dialog = "bookmarks" }) { Text("しおり ${session.bookmarks.size}") }
                            TextButton(enabled = session.activePano != null, onClick = { session.saveBookmark() }) { Text("しおりに保存") }
                        }
                    }
                }
            }
        }

        if (dialog == "import") AlertDialog(onDismissRequest = { dialog = null },
            title = { Text("過去の風景を開く") },
            text = {
                Column {
                    Text("Google マップで「他の日付を見る」から年月を選び、共有リンクを貼り付けてください。", color = Muted)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = linkText, onValueChange = { linkText = it.take(8192) }, label = { Text("Google マップの共有リンク") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Spacer(Modifier.height(10.dp))
                    Text("共有メニューから「平成カメラ」を選んでも開けます。共有で年月が変わることがあるため、読み込み後の撮影年月を確認してください。", color = Muted, fontSize = 12.sp)
                }
            }, confirmButton = { TextButton(enabled = linkText.isNotBlank() && !session.importing, onClick = {
                session.importLink(linkText); dialog = null
            }) { Text("この風景を開く") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } })

        if (dialog == "about") ModalBottomSheet(onDismissRequest = { dialog = null }, containerColor = Panel) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("過去の街を、見つけよう。", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Instruction("01", "Google マップで年月を選ぶ", "現在地のストリートビューで「他の日付を見る」を開き、いちばん古い平成の画像を探します。履歴がない場所もあります。")
                Instruction("02", "平成カメラへ共有する", "共有メニューで平成カメラを選ぶか、リンクを貼り付けます。アプリで読み込めない過去画像は Google マップでご覧ください。")
                Instruction("03", "スマホをかざして見回す", "上下・左右の向きに風景が追従します。撮影地点と現在地は異なるため、ぴったり重ならないことがあります。")
                Instruction("04", "好きな風景にしおりを付ける", "丸いボタンはパノラマと向きを記録します。画像保存やカメラ撮影は行いません。")
                HorizontalDivider(color = Muted.copy(alpha = .2f), modifier = Modifier.padding(vertical = 16.dp))
                Text("接続情報", fontWeight = FontWeight.Bold)
                Text(if (BuildConfig.HAS_MAPS_KEY) "アプリ内ビューア：設定済み" else "アプリ内ビューア：API キー未設定", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text(if (BuildConfig.METADATA_API_KEY.isNotBlank()) "撮影年月の取得：設定済み" else "撮影年月の取得：API キー未設定", color = Muted, fontSize = 13.sp)
                if (!BuildConfig.HAS_MAPS_KEY || BuildConfig.METADATA_API_KEY.isBlank()) {
                    Text("このビルドは Google マップを開いて過去の画像を見ることができます。アプリ内で表示するには、開発者が API キーを設定してビルドしてください。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { openExternal(context, "https://github.com/hatake716/heisei-camera#ローカル設定") { session.message = it } }) { Text("開発者向けの設定手順") }
                }
                Text(session.locationStatus, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Text(session.dateStatus, color = Muted, fontSize = 12.sp)
                TextButton(onClick = { openExternal(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS, settings = true) { session.message = it } }) { Text("端末の位置情報設定を開く") }
                TextButton(onClick = { openExternal(context, "package:${context.packageName}", appSettings = true) { session.message = it } }) { Text("位置情報の許可を変更する") }
                TextButton(onClick = { openExternal(context, "https://github.com/hatake716/heisei-camera/blob/main/docs/PRIVACY.md") { session.message = it } }) { Text("プライバシーポリシー") }
                TextButton(onClick = { openExternal(context, "https://maps.google.com/help/terms_maps/") { session.message = it } }) { Text("Google マップの利用規約") }
                if (session.selectedPano != null || session.currentMode) TextButton(onClick = { session.retry(); dialog = null }) { Text("風景を読み込み直す") }
            }
        }

        if (dialog == "bookmarks") ModalBottomSheet(onDismissRequest = { dialog = null }, containerColor = Panel) {
            Text("風景のしおり", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            if (session.bookmarks.isEmpty()) {
                Text("心に残った風景を、丸いボタンで残そう。\n画像を保存せず、同じ風景を再び開けます。", color = Muted, modifier = Modifier.padding(24.dp).padding(bottom = 30.dp))
            } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(bottom = 28.dp)) {
                itemsIndexed(session.bookmarks, key = { _, value -> value.panoId }) { index, value ->
                    Row(Modifier.fillMaxWidth().clickable { session.open(value.panoId, value.bearing, value.tilt); dialog = null }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BookmarkBorder, null, tint = Leaf)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("風景のしおり ${session.bookmarks.size - index}")
                            Text("登録 " + SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.JAPAN).format(Date(value.createdAt)), color = Muted, fontSize = 11.sp)
                        }
                        IconButton(onClick = { session.deleteBookmark(value) }) { Icon(Icons.Outlined.DeleteOutline, "このしおりを削除", tint = Muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFinder(session: CameraSession, compact: Boolean, onMaps: () -> Unit, onImport: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF293A2C), Color(0xFF19261D), Color(0xFF121A15))))) {
        Canvas(Modifier.fillMaxSize()) {
            val color = Color(0xFFD1DEA0)
            val center = Offset(size.width * .5f, size.height * .44f)
            drawCircle(color.copy(alpha = .04f), size.width * .47f, center)
            drawCircle(color.copy(alpha = .06f), size.width * .35f, center, style = Stroke(1.dp.toPx()))
            drawCircle(color.copy(alpha = .09f), size.width * .22f, center, style = Stroke(1.dp.toPx()))
            val edge = 20.dp.toPx(); val arm = 17.dp.toPx()
            listOf(Offset(edge, edge) to Offset(1f, 1f), Offset(size.width-edge, edge) to Offset(-1f, 1f),
                Offset(edge, size.height-edge) to Offset(1f, -1f), Offset(size.width-edge, size.height-edge) to Offset(-1f, -1f)).forEach { (p, sign) ->
                drawLine(color.copy(alpha = .4f), p, p + Offset(sign.x * arm, 0f), 1.dp.toPx())
                drawLine(color.copy(alpha = .4f), p, p + Offset(0f, sign.y * arm), 1.dp.toPx())
            }
        }
        Column(Modifier.align(Alignment.Center).padding(if (compact) 16.dp else 28.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            if (session.loading || session.importing) {
                CircularProgressIndicator(color = Leaf, strokeWidth = 2.dp, modifier = Modifier.size(30.dp))
                Text("風景を読み込んでいます", fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
            } else {
                if (!compact) {
                    Icon(Icons.Outlined.History, null, tint = Leaf, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(18.dp))
                }
                Text(if (session.selectedPano != null) "あの頃の風景へ。" else "あの頃の街へ。", fontFamily = FontFamily.Serif, fontSize = if (compact) 21.sp else 28.sp, letterSpacing = 2.sp)
                if (!compact) Text(if (session.selectedPano != null && !BuildConfig.HAS_MAPS_KEY) "リンクを受け取りました。\nGoogle マップで風景を見られます。" else
                    "いつもの道の、懐かしい時間。\nGoogle マップで過去を選ぼう。", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 23.sp, modifier = Modifier.padding(top = 12.dp, bottom = 20.dp))
                Button(onClick = onMaps, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Leaf, contentColor = Ink)) {
                    Icon(Icons.Outlined.NorthEast, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Google マップで開く", fontSize = 13.sp)
                }
                if (!compact) TextButton(onClick = onImport) { Text("共有リンクを貼り付ける", color = Cream, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun PanoramaSurface(session: CameraSession, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { StreetViewPanoramaView(context, StreetViewPanoramaOptions().userNavigationEnabled(false).streetNamesEnabled(false)) }
    DisposableEffect(view, lifecycle) {
        view.onCreate(null)
        var started = false
        var resumed = false
        fun start() { if (!started) { view.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { view.onResume(); resumed = true } }
        fun pause() { if (resumed) { view.onPause(); resumed = false } }
        fun stop() { pause(); if (started) { view.onStop(); started = false } }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        var disposed = false
        view.getStreetViewPanoramaAsync { if (!disposed) session.attach(it) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            lifecycle.removeObserver(observer)
            session.detach()
            stop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.copy(alpha = .78f)).border(1.dp, color.copy(alpha = .22f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun SmallControl(icon: ImageVector, label: String, onClick: () -> Unit, selected: Boolean = false, compact: Boolean = false) {
    Column(Modifier.widthIn(min = 65.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = if (compact) 4.dp else 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, Modifier.size(if (compact) 18.dp else 21.dp), tint = if (selected) Leaf else Muted)
        Text(label, color = if (selected) Leaf else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = if (compact) 2.dp else 5.dp))
    }
}

@Composable
private fun Instruction(number: String, title: String, body: String) {
    Row(Modifier.padding(bottom = 18.dp)) {
        Text(number, color = Leaf, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(34.dp).padding(top = 3.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(body, color = Muted, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

/** Original seven-segment drawing: no font file, invented day, or imagery export. */
@Composable
private fun FilmDate(text: String, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "撮影年月 $text" }) {
        val widths = text.map { if (it == '.') .4f else 1f }
        val cell = size.width / (widths.sum() + (text.length - 1) * .2f)
        val glyphWidth = cell * .8f
        val height = size.height * .8f
        val top = (size.height - height) / 2
        var left = 0f
        val segments = arrayOf("abcedf", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg")
        text.forEachIndexed { index, char ->
            if (char == '.') drawCircle(Amber, cell * .075f, Offset(left + cell * .12f, top + height))
            else {
                val active = if (char.isDigit()) segments[char.digitToInt()] else "g"
                val middle = top + height / 2
                val right = left + glyphWidth
                val bottom = top + height
                val endpoints = mapOf('a' to (Offset(left, top) to Offset(right, top)),
                    'b' to (Offset(right, top) to Offset(right, middle)), 'c' to (Offset(right, middle) to Offset(right, bottom)),
                    'd' to (Offset(left, bottom) to Offset(right, bottom)), 'e' to (Offset(left, middle) to Offset(left, bottom)),
                    'f' to (Offset(left, top) to Offset(left, middle)), 'g' to (Offset(left, middle) to Offset(right, middle)))
                endpoints.forEach { (segment, points) ->
                    drawLine(if (segment in active) Amber else Amber.copy(alpha = .045f), points.first, points.second,
                        strokeWidth = cell * .09f, cap = StrokeCap.Round)
                }
            }
            left += cell * (widths[index] + .2f)
        }
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun mapsUrl(panoId: String? = null, latitude: Double? = null, longitude: Double? = null): String {
    val builder = Uri.parse("https://www.google.com/maps/@").buildUpon().appendQueryParameter("api", "1").appendQueryParameter("map_action", "pano")
    if (panoId != null) builder.appendQueryParameter("pano", panoId)
    else if (latitude != null && longitude != null) builder.appendQueryParameter("viewpoint", "$latitude,$longitude")
    return builder.build().toString()
}

private fun openExternal(context: Context, value: String, settings: Boolean = false, appSettings: Boolean = false, onError: (String) -> Unit) {
    try {
        context.startActivity(if (settings) Intent(value) else if (appSettings) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(value)) else Intent(Intent.ACTION_VIEW, Uri.parse(value)))
    } catch (_: android.content.ActivityNotFoundException) {
        onError("リンクを開けるアプリが見つかりません。")
    } catch (_: SecurityException) {
        onError("この端末ではリンクを開けませんでした。")
    }
}
