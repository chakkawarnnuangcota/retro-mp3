package com.retro.player

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

// ---------- Retro palette ----------
private val Bg = Color(0xFF2B1D3A)
private val Beige = Color(0xFFD9C9A3)
private val Amber = Color(0xFFFFB000)
private val LcdBg = Color(0xFF9BBC0F)
private val LcdFg = Color(0xFF0F380F)
private val Ink = Color(0xFF1A1A1A)
private val Mono = FontFamily.Monospace

data class Track(val uri: Uri, val title: String, val artist: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RetroApp() }
    }
}

private fun loadTracks(ctx: Context): List<Track> {
    val out = mutableListOf<Track>()
    val proj = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST
    )
    val sel = "${MediaStore.Audio.Media.MIME_TYPE} = ?"
    ctx.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel,
        arrayOf("audio/mpeg"), "${MediaStore.Audio.Media.TITLE} ASC"
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            out += Track(
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                c.getString(1) ?: "UNKNOWN",
                c.getString(2) ?: "UNKNOWN"
            )
        }
    }
    return out
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun RetroApp() {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(perm)
        else if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    LaunchedEffect(granted) {
        if (granted) tracks = withContext(Dispatchers.IO) { loadTracks(ctx) }
    }

    // Connect to the background PlaybackService
    var player by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({ player = f.get() }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }

    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val pl = player
        if (pl != null) {
            playing = pl.isPlaying
            index = pl.currentMediaItemIndex
        }
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(m: MediaItem?, reason: Int) {
                index = player?.currentMediaItemIndex ?: 0
            }
        }
        pl?.addListener(l)
        onDispose { pl?.removeListener(l) }
    }
    // Load playlist only if service is empty (so reopening the app doesn't restart music)
    LaunchedEffect(tracks, player) {
        val pl = player ?: return@LaunchedEffect
        if (tracks.isNotEmpty() && pl.mediaItemCount == 0) {
            pl.setMediaItems(tracks.map {
                MediaItem.Builder().setUri(it.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build())
                    .build()
            })
            pl.prepare()
        }
        index = pl.currentMediaItemIndex
    }
    LaunchedEffect(player) {
        while (true) {
            player?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0) }
            delay(250)
        }
    }

    val cur = tracks.getOrNull(index)

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)
    ) {
        Text(
            "★ RETRO PLAYER ★", color = Amber, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 22.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(12.dp))

        // Cassette body
        Column(
            Modifier.fillMaxWidth().background(Beige).border(4.dp, Ink).padding(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().background(Ink).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Reel(playing, Modifier.size(72.dp))
                Reel(playing, Modifier.size(72.dp))
            }
            Spacer(Modifier.height(10.dp))
            // LCD
            Column(Modifier.fillMaxWidth().background(LcdBg).border(3.dp, LcdFg).padding(10.dp)) {
                Text(
                    "${if (playing) "▶" else "■"} TRK ${if (tracks.isEmpty()) "--" else "%02d".format(index + 1)}",
                    color = LcdFg, fontFamily = Mono, fontSize = 14.sp
                )
                Text(
                    (cur?.title ?: "NO MP3 FOUND").uppercase(), color = LcdFg, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1
                )
                Text((cur?.artist ?: "").uppercase(), color = LcdFg, fontFamily = Mono, fontSize = 13.sp, maxLines = 1)
                Text("${fmt(pos)} / ${fmt(dur)}", color = LcdFg, fontFamily = Mono, fontSize = 16.sp)
            }
        }

        Slider(
            value = if (dur > 0) pos.toFloat() / dur else 0f,
            onValueChange = { if (dur > 0) { pos = (it * dur).toLong(); player?.seekTo(pos) } },
            colors = SliderDefaults.colors(
                thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Color(0xFF55406B)
            )
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RetroButton("|◀") { player?.seekToPreviousMediaItem() }
            RetroButton(if (playing) "II" else "▶") { player?.let { if (playing) it.pause() else it.play() } }
            RetroButton("▶|") { player?.seekToNextMediaItem() }
        }

        Spacer(Modifier.height(12.dp))
        Text("— PLAYLIST —", color = Amber, fontFamily = Mono, fontSize = 14.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(tracks) { i, t ->
                val sel = i == index
                Text(
                    "${if (sel) ">" else " "} %02d ${t.title}".format(i + 1).uppercase(),
                    color = if (sel) Bg else Amber,
                    fontFamily = Mono, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (sel) Amber else Color.Transparent)
                        .clickable { player?.seekTo(i, 0); player?.play() }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
fun RetroButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 84.dp, height = 60.dp)) {
        Box(Modifier.offset(4.dp, 4.dp).fillMaxSize().background(Ink))
        Box(
            Modifier.fillMaxSize().background(Beige).border(3.dp, Ink).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(label, color = Ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
    }
}

@Composable
fun Reel(spinning: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "reel")
    val a by t.animateFloat(
        0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "a"
    )
    val angle = if (spinning) a else 0f
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Color(0xFF333333), r)
        drawCircle(Color(0xFFEEEEEE), r * 0.6f)
        for (i in 0 until 6) {
            val rad = Math.toRadians((angle + i * 60).toDouble())
            drawLine(
                Color(0xFF333333), c,
                Offset(c.x + (r * 0.6f * cos(rad)).toFloat(), c.y + (r * 0.6f * sin(rad)).toFloat()), 6f
            )
        }
        drawCircle(Color(0xFF333333), r * 0.22f)
    }
}
