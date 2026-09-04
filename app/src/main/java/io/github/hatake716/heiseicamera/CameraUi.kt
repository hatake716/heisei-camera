package io.github.hatake716.heiseicamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF000000)
private val Panel = Color(0xFF141414)
private val Cream = Color(0xFFFAFAFA)
private val Muted = Color(0xFFA8A8A8)
private val Accent = Color(0xFFFF70A7)
private val StoryGradient = Brush.linearGradient(listOf(Color(0xFFFFC15E), Color(0xFFFF543E), Color(0xFFDC2C91), Color(0xFF8E43E7)))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeiseiCameraApp(incomingLink: String?, consumeLink: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val session = rememberSaveable(saver = Saver<CameraSession, Bundle>(
        save = { Bundle().apply {
            putString("pano", it.selectedPano)
            putBoolean("automatic", it.isAutomatic)
            putString("pendingImport", it.pendingImportLink)
            putBoolean("importedSelectionPending", it.importedSelectionPending)
            when (val target = it.target) {
                is StreetViewTarget.Nearby -> {
                    putDouble("latitude", target.location.latitude)
                    putDouble("longitude", target.location.longitude)
                }
                is StreetViewTarget.Panorama -> putString("targetPano", target.panoId)
                null -> Unit
            }
        } },
        restore = { state -> CameraSession(context, scope).apply {
            state.getString("pano")?.let(::open)
            if (state.getBoolean("automatic", true)) selectAutomatic()
            if (state.containsKey("latitude") && state.containsKey("longitude")) {
                runCatching { SceneLocation(state.getDouble("latitude"), state.getDouble("longitude")) }
                    .getOrNull()?.let { restoreTarget(StreetViewTarget.Nearby(it)) }
            } else state.getString("targetPano")?.let { restoreTarget(StreetViewTarget.Panorama(it)) }
            restoreImportedSelectionPending(state.getBoolean("importedSelectionPending"))
            message = null
            state.getString("pendingImport")?.let(::importLink)
        } },
    )) { CameraSession(context, scope) }
    var showStreetView by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraPreferences = remember { context.getSharedPreferences("camera_preferences", Context.MODE_PRIVATE) }
    var permissionRequested by rememberSaveable { mutableStateOf(cameraPreferences.getBoolean("permission_prompted", false)) }
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var cameraState by remember { mutableStateOf(CameraPreviewState.STARTING) }
    var cameraEpoch by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    var pendingLocation by remember { mutableStateOf(false) }
    var locationRequestEpoch by remember { mutableIntStateOf(0) }
    var locationProblem by rememberSaveable { mutableStateOf<String?>(null) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it } || hasLocationPermission(context)) {
            locationRequestEpoch++
            pendingLocation = session.isAutomatic
        } else {
            locationProblem = "現在地付近の風景を表示するには、位置情報の許可が必要です。"
            dialog = "location"
        }
    }
    fun requestCamera() {
        permissionRequested = true
        cameraPreferences.edit().putBoolean("permission_prompted", true).apply()
        permission.launch(Manifest.permission.CAMERA)
    }
    fun requestLocation() {
        locationProblem = null
        locationRequestEpoch++
        if (hasLocationPermission(context)) pendingLocation = true
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun openMaps() = openExternal(context,
        session.target?.let(::mapsUrl) ?: session.selectedPano?.takeIf { !session.isAutomatic }
            ?.let { mapsUrl(StreetViewTarget.Panorama(it)) } ?: "https://www.google.com/maps/",
    ) { session.message = it }
    fun returnToCamera() {
        pendingLocation = false
        if (showStreetView) cameraState = CameraPreviewState.STARTING
        showStreetView = false
        session.resume()
    }
    fun shutter() {
        if (session.isAutomatic) requestLocation()
        else if (session.selectedPano == null) dialog = "source"
        else if (session.shutter()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showStreetView = true
        }
    }
    LaunchedEffect(pendingLocation, resumed, session.isAutomatic, locationRequestEpoch) {
        if (!pendingLocation || !resumed || !session.isAutomatic) return@LaunchedEffect
        val expectedEpoch = locationRequestEpoch
        val result = currentLocation(context)
        // Cancellation also needs an immediate guard before Compose disposes this effect.
        if (!pendingLocation || !resumed || !session.isAutomatic || expectedEpoch != locationRequestEpoch) return@LaunchedEffect
        when (result) {
            is CurrentLocationResult.Success -> {
                if (session.shutter(result.location)) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showStreetView = true
                    if (result.accuracyMeters > 100f) session.message = "位置の精度が低いため、離れた風景が表示される場合があります。"
                }
            }
            CurrentLocationResult.PermissionDenied -> {
                locationProblem = "位置情報の許可が必要です。端末の設定で許可してください。"
                dialog = "location"
            }
            CurrentLocationResult.Disabled -> {
                locationProblem = "端末の位置情報がオフになっています。位置情報をオンにしてから、もう一度お試しください。"
                dialog = "location"
            }
            CurrentLocationResult.Unavailable -> {
                locationProblem = "現在地を取得できませんでした。空が見える場所へ移動するか、通信を確認してもう一度お試しください。"
                dialog = "location"
            }
        }
        pendingLocation = false
    }
    BackHandler(enabled = showStreetView && dialog == null) { returnToCamera() }
    BackHandler(enabled = pendingLocation && dialog == null) { pendingLocation = false }
    LaunchedEffect(Unit) {
        if (!cameraGranted && !permissionRequested) requestCamera()
    }
    LaunchedEffect(incomingLink) {
        incomingLink?.let {
            dialog = null
            pendingLocation = false
            session.importLink(it)
            consumeLink()
        }
    }
    LaunchedEffect(session.importedSelectionPending, session.viewEpoch) {
        if (session.consumeImportedSelection()) {
            pendingLocation = false
            showStreetView = true
            dialog = null
        }
    }
    LaunchedEffect(session.message) {
        session.message?.let { message ->
            snackbar.showSnackbar(message)
            if (session.message == message) session.message = null
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    cameraGranted = hasCameraPermission(context)
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    pendingLocation = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cream, background = Ink, surface = Panel,
        onPrimary = Ink, onBackground = Cream, onSurface = Cream, secondary = Accent)) {
        Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) { data ->
            Snackbar(snackbarData = data, containerColor = Color(0xFF262626), contentColor = Cream, actionColor = Accent)
        } }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val landscape = maxWidth > maxHeight
                Column(Modifier.fillMaxSize().padding(horizontal = if (landscape) 16.dp else 12.dp)) {
                    Row(Modifier.fillMaxWidth().height(if (landscape) 48.dp else 68.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).border(2.dp, StoryGradient, CircleShape).padding(7.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.CameraAlt, null, tint = Cream, modifier = Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("平成カメラ", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .2.sp)
                            if (!landscape) Text("この街の、もうひとつの景色。", color = Muted, fontSize = 10.sp, letterSpacing = .2.sp)
                        }
                        IconButton(onClick = { pendingLocation = false; dialog = "about" }) { Icon(Icons.Outlined.Info, "使い方と接続情報", tint = Muted) }
                    }
                    if (landscape) {
                        Row(Modifier.weight(1f).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Finder(session, showStreetView, cameraGranted, cameraState, cameraEpoch, { cameraState = it },
                                ::requestCamera, { cameraEpoch++ }, ::openMaps,
                                Modifier.weight(1f).fillMaxHeight())
                            CameraControls(session, showStreetView, cameraGranted && cameraState == CameraPreviewState.STREAMING,
                                ::shutter, ::returnToCamera, { pendingLocation = false; dialog = "source" }, { pendingLocation = false; dialog = "bookmarks" }, { dialog = "era" }, ::openMaps, pendingLocation, compact = true,
                                modifier = Modifier.width(180.dp).fillMaxHeight())
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(if (showStreetView) "STREET VIEW" else "いま / LIVE", if (showStreetView) Accent else Cream)
                            Spacer(Modifier.weight(1f))
                            Text(if (showStreetView && session.isAutomatic) "現在地付近" else if (showStreetView) "選んだ風景" else "カメラをかざして", color = Muted, fontSize = 12.sp)
                        }
                        Finder(session, showStreetView, cameraGranted, cameraState, cameraEpoch, { cameraState = it },
                            ::requestCamera, { cameraEpoch++ }, ::openMaps,
                            Modifier.weight(1f).fillMaxWidth())
                        CameraControls(session, showStreetView, cameraGranted && cameraState == CameraPreviewState.STREAMING,
                            ::shutter, ::returnToCamera, { pendingLocation = false; dialog = "source" }, { pendingLocation = false; dialog = "bookmarks" }, { dialog = "era" }, ::openMaps, pendingLocation, compact = false)
                    }
                }
            }
        }

        if (dialog == "source") ModalBottomSheet(onDismissRequest = { dialog = null }, containerColor = Panel) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("映す風景", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("通常は現在地から自動で表示します。", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
                Button(onClick = { returnToCamera(); session.selectAutomatic(); dialog = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("現在地から自動表示")
                }
                TextButton(onClick = { dialog = "bookmarks" }, modifier = Modifier.fillMaxWidth()) { Text("しおりから選ぶ") }
            }
        }

        if (dialog == "location") AlertDialog(onDismissRequest = { dialog = null },
            title = { Text("現在地を確認してください") },
            text = { Column {
                Text(locationProblem.orEmpty())
                TextButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    catch (_: android.content.ActivityNotFoundException) { session.message = "端末の設定から位置情報をオンにしてください。" }
                }) { Text("位置情報の設定を開く") }
                TextButton(onClick = { openExternal(context, "package:${context.packageName}", appSettings = true) { session.message = it } }) { Text("アプリの権限を開く") }
            } },
            confirmButton = { TextButton(onClick = { dialog = null; requestLocation() }) { Text("もう一度取得する") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } })

        if (dialog == "era") AlertDialog(onDismissRequest = { dialog = null },
            title = { Text("年代を選ぶ") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Google マップでこの場所を開き、「他の日付を見る」から風景を選びます。", color = Muted)
                Text("選んだ風景を「共有」し、共有先に平成カメラを選ぶと、表示が切り替わります。", color = Muted)
                Text("過去の画像がある場所で利用できます。", color = Muted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { dialog = null; openMaps() }) { Text("Google マップで選ぶ ↗") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } })

        if (dialog == "about") ModalBottomSheet(onDismissRequest = { dialog = null }, containerColor = Panel) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("シャッターで、この街を見る。", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Instruction("01", "カメラをかざす", "起動すると背面カメラで今の風景を表示します。写真の保存やカメラ映像の送信は行いません。")
                Instruction("02", "シャッターで現在地を取得", "位置情報を許可すると、GPSやネットワークで現在地を取得します。アプリを閉じている間は測位しません。")
                Instruction("03", "この付近の Street View を見る", "Google が現在地付近から選ぶ画像を表示します。最古・平成時代の画像を指定する機能はありません。カメラの向きとは連動しません。")
                Instruction("04", "表示したあとに年代を選ぶ", "「年代を選ぶ」から Google マップを開き、「他の日付を見る」で風景を選びます。平成カメラへ共有すると、その風景に切り替わります。選んだ風景には、しおりを付けられます。")
                HorizontalDivider(color = Muted.copy(alpha = .2f), modifier = Modifier.padding(vertical = 12.dp))
                Text("接続情報", fontWeight = FontWeight.Bold)
                Text(if (BuildConfig.HAS_EMBED_KEY) "Street View の表示：設定済み" else "Street View の表示：API キー未設定", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Maps Embed API の表示料金は無料です。開発者による API キーの設定が必要です。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { openExternal(context, "https://github.com/hatake716/heisei-camera#ローカル設定") { session.message = it } }) { Text("開発者向けの設定手順") }
                TextButton(onClick = { openExternal(context, "package:${context.packageName}", appSettings = true) { session.message = it } }) { Text("カメラ・位置情報の許可を変更する") }
                TextButton(onClick = { openExternal(context, "https://github.com/hatake716/heisei-camera/blob/main/docs/PRIVACY.md") { session.message = it } }) { Text("プライバシーポリシー") }
                TextButton(onClick = { openExternal(context, "https://maps.google.com/help/terms_maps/") { session.message = it } }) { Text("Google マップの利用規約") }
            }
        }

        if (dialog == "bookmarks") ModalBottomSheet(onDismissRequest = { dialog = null }, containerColor = Panel) {
            Text("選んだ風景のしおり", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            if (session.bookmarks.isEmpty()) Text("Google マップから選んだ風景にしおりを付けると、ここから呼び出せます。", color = Muted, modifier = Modifier.padding(24.dp).padding(bottom = 30.dp))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(bottom = 28.dp)) {
                itemsIndexed(session.bookmarks, key = { _, value -> value.panoId }) { index, value ->
                    Row(Modifier.fillMaxWidth().clickable {
                        returnToCamera(); session.open(value.panoId); dialog = null
                    }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BookmarkBorder, null, tint = Accent)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("風景のしおり ${session.bookmarks.size - index}")
                            Text("登録 " + SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.JAPAN).format(Date(value.createdAt)), color = Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { session.deleteBookmark(value) }) { Icon(Icons.Outlined.DeleteOutline, "このしおりを削除", tint = Muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Finder(
    session: CameraSession, showStreetView: Boolean, cameraGranted: Boolean, cameraState: CameraPreviewState,
    cameraEpoch: Int, onCameraState: (CameraPreviewState) -> Unit, requestCamera: () -> Unit,
    retryCamera: () -> Unit, openMaps: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Panel)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101010))) {
            if (!showStreetView) {
                if (cameraGranted) {
                    key(cameraEpoch) { LiveCameraPreview(onState = onCameraState, modifier = Modifier.fillMaxSize()) }
                    if (cameraState == CameraPreviewState.STARTING) FinderMessage("カメラを準備中", "") { CircularProgressIndicator(Modifier.size(28.dp), color = Accent, strokeWidth = 2.dp) }
                    if (cameraState == CameraPreviewState.FAILED) FinderMessage("カメラを開けませんでした", "ほかのアプリで使用中の場合は閉じてください。") {
                        Button(onClick = retryCamera) { Text("もう一度開く") }
                    }
                    if (cameraState == CameraPreviewState.STREAMING) ViewfinderMarks(Modifier.fillMaxSize())
                } else FinderMessage("いまの風景を映そう", "カメラへのアクセスを許可してください。\n映像はプレビューにだけ使います。") {
                    Button(onClick = requestCamera) { Text("カメラを許可する") }
                    TextButton(onClick = { openExternal(context, "package:${context.packageName}", appSettings = true) { session.message = it } }) { Text("端末の設定を開く") }
                }
            } else if (!BuildConfig.HAS_EMBED_KEY) {
                FinderMessage("Street View の表示を設定", "このビルドは API キー未設定です。\nGoogle マップで風景を開けます。") {
                    Button(onClick = openMaps) { Text("Google マップで開く ↗") }
                    TextButton(onClick = { openExternal(context, "https://github.com/hatake716/heisei-camera#ローカル設定") { session.message = it } }) { Text("設定手順を見る") }
                }
            } else if (maxWidth < 200.dp || maxHeight < 200.dp) {
                FinderMessage("画面を広げてください", "Street View を表示するには、端末の向きやウィンドウの大きさを変更してください。") {}
            } else {
                val target = session.target
                if (target != null) {
                    val epoch = session.viewEpoch
                    if (session.pageState != EmbedPageState.FAILED) key(target, epoch) {
                        EmbedViewer(request = EmbedRequest(target = target, apiKey = BuildConfig.EMBED_API_KEY), frozen = true,
                            onPageState = { session.markPageState(target, epoch, it) }, modifier = Modifier.fillMaxSize())
                    }
                    if (session.pageState == EmbedPageState.FAILED) FinderMessage("Street View を開けませんでした", "通信状態を確認して再度お試しください。") {
                        Button(onClick = { session.retry() }) { Text("読み込み直す") }
                        TextButton(onClick = openMaps) { Text("Google マップで開く ↗") }
                    }
                }
            }
        }
        if (!showStreetView) Row(Modifier.fillMaxWidth().background(Ink).height(58.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("今、この場所", color = Muted, fontSize = 10.sp)
                Text("LIVE CAMERA", color = Cream, fontSize = 12.sp, letterSpacing = 1.sp)
            }
            Text("LIVE", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 2.sp)
        }

    }
}

