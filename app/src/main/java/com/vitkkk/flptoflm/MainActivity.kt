package com.vitkkk.flptoflm

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var chooseButton: Button
    private lateinit var convertButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var effectsSwitch: Switch
    private lateinit var bpmSwitch: Switch
    private lateinit var sourceBadge: TextView

    private data class PendingFlmPart(
        val fileName: String,
        val bytes: ByteArray,
        val bpm: Double,
        val startTick: Long,
        val endTick: Long,
        val summary: String,
        val usedAssets: List<ResolvedZipAudio>
    )

    private var selectedInputUri: Uri? = null
    private var selectedName: String? = null
    private var selectedProject: FlpProject? = null
    private var selectedMixer: FlpMixerScan = FlpMixerScan.EMPTY
    private var selectedTempoScan: FlpTempoScan = FlpTempoScan.EMPTY
    private var selectedAudioScan: FlpAudioScan = FlpAudioScan.EMPTY
    private var selectedZipBundle: ZipFlpBundle? = null
    private var selectedResolvedAudio: List<ResolvedZipAudio> = emptyList()

    private var pendingOutput: ByteArray? = null
    private var pendingParts: List<PendingFlmPart> = emptyList()
    private var pendingFolderName: String = "FLM BPM Change"
    private var pendingSummary: String? = null
    private var pendingPackageAssets: List<ResolvedZipAudio> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(34), dp(22), dp(36))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
            addView(root)
        }

        val eyebrow = TextView(this).apply {
            text = "FL STUDIO → MOBILE"
            textSize = 12f
            setTextColor(COLOR_ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
        }
        val title = TextView(this).apply {
            text = "FLP  →  FLM"
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(5), 0, 0)
        }
        val subtitle = TextView(this).apply {
            text = "Notas, slide notes e canais prontos para continuar o projeto no celular."
            textSize = 15f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(8), 0, dp(5))
        }
        val version = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(COLOR_DIM)
        }

        root.addView(eyebrow)
        root.addView(title)
        root.addView(subtitle)
        root.addView(version)

        val fileCard = card().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fileCard.addView(sectionTitle("Projeto de origem"))
        val fileHint = TextView(this).apply {
            text = "Aceita .FLP e .ZIP com um FLP dentro. ZIPs podem trazer WAV/MP3 junto."
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(5), 0, dp(13))
        }
        fileCard.addView(fileHint)

        chooseButton = Button(this).apply {
            text = "SELECIONAR .FLP OU .ZIP"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(5, 20, 13))
            background = rounded(COLOR_ACCENT, 14f)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener { chooseProject() }
        }
        fileCard.addView(chooseButton, matchWrap())

        sourceBadge = TextView(this).apply {
            text = "MODO BÁSICO"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ACCENT)
            background = rounded(Color.rgb(24, 53, 43), 999f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        val badgeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
        fileCard.addView(sourceBadge, badgeParams)

        status = TextView(this).apply {
            text = "Nenhum projeto selecionado."
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(12), 0, 0)
        }
        fileCard.addView(status, matchWrap())
        addCard(root, fileCard, dp(24))

        val optionsCard = card().apply {
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        optionsCard.addView(sectionTitle("Opções de conversão"))
        optionsCard.addView(optionDescription(
            "Por padrão o app gera um único FLM limpo: DirectWave vazio + notas e slides."
        ))

        effectsSwitch = optionSwitch(
            title = "Efeitos do FL Studio PC",
            description = "Traduz EQ, reverb, compressor, delay e outros efeitos compatíveis com settings adaptados."
        )
        effectsSwitch.isChecked = false
        effectsSwitch.isEnabled = false
        optionsCard.addView(effectsSwitch)

        bpmSwitch = optionSwitch(
            title = "Separar BPM Change",
            description = "Cria uma parte FLM por trecho de BPM para contornar a falta de automação de tempo no Mobile."
        )
        bpmSwitch.isChecked = false
        bpmSwitch.isEnabled = false
        optionsCard.addView(bpmSwitch)

        val audioAuto = TextView(this).apply {
            text = "Áudio em ZIP: automático • samples usados pelo FLP são recriados como canais de áudio."
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        optionsCard.addView(audioAuto)
        addCard(root, optionsCard, dp(14))

        convertButton = Button(this).apply {
            text = "GERAR FLM"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(47, 83, 70), 14f)
            isEnabled = false
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { convertToFlm() }
        }
        val convertParams = matchWrap().apply { topMargin = dp(18) }
        root.addView(convertButton, convertParams)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        val progressParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(18)
        }
        root.addView(progress, progressParams)

        val footer = TextView(this).apply {
            text = "Conversor experimental • o arquivo original nunca é modificado."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(COLOR_DIM)
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(footer, matchWrap())

        effectsSwitch.setOnCheckedChangeListener { _, _ -> updateConvertLabel() }
        bpmSwitch.setOnCheckedChangeListener { _, _ -> updateConvertLabel() }
        setContentView(scroll)
    }

    private fun chooseProject() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed", "*/*")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_PROJECT)
    }

    private fun convertToFlm() {
        val project = selectedProject ?: return
        val useEffects = effectsSwitch.isChecked && selectedMixer.compatibleEffects.isNotEmpty()
        val useBpmSplit = bpmSwitch.isChecked && selectedTempoScan.hasChanges
        val base = sanitizeName(
            selectedName?.substringBeforeLast('.')
                ?: selectedZipBundle?.flpEntryName?.substringAfterLast('/')?.substringBeforeLast('.')
                ?: "projeto-convertido"
        )

        setBusy(true, "Montando o projeto Mobile...")
        Thread {
            try {
                val segments = if (useBpmSplit) {
                    FlpTempoSegmenter.split(project, selectedTempoScan)
                } else {
                    listOf(
                        FlpTempoSegment(
                            index = 1,
                            startTick = 0L,
                            endTick = Long.MAX_VALUE,
                            bpm = project.tempo,
                            project = project
                        )
                    )
                }

                val parts = segments.map { segment ->
                    val partBase = if (segments.size == 1) {
                        base
                    } else {
                        "$base - Parte ${segment.index.toString().padStart(2, '0')} - ${formatTempo(segment.bpm)} BPM"
                    }

                    var bytes: ByteArray
                    var summary: String
                    if (useEffects) {
                        val effectResult = FlmEffectAwareWriter.write(segment.project, partBase, selectedMixer)
                        bytes = effectResult.bytes
                        summary = effectSummary(effectResult)
                    } else {
                        bytes = FlmWriter.write(segment.project, partBase)
                        summary = "Modo básico • efeitos do PC não adicionados"
                    }

                    val partAudioScan = if (useBpmSplit) {
                        sliceAudioScan(selectedAudioScan, segment.startTick, segment.endTick)
                    } else {
                        selectedAudioScan
                    }
                    val audioResult = FlmAudioWriter.write(
                        baseFlm = bytes,
                        project = segment.project,
                        audioScan = partAudioScan,
                        resolvedAssets = selectedResolvedAudio
                    )
                    bytes = audioResult.bytes

                    summary += buildString {
                        if (audioResult.audioChannels > 0) {
                            append("\nÁudio do ZIP: ").append(audioResult.audioChannels)
                                .append(" canal(is), ").append(audioResult.audioClips).append(" clip(s)")
                        }
                        if (audioResult.missingSamplePaths.isNotEmpty()) {
                            append("\nÁudios sem arquivo no ZIP: ")
                                .append(audioResult.missingSamplePaths.size)
                        }
                    }

                    PendingFlmPart(
                        fileName = "$partBase.flm",
                        bytes = bytes,
                        bpm = segment.bpm,
                        startTick = segment.startTick,
                        endTick = segment.endTick,
                        summary = summary,
                        usedAssets = audioResult.usedAssets
                    )
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    prepareSave(base, parts)
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

    private fun prepareSave(base: String, parts: List<PendingFlmPart>) {
        val assets = parts.flatMap { it.usedAssets }.distinctBy { it.sourceEntryName }
        pendingSummary = buildString {
            if (parts.size > 1) {
                append(parts.size).append(" partes de BPM geradas:\n")
                parts.forEach { part ->
                    append("• ").append(part.fileName)
                        .append(" — ").append(formatTempo(part.bpm)).append(" BPM\n")
                }
                append('\n')
            }
            append(parts.firstOrNull()?.summary.orEmpty())
        }

        if (assets.isNotEmpty() && selectedZipBundle != null) {
            pendingParts = parts
            pendingOutput = null
            pendingPackageAssets = assets
            val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "$base - FLM Package.zip")
            }
            startActivityForResult(saveIntent, REQUEST_SAVE_PACKAGE)
            return
        }

        pendingPackageAssets = emptyList()
        if (parts.size == 1) {
            pendingOutput = parts.first().bytes
            pendingParts = emptyList()
            val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, parts.first().fileName)
            }
            startActivityForResult(saveIntent, REQUEST_SAVE_FLM)
        } else {
            pendingOutput = null
            pendingParts = parts
            pendingFolderName = "$base - BPM Change"
            val folderIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
            startActivityForResult(folderIntent, REQUEST_SAVE_FLM_FOLDER)
        }
    }

    @Deprecated("Deprecated in Android API; retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQUEST_OPEN_PROJECT -> data?.data?.let { uri ->
                val grantedFlags = data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (grantedFlags != 0) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, grantedFlags)
                    } catch (_: Exception) {
                    }
                }
                loadProject(uri)
            }
            REQUEST_SAVE_FLM -> data?.data?.let(::saveFlm)
            REQUEST_SAVE_PACKAGE -> data?.data?.let(::saveZipPackage)
            REQUEST_SAVE_FLM_FOLDER -> data?.data?.let { treeUri ->
                val grantedFlags = data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try {
                    contentResolver.takePersistableUriPermission(treeUri, grantedFlags)
                } catch (_: Exception) {
                }
                saveFlmParts(treeUri)
            }
        }
    }

    private fun loadProject(uri: Uri) {
        clearSelection()
        setBusy(true, "Analisando FLP, Mixer, BPM e mídia...")

        Thread {
            try {
                val displayName = queryName(uri) ?: uri.lastPathSegment ?: "Projeto"
                val isZip = displayName.lowercase(Locale.ROOT).endsWith(".zip")
                val size = querySize(uri)

                val bundle: ZipFlpBundle?
                val project: FlpProject
                val mixer: FlpMixerScan
                val tempoScan: FlpTempoScan
                val audioScan: FlpAudioScan

                if (isZip) {
                    bundle = contentResolver.openInputStream(uri)?.use(ZipFlpBundleReader::read)
                        ?: throw IOException("Não foi possível abrir o ZIP.")
                    val flp = bundle.flpBytes
                    project = FlpParser.parse(flp)
                    mixer = try {
                        ByteArrayInputStream(flp).use(FlpMixerScanner::scan)
                    } catch (_: Throwable) {
                        FlpMixerScan.EMPTY
                    }
                    tempoScan = try {
                        FlpTempoAutomationScanner.scan(flp, project.tempo)
                    } catch (_: Throwable) {
                        FlpTempoScan(listOf(FlpTempoChange(0L, project.tempo)), 0, false)
                    }
                    audioScan = try {
                        FlpAudioScanner.scan(flp)
                    } catch (_: Throwable) {
                        FlpAudioScan.EMPTY
                    }
                } else {
                    bundle = null
                    project = contentResolver.openInputStream(uri)?.use { stream ->
                        FlpParser.parse(stream, size)
                    } ?: throw IOException("Não foi possível abrir o FLP.")
                    mixer = try {
                        contentResolver.openInputStream(uri)?.use(FlpMixerScanner::scan)
                            ?: FlpMixerScan.EMPTY
                    } catch (_: Throwable) {
                        FlpMixerScan.EMPTY
                    }
                    tempoScan = try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            FlpTempoAutomationScanner.scan(stream, project.tempo)
                        } ?: FlpTempoScan.EMPTY
                    } catch (_: Throwable) {
                        FlpTempoScan(listOf(FlpTempoChange(0L, project.tempo)), 0, false)
                    }
                    audioScan = try {
                        contentResolver.openInputStream(uri)?.use(FlpAudioScanner::scan)
                            ?: FlpAudioScan.EMPTY
                    } catch (_: Throwable) {
                        FlpAudioScan.EMPTY
                    }
                }

                val resolved = bundle?.let {
                    ZipFlpBundleReader.resolveAudio(audioScan, it.mediaEntries)
                }.orEmpty()

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    selectedInputUri = uri
                    selectedName = displayName
                    selectedProject = project
                    selectedMixer = mixer
                    selectedTempoScan = tempoScan
                    selectedAudioScan = audioScan
                    selectedZipBundle = bundle
                    selectedResolvedAudio = resolved

                    effectsSwitch.isChecked = false
                    effectsSwitch.isEnabled = mixer.compatibleEffects.isNotEmpty()
                    bpmSwitch.isChecked = false
                    bpmSwitch.isEnabled = tempoScan.hasChanges

                    sourceBadge.text = if (bundle != null) "ZIP + MÍDIA" else "FLP"
                    status.text = projectSummary(displayName, project, mixer, tempoScan, audioScan, bundle, resolved)
                    setBusy(false)
                    convertButton.isEnabled = true
                    updateConvertLabel()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    clearSelection()
                    setBusy(false)
                    showError("Não foi possível analisar o projeto", t)
                }
            }
        }.start()
    }

    private fun projectSummary(
        displayName: String,
        project: FlpProject,
        mixer: FlpMixerScan,
        tempoScan: FlpTempoScan,
        audioScan: FlpAudioScan,
        bundle: ZipFlpBundle?,
        resolved: List<ResolvedZipAudio>
    ): String = buildString {
        append(displayName)
        if (bundle != null) append("\nFLP interno: ").append(bundle.flpEntryName)
        append("\n").append(formatTempo(project.tempo)).append(" BPM")
            .append("  •  PPQ ").append(project.ppq)
        append("\n").append(project.noteCount).append(" notas")
            .append("  •  ").append(project.slideNoteCount).append(" slides")
        append("  •  ").append(project.patterns.size).append(" patterns")

        if (mixer.allEffects.isNotEmpty()) {
            append("\nFX PC: ").append(mixer.compatibleEffects.size)
                .append(" compatíveis de ").append(mixer.allEffects.size)
        } else {
            append("\nFX PC: nenhum")
        }

        if (tempoScan.hasChanges) {
            append("\nBPM Change: ")
            append(tempoScan.changes.joinToString(" → ") { formatTempo(it.bpm) })
        } else {
            append("\nBPM Change: não detectado")
        }

        if (bundle != null) {
            append("\nZIP: ").append(bundle.mediaEntries.size).append(" áudio(s) encontrado(s)")
            append("  •  ").append(resolved.size).append(" ligado(s) ao FLP")
            if (audioScan.usedChannels.size > resolved.size) {
                append("  •  ").append(audioScan.usedChannels.size - resolved.size).append(" sem match")
            }
        } else if (audioScan.usedChannels.isNotEmpty()) {
            append("\nAudio Clips: ").append(audioScan.usedChannels.size)
                .append(" detectado(s), mas o .FLP sozinho não inclui os arquivos de áudio")
        }
    }

    private fun sliceAudioScan(scan: FlpAudioScan, start: Long, end: Long): FlpAudioScan {
        if (scan.placements.isEmpty() || end == Long.MAX_VALUE) return scan
        val sliced = mutableListOf<FlpAudioPlacement>()
        for (placement in scan.activePlacements) {
            val sourceEnd = placement.position + placement.length
            val clippedStart = max(placement.position, start)
            val clippedEnd = min(sourceEnd, end)
            if (clippedEnd <= clippedStart) continue
            val consumed = (clippedStart - placement.position).coerceAtLeast(0L)
            sliced += placement.copy(
                position = clippedStart - start,
                length = clippedEnd - clippedStart,
                startOffsetTicks = placement.startOffsetTicks + consumed
            )
        }
        return FlpAudioScan(scan.channels, sliced)
    }

    private fun saveFlm(uri: Uri) {
        try {
            val output = pendingOutput ?: throw IOException("Nenhum projeto convertido disponível.")
            contentResolver.openOutputStream(uri, "w")?.use { it.write(output) }
                ?: throw IOException("Não foi possível salvar o arquivo.")
            status.text = "Projeto FLM salvo.\n${pendingSummary.orEmpty()}"
            Toast.makeText(this, "FLM salvo", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            showError("Erro ao salvar", t)
        } finally {
            pendingOutput = null
            pendingSummary = null
        }
    }

    private fun saveZipPackage(uri: Uri) {
        val sourceUri = selectedInputUri ?: return
        val parts = pendingParts
        val assets = pendingPackageAssets
        if (parts.isEmpty()) return
        setBusy(true, "Empacotando FLM e My Samples...")

        Thread {
            try {
                contentResolver.openOutputStream(uri, "w")?.use { rawOut ->
                    ZipOutputStream(rawOut.buffered()).use { zipOut ->
                        for (part in parts) {
                            zipOut.putNextEntry(ZipEntry(part.fileName))
                            zipOut.write(part.bytes)
                            zipOut.closeEntry()
                        }

                        val wanted = assets.associateBy { normalizePath(it.sourceEntryName).lowercase(Locale.ROOT) }
                        val written = hashSetOf<String>()
                        contentResolver.openInputStream(sourceUri)?.use { source ->
                            ZipInputStream(source.buffered()).use { zipIn ->
                                while (true) {
                                    val entry = zipIn.nextEntry ?: break
                                    if (!entry.isDirectory) {
                                        val key = normalizePath(entry.name).lowercase(Locale.ROOT)
                                        val asset = wanted[key]
                                        if (asset != null) {
                                            val outputPath = normalizePath(asset.outputRelativePath)
                                            if (written.add(outputPath.lowercase(Locale.ROOT))) {
                                                zipOut.putNextEntry(ZipEntry(outputPath))
                                                zipIn.copyTo(zipOut, 64 * 1024)
                                                zipOut.closeEntry()
                                            }
                                        }
                                    }
                                    zipIn.closeEntry()
                                }
                            }
                        } ?: throw IOException("Não foi possível reabrir o ZIP de origem.")
                    }
                } ?: throw IOException("Não foi possível criar o ZIP de saída.")

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    status.text = "Pacote ZIP salvo com ${parts.size} FLM(s) e ${assets.size} mídia(s).\n${pendingSummary.orEmpty()}"
                    Toast.makeText(this, "Pacote FLM salvo", Toast.LENGTH_LONG).show()
                    pendingParts = emptyList()
                    pendingPackageAssets = emptyList()
                    pendingSummary = null
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    showError("Erro ao salvar o pacote", t)
                }
            }
        }.start()
    }

    private fun saveFlmParts(treeUri: Uri) {
        val parts = pendingParts
        if (parts.isEmpty()) return
        setBusy(true, "Criando pasta e salvando ${parts.size} partes...")

        Thread {
            try {
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                val rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
                val folder = DocumentsContract.createDocument(
                    contentResolver,
                    rootDocument,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    pendingFolderName
                ) ?: rootDocument

                for (part in parts) {
                    val document = DocumentsContract.createDocument(
                        contentResolver,
                        folder,
                        "application/octet-stream",
                        part.fileName
                    ) ?: throw IOException("Não foi possível criar ${part.fileName}.")
                    contentResolver.openOutputStream(document, "w")?.use { stream ->
                        stream.write(part.bytes)
                    } ?: throw IOException("Não foi possível salvar ${part.fileName}.")
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    status.text = "${parts.size} projetos FLM salvos em $pendingFolderName.\n${pendingSummary.orEmpty()}"
                    Toast.makeText(this, "${parts.size} partes FLM salvas", Toast.LENGTH_LONG).show()
                    pendingParts = emptyList()
                    pendingSummary = null
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    showError("Erro ao salvar as partes", t)
                }
            }
        }.start()
    }

    private fun effectSummary(result: FlmEffectWriteResult): String = buildString {
        append("FX Mobile: ").append(result.addedEffects)
        append(" • settings diretos ").append(result.directSettings)
        append(" • adaptados ").append(result.adaptedSettings)
        if (result.defaultSettings > 0) append(" • padrão ").append(result.defaultSettings)
        if (result.unsupportedEffects.isNotEmpty()) {
            append("\nSem equivalente: ").append(result.unsupportedEffects.joinToString(", "))
        }
    }

    private fun updateConvertLabel() {
        val project = selectedProject
        if (project == null) {
            convertButton.text = "GERAR FLM"
            return
        }
        val split = bpmSwitch.isChecked && selectedTempoScan.hasChanges
        val hasAudioPackage = selectedResolvedAudio.isNotEmpty() && selectedZipBundle != null
        convertButton.text = when {
            hasAudioPackage && split -> "GERAR PACOTE ZIP COM PARTES"
            hasAudioPackage -> "GERAR PACOTE ZIP + ÁUDIO"
            split -> "GERAR PARTES FLM"
            else -> "GERAR FLM"
        }
    }

    private fun clearSelection() {
        selectedInputUri = null
        selectedName = null
        selectedProject = null
        selectedMixer = FlpMixerScan.EMPTY
        selectedTempoScan = FlpTempoScan.EMPTY
        selectedAudioScan = FlpAudioScan.EMPTY
        selectedZipBundle = null
        selectedResolvedAudio = emptyList()
        pendingOutput = null
        pendingParts = emptyList()
        pendingPackageAssets = emptyList()
        effectsSwitch.isChecked = false
        effectsSwitch.isEnabled = false
        bpmSwitch.isChecked = false
        bpmSwitch.isEnabled = false
        convertButton.isEnabled = false
        sourceBadge.text = "MODO BÁSICO"
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) return cursor.getString(index)
        }
        return null
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) return cursor.getLong(index)
        }
        return -1L
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        chooseButton.isEnabled = !busy
        convertButton.isEnabled = !busy && selectedProject != null
        effectsSwitch.isEnabled = !busy && selectedMixer.compatibleEffects.isNotEmpty()
        bpmSwitch.isEnabled = !busy && selectedTempoScan.hasChanges
        if (message != null) status.text = message
    }

    private fun showError(prefix: String, throwable: Throwable) {
        val detail = throwable.message ?: throwable::class.java.simpleName
        status.text = "$prefix: $detail"
        Toast.makeText(this, "$prefix: $detail", Toast.LENGTH_LONG).show()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_CARD, 18f, COLOR_STROKE)
    }

    private fun addCard(root: LinearLayout, view: View, top: Int) {
        val params = matchWrap().apply { topMargin = top }
        root.addView(view, params)
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
    }

    private fun optionDescription(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(COLOR_MUTED)
        setPadding(0, dp(5), 0, dp(8))
    }

    private fun optionSwitch(title: String, description: String): Switch = Switch(this).apply {
        text = "$title\n$description"
        textSize = 13f
        setTextColor(COLOR_TEXT)
        setPadding(dp(4), dp(10), dp(2), dp(10))
        showText = false
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun sanitizeName(value: String): String =
        value.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "projeto-convertido" }

    private fun formatTempo(tempo: Double): String =
        if (tempo % 1.0 == 0.0) tempo.toInt().toString() else String.format(Locale.US, "%.3f", tempo)

    companion object {
        private const val REQUEST_OPEN_PROJECT = 1001
        private const val REQUEST_SAVE_FLM = 1002
        private const val REQUEST_SAVE_FLM_FOLDER = 1003
        private const val REQUEST_SAVE_PACKAGE = 1004

        private val COLOR_BG = Color.rgb(10, 14, 22)
        private val COLOR_CARD = Color.rgb(18, 25, 36)
        private val COLOR_STROKE = Color.rgb(40, 54, 68)
        private val COLOR_ACCENT = Color.rgb(74, 236, 153)
        private val COLOR_TEXT = Color.rgb(224, 231, 239)
        private val COLOR_MUTED = Color.rgb(153, 165, 179)
        private val COLOR_DIM = Color.rgb(105, 119, 135)
    }
}
