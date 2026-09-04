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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
private val Amber = Color(0xFFFFB277)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeiseiCameraApp(incomingLink: String?, consumeLink: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val session = rememberSaveable(saver = Saver<CameraSession, Bundle>(
        save = { Bundle().apply { putString("pano", it.selectedPano); putString("pendingImport", it.pendingImportLink) } },
        restore = { state -> CameraSession(context, scope).apply {
            state.getString("pano")?.let(::open)
            message = null
            state.getString("pendingImport")?.let(::importLink)
        } },
    )) { CameraSession(context, scope) }
    var showPast by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var linkText by rememberSaveable { mutableStateOf("") }
    val cameraPreferences = remember { context.getSharedPreferences("camera_preferences", Context.MODE_PRIVATE) }
    var permissionRequested by rememberSaveable { mutableStateOf(cameraPreferences.getBoolean("permission_prompted", false)) }
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var cameraState by remember { mutableStateOf(CameraPreviewState.STARTING) }
    var cameraEpoch by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    fun requestCamera() {
        permissionRequested = true
        cameraPreferences.edit().putBoolean("permission_prompted", true).apply()
        permission.launch(Manifest.permission.CAMERA)
    }
    fun openMaps() = openExternal(context,
        session.selectedPano?.let { mapsUrl(panoId = it) } ?: "https://www.google.com/maps/",
    ) { session.message = it }
    fun returnToCamera() {
        if (showPast) cameraState = CameraPreviewState.STARTING
        showPast = false
        session.resume()
    }
    fun shutter() {
        if (session.selectedPano == null) {
            dialog = "import"
        } else if (session.shutter()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showPast = true
        }
    }
    BackHandler(enabled = showPast && dialog == null) { returnToCamera() }
    LaunchedEffect(Unit) {
        if (!cameraGranted && !permissionRequested) requestCamera()
    }
    LaunchedEffect(incomingLink) {
        incomingLink?.let {
            dialog = null
            returnToCamera()
            session.importLink(it)
            consumeLink()
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
            if (event == Lifecycle.Event.ON_RESUME) cameraGranted = hasCameraPermission(context)
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
                            if (!landscape) Text("いまから、あの頃へ。", color = Muted, fontSize = 10.sp, letterSpacing = .2.sp)
                        }
                        IconButton(onClick = { dialog = "about" }) { Icon(Icons.Outlined.Info, "使い方と接続情報", tint = Muted) }
                    }
                    if (landscape) {
                        Row(Modifier.weight(1f).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Finder(session, showPast, cameraGranted, cameraState, cameraEpoch, { cameraState = it },
                                ::requestCamera, { cameraEpoch++ }, ::openMaps,
                                Modifier.weight(1f).fillMaxHeight())
                            CameraControls(session, showPast, cameraGranted && cameraState == CameraPreviewState.STREAMING,
                                ::shutter, ::returnToCamera, { dialog = "import" }, { dialog = "bookmarks" }, compact = true,
                                modifier = Modifier.width(180.dp).fillMaxHeight())
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(if (showPast) "あの頃 / PAST" else "いま / LIVE", if (showPast) Accent else Cream)
                            Spacer(Modifier.weight(1f))
                            Text(if (showPast) "選んだ風景" else "カメラをかざして", color = Muted, fontSize = 12.sp)
                        }
                        Finder(session, showPast, cameraGranted, cameraState, cameraEpoch, { cameraState = it },
                            ::requestCamera, { cameraEpoch++ }, ::openMaps,
                            Modifier.weight(1f).fillMaxWidth())
                        CameraControls(session, showPast, cameraGranted && cameraState == CameraPreviewState.STREAMING,
                            ::shutter, ::returnToCamera, { dialog = "import" }, { dialog = "bookmarks" }, compact = false)
                    }
                }
            }
        }

        if (dialog == "import") AlertDialog(onDismissRequest = { dialog = null },
            title = { Text("シャッターで映す過去を選ぶ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Google マップで「他の日付を見る」から風景を選び、共有リンクを貼り付けてください。", color = Muted)
                    TextButton(onClick = ::openMaps) { Text("Google マップで過去を探す ↗") }
                    OutlinedTextField(value = linkText, onValueChange = { linkText = it.take(8192) },
                        label = { Text("Google マップの共有リンク") }, modifier = Modifier.fillMaxWidth(),
                        minLines = 2, maxLines = 4, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text("カメラで見ている場所とは自動で連動しません。共有リンクが過去の年月を保持しない場合があります。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }, confirmButton = { TextButton(enabled = linkText.isNotBlank() && !session.importing, onClick = {
                returnToCamera(); session.importLink(linkText); dialog = null
            }) { Text("この風景を選ぶ") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } })

        if (dialog == "about") ModalBottomSheet(onDismissRequest = { dialog = null }, containerColor = Panel) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("シャッターで、あの頃へ。", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Instruction("01", "表示する過去を選ぶ", "Google マップの「他の日付を見る」から風景を選び、共有メニューで平成カメラに送るか、リンクを貼り付けます。前回の選択は記憶されます。")
                Instruction("02", "カメラをかざす", "起動すると今の風景がカメラに映ります。プレビューだけに使用し、写真の撮影・保存や映像の送信は行いません。")
                Instruction("03", "シャッターで過去に切り替える", "丸いボタンで、選んだ Street View を表示します。画像ファイルに変換せず、見回し操作を止めた埋め込み表示です。読み込み中の表示は変わることがあります。")
                Instruction("04", "もう一度、いまを見る", "「現在に戻る」でカメラ表示へ。しおりには選んだ風景のリンクを残せます。カメラの位置・向きと Street View は自動では一致しません。")
                HorizontalDivider(color = Muted.copy(alpha = .2f), modifier = Modifier.padding(vertical = 12.dp))
                Text("接続情報", fontWeight = FontWeight.Bold)
                Text(if (BuildConfig.HAS_EMBED_KEY) "過去の風景の表示：設定済み" else "過去の風景の表示：API キー未設定", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text(session.dateStatus, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Maps Embed API の表示料金は無料です。開発者による API キーの設定が必要です。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { openExternal(context, "https://github.com/hatake716/heisei-camera#ローカル設定") { session.message = it } }) { Text("開発者向けの設定手順") }
                TextButton(onClick = { openExternal(context, "package:${context.packageName}", appSettings = true) { session.message = it } }) { Text("カメラの許可を変更する") }
                TextButton(onClick = { openExternal(context, "https://github.com/hatake716/heisei-camera/blob/main/docs/PRIVACY.md") { session.message = it } }) { Text("プライバシーポリシー") }
                TextButton(onClick = { openExternal(context, "https://maps.google.com/help/terms_maps/") { session.message = it } }) { Text("Google マップの利用規約") }
            }
        }

        if (dialog == "bookmarks") ModalBottomSheet(onDismissRequest = { dialog = null }, containerColor = Panel) {
            Text("選んだ風景のしおり", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            if (session.bookmarks.isEmpty()) Text("過去の画面でしおりを付けると、選んだ風景のリンクをここから呼び出せます。", color = Muted, modifier = Modifier.padding(24.dp).padding(bottom = 30.dp))
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
    session: CameraSession, showPast: Boolean, cameraGranted: Boolean, cameraState: CameraPreviewState,
    cameraEpoch: Int, onCameraState: (CameraPreviewState) -> Unit, requestCamera: () -> Unit,
    retryCamera: () -> Unit, openMaps: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Panel)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101010))) {
            if (!showPast) {
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
                FinderMessage("過去の風景の表示を設定", "このビルドは API キー未設定です。\n選んだリンクは Google マップで開けます。") {
                    Button(onClick = openMaps) { Text("Google マップで開く ↗") }
                    TextButton(onClick = { openExternal(context, "https://github.com/hatake716/heisei-camera#ローカル設定") { session.message = it } }) { Text("設定手順を見る") }
                }
            } else if (maxWidth < 200.dp || maxHeight < 200.dp) {
                FinderMessage("画面を広げてください", "過去の風景を表示するには、端末の向きやウィンドウの大きさを変更してください。") {}
            } else {
                val id = session.selectedPano
                if (id != null) {
                    val epoch = session.viewEpoch
                    if (session.pageState != EmbedPageState.FAILED) key(id, epoch) {
                        EmbedViewer(request = EmbedRequest(panoId = id, apiKey = BuildConfig.EMBED_API_KEY), frozen = true,
                            onPageState = { session.markPageState(id, epoch, it) }, modifier = Modifier.fillMaxSize())
                    }
                    if (session.pageState == EmbedPageState.FAILED) FinderMessage("過去の風景を開けませんでした", "通信状態を確認して再度お試しください。") {
                        Button(onClick = { session.retry() }) { Text("読み込み直す") }
                        TextButton(onClick = openMaps) { Text("Google マップで開く ↗") }
                    }
                }
            }
        }
        // Date and all native controls sit outside the iframe, leaving Google attribution visible.
        Row(Modifier.fillMaxWidth().background(Ink).height(58.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (showPast) "選んだ風景の撮影年月" else "今、この場所", color = Muted, fontSize = 10.sp)
                Text(if (showPast) session.date?.eraLabel ?: "年月不明" else "LIVE CAMERA", color = if (showPast) Amber else Cream, fontSize = 12.sp, letterSpacing = 1.sp)
            }
            if (showPast) FilmDate(session.date?.display ?: "----.--", Modifier.width(118.dp).height(22.dp))
            else Text("LIVE", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun CameraControls(
    session: CameraSession, showPast: Boolean, cameraReady: Boolean, shutter: () -> Unit,
    returnToCamera: () -> Unit, selectScene: () -> Unit, bookmarks: () -> Unit,
    compact: Boolean, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (compact) Arrangement.SpaceEvenly else Arrangement.spacedBy(10.dp)) {
        Text(if (session.importing) "風景のリンクを確認中…" else if (session.selectedPano == null) "まずはシャッターで映す過去を選ぼう" else if (showPast) "選んだ過去を、眺める。" else "過去の風景を選択済み", color = Muted,
            fontSize = 12.sp, textAlign = TextAlign.Center)
        if (compact) {
            ShutterButton(showPast, cameraReady && !session.importing, if (showPast) returnToCamera else shutter)
            TextButton(onClick = selectScene, enabled = !session.importing) { Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("過去を選ぶ") }
            TextButton(onClick = if (showPast) ({ session.saveBookmark(); Unit }) else bookmarks) { Text(if (showPast) "しおりに残す" else "しおり ${session.bookmarks.size}") }
            if (showPast) TextButton(onClick = { session.retry() }) { Text("読み込み直す") }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(82.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !session.importing, onClick = selectScene).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.History, "過去を選ぶ", tint = Cream, modifier = Modifier.size(24.dp))
                }
                Text("過去を選ぶ", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            ShutterButton(showPast, cameraReady && !session.importing, if (showPast) returnToCamera else shutter)
            Column(Modifier.width(82.dp).clip(RoundedCornerShape(12.dp)).clickable {
                if (showPast) session.saveBookmark() else bookmarks()
            }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).border(1.5.dp, StoryGradient, CircleShape).padding(2.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    Icon(if (showPast) Icons.Outlined.BookmarkAdd else Icons.Outlined.CollectionsBookmark,
                        if (showPast) "選んだ風景をしおりに残す" else "しおり一覧", tint = Cream, modifier = Modifier.size(22.dp))
                }
                Text(if (showPast) "しおりに残す" else "しおり ${session.bookmarks.size}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (!compact && showPast) TextButton(onClick = { session.retry() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(48.dp)) { Text("読み込み直す", fontSize = 12.sp) }
    }
}

@Composable
private fun ShutterButton(showPast: Boolean, ready: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val enabled = ready || showPast
        val ring = if (showPast) Modifier.border(3.dp, StoryGradient, CircleShape)
            else Modifier.border(3.dp, if (enabled) Cream else Muted.copy(alpha = .45f), CircleShape)
        Box(Modifier.size(82.dp).then(ring).padding(7.dp).clip(CircleShape)
            .background(if (enabled) Cream else Color(0xFF383838))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (showPast) "現在のカメラに戻る" else "シャッター・過去の風景を表示" }, contentAlignment = Alignment.Center) {
            if (showPast) Icon(Icons.Outlined.CameraAlt, null, tint = Ink, modifier = Modifier.size(28.dp))
        }
        Text(if (showPast) "現在に戻る" else "シャッター", color = Cream, fontWeight = FontWeight.Medium,
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

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

internal fun mapsUrl(panoId: String? = null): String {
    val builder = Uri.parse("https://www.google.com/maps/@").buildUpon().appendQueryParameter("api", "1").appendQueryParameter("map_action", "pano")
    if (panoId != null) builder.appendQueryParameter("pano", panoId)
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