@Composable
private fun CameraControls(
    session: CameraSession, showStreetView: Boolean, cameraReady: Boolean, shutter: () -> Unit,
    returnToCamera: () -> Unit, selectScene: () -> Unit, bookmarks: () -> Unit,
    chooseEra: () -> Unit, openMaps: () -> Unit, locating: Boolean, compact: Boolean, modifier: Modifier = Modifier,
) {
    val automaticResult = showStreetView && session.isAutomatic
    val selectionAction = if (showStreetView) chooseEra else selectScene
    val selectionLabel = if (showStreetView) "年代を選ぶ" else "風景を選ぶ"
    val sideAction: () -> Unit = when {
        automaticResult -> openMaps
        showStreetView -> { { session.saveBookmark(); Unit } }
        else -> bookmarks
    }
    val sideLabel = when {
        automaticResult -> "マップで見る"
        showStreetView -> "しおりに残す"
        else -> "しおり ${session.bookmarks.size}"
    }
    val ready = cameraReady && !session.importing && !locating
    Column(modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (compact) Arrangement.SpaceEvenly else Arrangement.spacedBy(10.dp)) {
        Text(when {
            locating -> "現在地を取得中…"
            session.importing -> "風景のリンクを確認中…"
            automaticResult -> "この付近の風景を、眺める。"
            showStreetView -> "選んだ風景を、眺める。"
            session.isAutomatic -> "シャッターで現在地の風景を自動表示"
            else -> "選んだ風景をシャッターで表示"
        }, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        if (compact) {
            ShutterButton(showStreetView, ready, if (showStreetView) returnToCamera else shutter)
            TextButton(onClick = selectionAction, enabled = !session.importing) { Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(selectionLabel) }
            TextButton(onClick = sideAction) { Text(sideLabel) }
            if (showStreetView) TextButton(onClick = { session.retry() }) { Text("読み込み直す") }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(82.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !session.importing, onClick = selectionAction).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.History, selectionLabel, tint = Cream, modifier = Modifier.size(24.dp))
                }
                Text(selectionLabel, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            ShutterButton(showStreetView, ready, if (showStreetView) returnToCamera else shutter)
            Column(Modifier.width(82.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = sideAction)
                .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).border(1.5.dp, StoryGradient, CircleShape).padding(2.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    Icon(when {
                        automaticResult -> Icons.Outlined.Map
                        showStreetView -> Icons.Outlined.BookmarkAdd
                        else -> Icons.Outlined.CollectionsBookmark
                    }, when {
                        automaticResult -> "現在地付近をGoogleマップで開く"
                        showStreetView -> "選んだ風景をしおりに残す"
                        else -> "しおり一覧"
                    }, tint = Cream, modifier = Modifier.size(22.dp))
                }
                Text(sideLabel, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (!compact && showStreetView) TextButton(onClick = { session.retry() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(48.dp)) { Text("読み込み直す", fontSize = 12.sp) }
    }
}

@Composable
private fun ShutterButton(showStreetView: Boolean, ready: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val enabled = ready || showStreetView
        val ring = if (showStreetView) Modifier.border(3.dp, StoryGradient, CircleShape)
            else Modifier.border(3.dp, if (enabled) Cream else Muted.copy(alpha = .45f), CircleShape)
        Box(Modifier.size(82.dp).then(ring).padding(7.dp).clip(CircleShape)
            .background(if (enabled) Cream else Color(0xFF383838))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (showStreetView) "現在のカメラに戻る" else "シャッター・ストリートビューを表示" }, contentAlignment = Alignment.Center) {
            if (showStreetView) Icon(Icons.Outlined.CameraAlt, null, tint = Ink, modifier = Modifier.size(28.dp))
        }
        Text(if (showStreetView) "現在に戻る" else "シャッター", color = Cream, fontWeight = FontWeight.Medium,
            fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun FinderMessage(title: String, body: String, actions: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Cream, textAlign = TextAlign.Center)
        if (body.isNotBlank()) Text(body, fontSize = 12.sp, lineHeight = 21.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(18.dp))
        actions()
    }
}

@Composable
private fun ViewfinderMarks(modifier: Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width/2, size.height/2)
        drawLine(Ink.copy(alpha = .45f), center-Offset(8.dp.toPx(), 0f), center+Offset(8.dp.toPx(), 0f), 2.dp.toPx())
        drawLine(Ink.copy(alpha = .45f), center-Offset(0f, 8.dp.toPx()), center+Offset(0f, 8.dp.toPx()), 2.dp.toPx())
        drawLine(Cream.copy(alpha = .7f), center-Offset(8.dp.toPx(), 0f), center+Offset(8.dp.toPx(), 0f), 1.dp.toPx())
        drawLine(Cream.copy(alpha = .7f), center-Offset(0f, 8.dp.toPx()), center+Offset(0f, 8.dp.toPx()), 1.dp.toPx())
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = .7.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Panel).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun Instruction(number: String, title: String, body: String) {
    Row(Modifier.padding(bottom = 18.dp)) {
        Text(number, color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(34.dp).padding(top = 3.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(body, color = Muted, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun mapsUrl(target: StreetViewTarget): String {
    val builder = Uri.parse("https://www.google.com/maps/@").buildUpon().appendQueryParameter("api", "1").appendQueryParameter("map_action", "pano")
    when (target) {
        is StreetViewTarget.Panorama -> builder.appendQueryParameter("pano", target.panoId)
        is StreetViewTarget.Nearby -> builder.appendQueryParameter("viewpoint", "${target.location.latitude},${target.location.longitude}")
    }
    return builder.build().toString()
}

private fun openExternal(context: Context, value: String, appSettings: Boolean = false, onError: (String) -> Unit) {
    try {
        context.startActivity(if (appSettings) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(value)) else Intent(Intent.ACTION_VIEW, Uri.parse(value)))
    } catch (_: android.content.ActivityNotFoundException) {
        onError("リンクを開けるアプリが見つかりません。")
    } catch (_: SecurityException) {
        onError("この端末ではリンクを開けませんでした。")
    }
}
