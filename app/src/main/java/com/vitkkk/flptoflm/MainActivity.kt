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
    private var pendingOutput: ByteArray? = null

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
        val subtitle = TextView(this).apply {
            text = "Conversor experimental de projetos do FL Studio para FL Studio Mobile"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        }
        chooseButton = Button(this).apply {
            text = "Selecionar arquivo .FLP"
            setOnClickListener { chooseFlp() }
        }
        convertButton = Button(this).apply {
            text = "Gerar .FLM experimental"
            isEnabled = false
            setOnClickListener { convert() }
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        status = TextView(this).apply {
            text = "Nenhum projeto selecionado."
            textSize = 15f
            setPadding(0, 36, 0, 24)
        }
        val warning = TextView(this).apply {
            text = "Alpha 0.1.1: leitura segura do cabeçalho FLP. A serialização completa de notas, slide notes e canais DirectWave em um FLM compatível ainda está em desenvolvimento."
            textSize = 13f
            setPadding(0, 36, 0, 0)
        }

        root.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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

    private fun convert() {
        val project = selectedProject ?: return
        setBusy(true, "Preparando arquivo experimental...")
        try {
            pendingOutput = ExperimentalFlmWriter.write(project)
            val base = selectedName?.substringBeforeLast('.') ?: "converted-project"
            val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "$base.flm")
            }
            startActivityForResult(saveIntent, REQUEST_SAVE_FLM)
        } catch (t: Throwable) {
            showError("Falha ao preparar a saída", t)
        } finally {
            setBusy(false)
        }
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
        convertButton.isEnabled = false
        setBusy(true, "Lendo cabeçalho do projeto...")

        Thread {
            try {
                val name = queryName(uri) ?: uri.lastPathSegment ?: "Projeto FLP"
                val size = querySize(uri)
                val project = contentResolver.openInputStream(uri)?.use { stream ->
                    FlpInspector.inspect(stream, size)
                } ?: throw IOException("Não foi possível abrir o arquivo.")

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    selectedName = name
                    selectedProject = project
                    status.text = buildString {
                        append(name)
                        append("\nFormato ").append(project.format)
                        append(", ").append(project.channels).append(" canal(is)")
                        append(", PPQ ").append(project.ppq)
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
                    convertButton.isEnabled = false
                    showError("Não foi possível ler o FLP", t)
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun saveFlm(uri: Uri) {
        try {
            val output = pendingOutput ?: throw IOException("Nenhum resultado disponível.")
            contentResolver.openOutputStream(uri, "w")?.use { it.write(output) }
                ?: throw IOException("Não foi possível salvar o arquivo.")
            status.text = "Arquivo experimental salvo."
            Toast.makeText(this, "FLM experimental salvo", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            showError("Erro ao salvar", t)
        } finally {
            pendingOutput = null
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
