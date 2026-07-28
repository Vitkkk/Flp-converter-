package com.vitkkk.flptoflm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var chooseButton: Button
    private lateinit var convertButton: Button
    private lateinit var progress: ProgressBar

    private var selectedName: String? = null
    private var selectedProject: FlpProject? = null
    private var selectedMixer: FlpMixerScan = FlpMixerScan.EMPTY
    private var pendingOutput: ByteArray? = null
    private var pendingSummary: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 72, 48, 48)
        }
        val scroll = ScrollView(this).apply { addView(root) }

        val title = TextView(this).apply {
            text = "FLP → FLM"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val version = TextView(this).apply {
            text = "Versão ${BuildConfig.VERSION_NAME}"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 0)
        }
        val subtitle = TextView(this).apply {
            text = "Converte melodias, slide notes e efeitos para o FL Studio Mobile"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        }
        chooseButton = Button(this).apply {
            text = "Selecionar arquivo .FLP"
            setOnClickListener { chooseFlp() }
        }
        convertButton = Button(this).apply {
            text = "Gerar projeto .FLM"
            isEnabled = false
            setOnClickListener { convertToFlm() }
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        status = TextView(this).apply {
            text = "Nenhum projeto selecionado."
            textSize = 15f
            setPadding(0, 36, 0, 24)
        }
        val warning = TextView(this).apply {
            text = "Cada canal do FLP vira um DirectWave vazio. Efeitos nativos compatíveis são adicionados na ordem dos slots, minimizados e recebem os parâmetros do FLP. Quando os módulos possuem controles diferentes, a configuração é adaptada para manter o resultado sonoro mais próximo."
            textSize = 13f
            setPadding(0, 36, 0, 0)
        }

        root.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(version, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(subtitle, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(chooseButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(convertButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(progress)
        root.addView(warning, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(scroll)
    }

    private fun chooseFlp() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip", "*/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_FLP)
    }

    private fun convertToFlm() {
        val project = selectedProject ?: return
        val mixer = selectedMixer
        setBusy(true, "Criando DirectWave e traduzindo notas, slides e settings dos efeitos...")

        Thread {
            try {
                val base = selectedName?.substringBeforeLast('.') ?: "projeto-convertido"
                val result = FlmEffectAwareWriter.write(project, base, mixer)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pendingOutput = result.bytes
                    pendingSummary = buildString {
                        append("Efeitos adicionados: ").append(result.addedEffects)
                        append("\nSettings diretos: ").append(result.directSettings)
                        append(" • adaptados: ").append(result.adaptedSettings)
                        if (result.defaultSettings > 0) {
                            append(" • padrão: ").append(result.defaultSettings)
                        }
                        if (result.disabledEffectsSkipped > 0) {
                            append("\nDesligados ignorados: ").append(result.disabledEffectsSkipped)
                        }
                        if (result.unsupportedEffects.isNotEmpty()) {
                            append("\nSem equivalente: ")
                            append(result.unsupportedEffects.joinToString(", "))
                        }
                        if (result.settingsNotes.isNotEmpty()) {
                            append("\n\nTraduções:\n")
                            append(result.settingsNotes.joinToString("\n") { "• $it" })
                        }
                    }
                    setBusy(false)
                    val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, "$base.flm")
                    }
                    startActivityForResult(saveIntent, REQUEST_SAVE_FLM)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    showError("Falha na conversão", t)
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Android API; retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQUEST_OPEN_FLP -> data?.data?.let { uri ->
                val grantedFlags = data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (grantedFlags != 0) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, grantedFlags)
                    } catch (_: Exception) {
                        // Some document providers grant temporary access only.
                    }
                }
                loadFlp(uri)
            }
            REQUEST_SAVE_FLM -> data?.data?.let(::saveFlm)
        }
    }

    private fun loadFlp(uri: Uri) {
        selectedProject = null
        selectedMixer = FlpMixerScan.EMPTY
        convertButton.isEnabled = false
        setBusy(true, "Lendo patterns, piano rolls, Mixer, efeitos e presets...")

        Thread {
            try {
                val name = queryName(uri) ?: uri.lastPathSegment ?: "Projeto FLP"
                val size = querySize(uri)
                val project = contentResolver.openInputStream(uri)?.use { stream ->
                    FlpParser.parse(stream, size)
                } ?: throw IOException("Não foi possível abrir o arquivo.")

                val mixer = try {
                    contentResolver.openInputStream(uri)?.use(FlpMixerScanner::scan)
                        ?: FlpMixerScan.EMPTY
                } catch (_: Throwable) {
                    // Notes remain convertible even when a new FLP version changes Mixer events.
                    FlpMixerScan.EMPTY
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    selectedName = name
                    selectedProject = project
                    selectedMixer = mixer
                    status.text = buildString {
                        append(name)
                        append("\nFL Studio: ").append(project.flVersion ?: "versão não identificada")
                        append("\nTempo: ").append(formatTempo(project.tempo)).append(" BPM")
                        append(" • PPQ ").append(project.ppq)
                        append("\nCanais DirectWave: ").append(project.outputChannelCount)
                        append("\nPatterns: ").append(project.patterns.size)
                        append(" • clips na playlist: ").append(project.playlist.size)
                        append("\nNotas: ").append(project.noteCount)
                        append(" • slide notes: ").append(project.slideNoteCount)
                        append("\nEfeitos encontrados: ").append(mixer.allEffects.size)
                        append(" • compatíveis: ").append(mixer.compatibleEffects.size)
                        val states = mixer.compatibleEffects.count { !it.pluginData.isNullOrEmpty() }
                        append(" • com settings: ").append(states)
                        if (mixer.unsupportedEffects.isNotEmpty()) {
                            append("\nSem equivalente: ")
                            append(
                                mixer.unsupportedEffects
                                    .mapNotNull { it.bestName }
                                    .distinct()
                                    .joinToString(", ")
                            )
                        }
                        if (project.sourceSize >= 0L) {
                            append("\nTamanho: ").append(formatSize(project.sourceSize))
                        }
                    }
                    setBusy(false)
                    convertButton.isEnabled = true
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    selectedName = null
                    selectedProject = null
                    selectedMixer = FlpMixerScan.EMPTY
                    convertButton.isEnabled = false
                    showError("Não foi possível analisar o FLP", t)
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun saveFlm(uri: Uri) {
        try {
            val output = pendingOutput ?: throw IOException("Nenhum projeto convertido disponível.")
            contentResolver.openOutputStream(uri, "w")?.use { it.write(output) }
                ?: throw IOException("Não foi possível salvar o arquivo.")
            status.text = buildString {
                append("Projeto FLM salvo. Importe ou abra no FL Studio Mobile.")
                pendingSummary?.let { append("\n").append(it) }
            }
            Toast.makeText(this, "FLM salvo", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            showError("Erro ao salvar", t)
        } finally {
            pendingOutput = null
            pendingSummary = null
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getString(index)
            }
        }
        return null
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        return -1L
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        chooseButton.isEnabled = !busy
        convertButton.isEnabled = !busy && selectedProject != null
        if (message != null) status.text = message
    }

    private fun showError(prefix: String, throwable: Throwable) {
        val detail = throwable.message ?: throwable::class.java.simpleName
        status.text = "$prefix: $detail"
        Toast.makeText(this, "$prefix: $detail", Toast.LENGTH_LONG).show()
    }

    private fun formatTempo(tempo: Double): String =
        if (tempo % 1.0 == 0.0) tempo.toInt().toString() else String.format("%.3f", tempo)

    private fun formatSize(bytes: Long): String {
        if (bytes < 1_024L) return "$bytes B"
        val kb = bytes / 1_024.0
        if (kb < 1_024.0) return String.format("%.1f KB", kb)
        val mb = kb / 1_024.0
        return String.format("%.1f MB", mb)
    }

    companion object {
        private const val REQUEST_OPEN_FLP = 1001
        private const val REQUEST_SAVE_FLM = 1002
    }
}
