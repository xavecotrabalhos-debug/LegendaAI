package com.legendaai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object OfflineTranscriber {
    fun transcribe(context: Context, uri: Uri, progress: (String) -> Unit): List<Caption> {
        val modelPath = ensureModel(context, progress)
        val model = Model(modelPath.absolutePath)
        val recognizer = Recognizer(model, 16000.0f)
        recognizer.setWords(true)
        val result = mutableListOf<Caption>()
        try {
            decodeAudio(context, uri) { pcm ->
                if (recognizer.acceptWaveForm(pcm, pcm.size)) {
                    result.addAll(parse(recognizer.result))
                    progress("Transcrevendo no aparelho… " + result.size + " trechos.")
                }
            }
            result.addAll(parse(recognizer.finalResult))
        } finally {
            recognizer.close()
            model.close()
        }
        return result
    }

    private fun ensureModel(context: Context, progress: (String) -> Unit): File {
        val modelDir = File(context.filesDir, "vosk-model-small-pt-0.3")
        if (File(modelDir, "conf").exists() && File(modelDir, "am").exists()) return modelDir
        progress("Baixando modelo gratuito de português (aprox. 31 MB)…")
        val zipFile = File(context.cacheDir, "vosk-pt.zip")
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.MINUTES).build()
        val request = Request.Builder().url("https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Falha ao baixar o modelo: HTTP " + response.code)
            val body = response.body ?: error("Download vazio.")
            body.byteStream().use { input -> FileOutputStream(zipFile).use { input.copyTo(it) } }
        }
        progress("Instalando modelo de português…")
        val root = File(context.filesDir, "vosk-unpack")
        root.deleteRecursively()
        root.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                if (!target.path.startsWith(root.canonicalPath + File.separator)) error("Arquivo inválido no modelo.")
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        zipFile.delete()
        val found = root.walkTopDown().firstOrNull {
            it.isDirectory && File(it, "conf").exists() && File(it, "am").exists()
        } ?: error("O pacote baixado não contém um modelo Vosk válido.")
        if (modelDir.exists()) modelDir.deleteRecursively()
        if (!found.renameTo(modelDir)) {
            found.copyRecursively(modelDir, overwrite = true)
            root.deleteRecursively()
        }
        return modelDir
    }

    private fun decodeAudio(context: Context, uri: Uri, onPcm: (ByteArray) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) error("O vídeo não contém faixa de áudio.")
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val output = codec.getOutputBuffer(outputIndex)!!
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            output.get(bytes)
                            val pcm = to16kMono(bytes, channels, sampleRate)
                            if (pcm.isNotEmpty()) onPcm(pcm)
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun to16kMono(data: ByteArray, channels: Int, sampleRate: Int): ByteArray {
        if (channels <= 0 || sampleRate <= 0) return ByteArray(0)
        val frameBytes = channels * 2
        val frames = data.size / frameBytes
        if (frames == 0) return ByteArray(0)
        val step = sampleRate.toDouble() / 16000.0
        val count = (frames / step).toInt().coerceAtLeast(1)
        val output = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        var source = 0.0
        repeat(count) {
            val frame = source.toInt().coerceAtMost(frames - 1)
            var sum = 0
            for (channel in 0 until channels) {
                val offset = frame * frameBytes + channel * 2
                val value = (data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)
                sum += value.toShort().toInt()
            }
            output.putShort((sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            source += step
        }
        return output.array()
    }

    private fun parse(raw: String): List<Caption> {
        val json = JSONObject(raw)
        val words = json.optJSONArray("result") ?: return emptyList()
        val captions = mutableListOf<Caption>()
        val phrase = mutableListOf<String>()
        var start = 0.0
        var end = 0.0
        for (i in 0 until words.length()) {
            val word = words.getJSONObject(i)
            val value = word.optString("word").trim()
            if (value.isBlank()) continue
            if (phrase.isEmpty()) start = word.optDouble("start")
            end = word.optDouble("end")
            phrase.add(value)
            if (phrase.size >= 7 || i == words.length() - 1) {
                captions.add(Caption(start, end, phrase.joinToString(" ")))
                phrase.clear()
            }
        }
        if (phrase.isNotEmpty()) captions.add(Caption(start, end, phrase.joinToString(" ")))
        return captions
    }
}
