package com.legendaai

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Caption(val start: Double, val end: Double, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LegendaApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegendaApp(context: Context) {
    var apiKey by remember { mutableStateOf("") }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoName by remember { mutableStateOf("Nenhum vídeo selecionado") }
    var captions by remember { mutableStateOf<List<Caption>>(emptyList()) }
    var status by remember { mutableStateOf("Selecione um vídeo para começar.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            videoUri = uri
            videoName = uri.lastPathSegment?.substringAfterLast('/') ?: "Vídeo selecionado"
            captions = emptyList()
            status = "Vídeo carregado. Informe sua chave da API e toque em Transcrever."
        }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            val srt = captionsToSrt(captions)
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(srt) }
                    ?: error("Não foi possível gravar o arquivo.")
            }.onSuccess { status = "Legenda SRT salva com sucesso." }
                .onFailure { status = "Erro ao salvar: ${it.message}" }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB8F36B),
        background = Color(0xFF101410),
        surface = Color(0xFF1B231B),
        onBackground = Color(0xFFF2F5EF),
        onSurface = Color(0xFFF2F5EF)
    )) {
        Scaffold(containerColor = Color(0xFF101410)) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("LegendaAI", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color(0xFFB8F36B))
                Text("Transforme a fala dos seus vídeos em legendas.", color = Color(0xFFB9C3B5))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B231B))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. Escolha seu vídeo", fontWeight = FontWeight.Bold)
                        Text(videoName, color = Color(0xFFB9C3B5), fontSize = 13.sp)
                        Button(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Selecionar vídeo")
                        }
                        HorizontalDivider(color = Color(0xFF354135))
                        Text("2. Conecte a transcrição", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Chave da API OpenAI") },
                            placeholder = { Text("sk-...") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFB8F36B),
                                focusedLabelColor = Color(0xFFB8F36B)
                            )
                        )
                        Text("A chave é usada somente nesta solicitação e não é enviada ao repositório.", fontSize = 11.sp, color = Color(0xFF9EAA99))
                        Button(
                            onClick = {
                                val uri = videoUri
                                if (uri == null) status = "Selecione um vídeo primeiro."
                                else if (!apiKey.startsWith("sk-")) status = "Informe uma chave válida da API OpenAI."
                                else {
                                    busy = true
                                    status = "Extraindo áudio e transcrevendo…"
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                transcribeVideo(context, uri, apiKey.trim())
                                            }
                                            captions = result
                                            status = if (result.isEmpty()) "Nenhuma fala foi identificada." else "Pronto! ${result.size} trechos de legenda gerados."
                                        } catch (e: Exception) {
                                            status = "Não foi possível transcrever: ${e.message ?: "erro desconhecido"}"
                                        } finally { busy = false }
                                    }
                                }
                            },
                            enabled = !busy && videoUri != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Transcrever vídeo")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Legendas geradas", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (captions.isNotEmpty()) {
                        TextButton(onClick = { savePicker.launch("legenda.srt") }) { Text("Exportar SRT") }
                    }
                }
                if (captions.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Sua prévia de legendas aparecerá aqui.", color = Color(0xFF778274))
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(captions) { item ->
                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFF1B231B), RoundedCornerShape(12.dp)).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(formatTime(item.start), color = Color(0xFFB8F36B), fontSize = 12.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(item.text, color = Color.White, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                Text(status, color = Color(0xFFB8F36B), fontSize = 12.sp)
                Text("A transcrição usa a API OpenAI e pode gerar custos na sua conta. O app exporta SRT; a aplicação visual da legenda no MP4 ainda não está incluída nesta versão.", color = Color(0xFF8E9A88), fontSize = 10.sp)
            }
        }
    }
}

private fun transcribeVideo(context: Context, uri: Uri, apiKey: String): List<Caption> {
    val audio = extractAudio(context, uri)
    try {
        if (audio.length() > 24L * 1024 * 1024) error("O áudio extraído ultrapassa 24 MB. Use um vídeo mais curto.")
        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.m4a", audio.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "pt")
            .addFormDataPart("response_format", "verbose_json")
            .build()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                error(message?.takeIf { it.isNotBlank() } ?: "API respondeu HTTP ${response.code}")
            }
            val json = JSONObject(raw)
            val segments = json.optJSONArray("segments") ?: error("A API não retornou os trechos da fala.")
            return (0 until segments.length()).mapNotNull { i ->
                val s = segments.getJSONObject(i)
                val text = s.optString("text").trim()
                if (text.isBlank()) null else Caption(s.optDouble("start"), s.optDouble("end"), text)
            }
        }
    } finally { audio.delete() }
}

private fun extractAudio(context: Context, uri: Uri): File {
    val extractor = MediaExtractor()
    val output = File(context.cacheDir, "legendaai_audio_${System.currentTimeMillis()}.m4a")
    var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(context, uri, null)
        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i
                format = f
                break
            }
        }
        if (audioTrack < 0 || format == null) error("Este vídeo não contém uma faixa de áudio.")
        extractor.selectTrack(audioTrack)
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(format)
        muxer.start()
        val bufferSize = maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(65536), 1024 * 1024)
        val buffer = java.nio.ByteBuffer.allocateDirect(bufferSize)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
        muxer.stop()
        return output
    } catch (e: Exception) {
        output.delete()
        throw e
    } finally {
        runCatching { muxer?.release() }
        extractor.release()
    }
}

private fun captionsToSrt(items: List<Caption>): String = items.mapIndexed { index, c ->
    "${index + 1}\n${srtTime(c.start)} --> ${srtTime(c.end)}\n${c.text}\n"
}.joinToString("\n")

private fun srtTime(seconds: Double): String {
    val ms = (seconds * 1000).toLong().coerceAtLeast(0)
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    val s = (ms % 60_000) / 1000
    val milli = ms % 1000
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}
