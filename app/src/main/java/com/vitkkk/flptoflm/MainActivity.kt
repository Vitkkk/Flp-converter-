package com.vitkkk.flptoflm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var convertButton: Button
    private lateinit var progress: ProgressBar
    private var selectedName: String? = null
    private var selectedBytes: ByteArray? = null
    private var pendingOutput: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 72, 48, 48)
        }
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
        val chooseButton = Button(this).apply {
            text = "Selecionar arquivo .FLP"
            setOnClickListener { chooseFlp() }
        }
        convertButton = Button(this).apply {
            text = "Gerar .FLM experimental"
            isEnabled = false
            setOnClickListener { convert() }
        }
        progress = ProgressBar(this).apply { visibility = ProgressBar.GONE }
        status = TextView(this).apply {
            text = "Nenhum projeto selecionado."
            textSize = 15f
            setPadding(0, 36, 0, 24)
        }
        val warning = TextView(this).apply {
            text = "Alpha 0.1: valida o FLP e prepara a arquitetura de canais DirectWave. A escrita binária completa do FLM ainda está em desenvolvimento."
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
        setContentView(root)
    }

    private fun chooseFlp() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip", "*/*"))
        }
        startActivityForResult(intent, REQUEST_OPEN_FLP)
    }

    private fun convert() {
        val bytes = selectedBytes ?: return
        progress.visibility = ProgressBar.VISIBLE
        convertButton.isEnabled = false
        try {
            val project = FlpInspector.inspect(bytes)
            pendingOutput = ExperimentalFlmWriter.write(project)
            val base = selectedName?.substringBeforeLast('.') ?: "converted-project"
            val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "$base.flm")
            }
            startActivityForResult(saveIntent, REQUEST_SAVE_FLM)
        } catch (e: Exception) {
            status.text = "Falha: ${e.message}"
            Toast.makeText(this, e.message ?: "Erro na conversão", Toast.LENGTH_LONG).show()
        } finally {
            progress.visibility = ProgressBar.GONE
            convertButton.isEnabled = selectedBytes != null
        }
    }

    @Deprecated("Deprecated in Android API; retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_OPEN_FLP -> data?.data?.let(::loadFlp)
            REQUEST_SAVE_FLM -> data?.data?.let(::saveFlm)
        }
    }

    private fun loadFlp(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Não foi possível abrir o arquivo.")
            val project = FlpInspector.inspect(bytes)
            selectedBytes = bytes
            selectedName = queryName(uri)
            status.text = "${selectedName ?: "Projeto FLP"}\nFormato ${project.format}, ${project.channels} canal(is) detectado(s), PPQ ${project.ppq}."
            convertButton.isEnabled = true
        } catch (e: Exception) {
            selectedBytes = null
            convertButton.isEnabled = false
            status.text = "Arquivo inválido: ${e.message}"
        }
    }

    private fun saveFlm(uri: Uri) {
        try {
            val output = pendingOutput ?: throw IOException("Nenhum resultado disponível.")
            contentResolver.openOutputStream(uri, "w")?.use { it.write(output) }
                ?: throw IOException("Não foi possível salvar o arquivo.")
            status.text = "Arquivo experimental salvo."
            Toast.makeText(this, "FLM experimental salvo", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            status.text = "Erro ao salvar: ${e.message}"
        } finally {
            pendingOutput = null
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    companion object {
        private const val REQUEST_OPEN_FLP = 1001
        private const val REQUEST_SAVE_FLM = 1002
    }
}
