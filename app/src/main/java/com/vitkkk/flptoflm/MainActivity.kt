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
    private lateinit var audioSwitch: Switch
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
            setPadding(dp(22), dp(30), dp(22), dp(36))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
            addView(root)
        }

        val eyebrow = TextView(this).apply {
            text = "FL STUDIO  →  MOBILE"
            textSize = 11f
            setTextColor(COLOR_ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.16f
        }
        val title = TextView(this).apply {
            text = "FLP  →  FLM"
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        val subtitle = TextView(this).apply {
            text = "Leve notes, slide notes, mídia e opções do projeto para o FL Studio Mobile."
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(8), 0, dp(5))
        }
        val version = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}  •  alpha"
            textSize = 11f
            setTextColor(COLOR_DIM)
        }

        root.addView(eyebrow)
        root.addView(title)
        root.addView(subtitle)
        root.addView(version)

        val defaultCard = card(COLOR_CARD_SOFT).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val defaultHeader = TextView(this).apply {
            text = "PADRÃO"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ACCENT)
            letterSpacing = 0.12f
        }
        val defaultText = TextView(this).apply {
            text = "1 FLM • DirectWave vazio • notes + slide notes • sem FX • sem divisão de BPM"
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(5), 0, 0)
        }
        defaultCard.addView(defaultHeader)
        defaultCard.addView(defaultText)
        addCard(root, defaultCard, dp(20))

        val fileCard = card().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fileCard.addView(sectionTitle("Projeto de origem"))
        fileCard.addView(optionDescription(
            "Aceita .FLP e Zipped Loop Package (.ZIP) com um FLP dentro. O ZIP também pode conter WAV, MP3 e outros samples."
        ))

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
            text = "AGUARDANDO ARQUIVO"
            textSize = 10f
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
            setLineSpacing(0f, 1.13f)
            setPadding(0, dp(12), 0, 0)
        }
        fileCard.addView(status, matchWrap())
        addCard(root, fileCard, dp(14))

        val optionsCard = card().apply {
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        optionsCard.addView(sectionTitle("Extras opcionais"))
        optionsCard.addView(optionDescription(
            "Tudo abaixo começa desligado. Ative somente o que quiser levar para o Mobile."
        ))

        effectsSwitch = optionSwitch(
            title = "Efeitos do FL Studio PC",
            description = "Traduz EQ, reverb, compressor, limiter, delay e outros módulos compatíveis com settings adaptados."
        )
        effectsSwitch.isChecked = false
        effectsSwitch.isEnabled = false
        optionsCard.addView(effectsSwitch)

        bpmSwitch = optionSwitch(
            title = "Separar BPM Change",
            description = "Cria um FLM para cada trecho de BPM; cada parte começa no zero com o tempo correto."
        )
        bpmSwitch.isChecked = false
        bpmSwitch.isEnabled = false
        optionsCard.addView(bpmSwitch)

        audioSwitch = optionSwitch(
            title = "Importar áudios do ZIP",
            description = "Usa os samples encontrados no pacote e recria os Audio Clips como canais de áudio do Mobile."
        )
        audioSwitch.isChecked = false
        audioSwitch.isEnabled = false
        audioSwitch.visibility = View.GONE
        optionsCard.addView(audioSwitch)

        addCard(root, optionsCard, dp(14))

        convertButton = Button(this).apply {
            text = "GERAR FLM"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(COLOR_BUTTON, 14f)
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
            text = "Conversor experimental • o arquivo original nunca é modificado"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(COLOR_DIM)
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(footer, matchWrap())

        effectsSwitch.setOnCheckedChangeListener { _, _ -> updateConvertLabel() }
        bpmSwitch.setOnCheckedChangeListener { _, _ -> updateConvertLabel() }
        audioSwitch.setOnCheckedChangeListener { _, _ -> updateConvertLabel() }

        setContentView(scroll)
    }

    private fun chooseProject() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/octet-stream",
                    "application/zip",
                    "application/x-zip-compressed",
                    "*/*"
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_PROJECT)
    }

    private fun convertToFlm() {
        val project = selectedProject ?: return
        val useEffects = effectsSwitch.isChecked && selectedMixer.compatibleEffects.isNotEmpty()
        val useBpmSplit = bpmSwitch.isChecked && selectedTempoScan.hasChanges
        val useAudio = audioSwitch.isChecked &&
            selectedZipBundle != null &&
            selectedResolvedAudio.isNotEmpty()

        val base = sanitizeName(
            if (selectedZipBundle != null) {
                selectedZipBundle?.flpEntryName
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
            } else {
                selectedName?.substringBeforeLast('.')
            } ?: "projeto-convertido"
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
                        val effectResult = FlmEffectAwareWriter.write(
                            segment.project,
                            partBase,
                            selectedMixer
                        )
                        bytes = effectResult.bytes
                        summary = effectSummary(effectResult)
                    } else {
                        bytes = FlmWriter.write(segment.project, partBase)
                        summary = "Modo padrão • notes e slides • sem FX do PC"
                    }

                    var usedAssets: List<ResolvedZipAudio> = emptyList()
                    if (useAudio) {
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
                        usedAssets = audioResult.usedAssets

                        summary += buildString {
                            if (audioResult.audioChannels > 0) {
                                append("\nÁudio do ZIP: ")
                                    .append(audioResult.audioChannels)
                                    .append(" canal(is), ")
                                    .append(audioResult.audioClips)
                                    .append(" clip(s)")
                            }
                            if (audioResult.missingSamplePaths.isNotEmpty()) {
                                append("\nÁudios sem match no ZIP: ")
                                    .append(audioResult.missingSamplePaths.size)
                            }
                        }
                    }

                    PendingFlmPart(
                        fileName = "$partBase.flm",
                        bytes = bytes,
                        bpm = segment.bpm,
                        startTick = segment.startTick,
                        endTick = segment.endTick,
                        summary = summary,
                        usedAssets = usedAssets
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
                    append("• ")
                        .append(part.fileName)
                        .append(" — ")
                        .append(formatTempo(part.bpm))
                        .append(" BPM\n")
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

                    audioSwitch.isChecked = false
                    audioSwitch.visibility = if (bundle != null) View.VISIBLE else View.GONE
                    audioSwitch.isEnabled = bundle != null && resolved.isNotEmpty()

                    sourceBadge.text = when {
                        bundle != null && resolved.isNotEmpty() -> "ZIP • FLP + MÍDIA DISPONÍVEL"
                        bundle != null -> "ZIP • FLP INTERNO"
                        else -> "ARQUIVO FLP"
                    }

                    status.text = projectSummary(
                        displayName,
                        project,
                        mixer,
                        tempoScan,
                        audioScan,
                        bundle,
                        resolved
                    )
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
        if (bundle != null) {
            append("\nFLP interno: ").append(bundle.flpEntryName)
        }
        append("\n")
            .append(formatTempo(project.tempo))
            .append(" BPM  •  PPQ ")
            .append(project.ppq)

        append("\n")
            .append(project.noteCount)
            .append(" notes  •  ")
            .append(project.slideNoteCount)
            .append(" slides  •  ")
            .append(project.patterns.size)
            .append(" patterns")

        if (mixer.allEffects.isNotEmpty()) {
            append("\nFX PC disponíveis: ")
                .append(mixer.compatibleEffects.size)
                .append(" compatíveis de ")
                .append(mixer.allEffects.size)
        } else {
            append("\nFX PC: nenhum")
        }

        if (tempoScan.hasChanges) {
            append("\nBPM Change disponível: ")
            append(tempoScan.changes.joinToString(" → ") { formatTempo(it.bpm) })
        } else {
            append("\nBPM Change: não detectado")
        }

        if (bundle != null) {
            append("\nMídia no ZIP: ")
                .append(bundle.mediaEntries.size)
                .append(" arquivo(s)  •  ")
                .append(resolved.size)
                .append(" ligado(s) aos Audio Clips")
            if (audioScan.usedChannels.size > resolved.size) {
                append("  •  ")
                    .append(audioScan.usedChannels.size - resolved.size)
                    .append(" sem match")
            }
            append("\nImportação de áudio: desligada por padrão")
        } else if (audioScan.usedChannels.isNotEmpty()) {
            append("\nAudio Clips detectados: ")
                .append(audioScan.usedChannels.size)
                .append(" • o FLP sozinho não contém os arquivos de áudio")
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
            val output = pendingOutput
                ?: throw IOException("Nenhum projeto convertido disponível.")
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

                        val wanted = assets.associateBy {
                            normalizePath(it.sourceEntryName).lowercase(Locale.ROOT)
                        }
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
        if (result.defaultSettings > 0) {
            append(" • padrão ").append(result.defaultSettings)
        }
        if (result.unsupportedEffects.isNotEmpty()) {
            append("\nSem equivalente: ")
                .append(result.unsupportedEffects.joinToString(", "))
        }
    }

    private fun updateConvertLabel() {
        if (selectedProject == null) {
            convertButton.text = "GERAR FLM"
            return
        }

        val split = bpmSwitch.isChecked && selectedTempoScan.hasChanges
        val includeAudio = audioSwitch.isChecked &&
            selectedResolvedAudio.isNotEmpty() &&
            selectedZipBundle != null

        convertButton.text = when {
            includeAudio && split -> "GERAR PACOTE ZIP COM PARTES"
            includeAudio -> "GERAR PACOTE FLM + ÁUDIO"
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

        if (::effectsSwitch.isInitialized) {
            effectsSwitch.isChecked = false
            effectsSwitch.isEnabled = false
        }
        if (::bpmSwitch.isInitialized) {
            bpmSwitch.isChecked = false
            bpmSwitch.isEnabled = false
        }
        if (::audioSwitch.isInitialized) {
            audioSwitch.isChecked = false
            audioSwitch.isEnabled = false
            audioSwitch.visibility = View.GONE
        }
        if (::convertButton.isInitialized) {
            convertButton.isEnabled = false
        }
        if (::sourceBadge.isInitialized) {
            sourceBadge.text = "AGUARDANDO ARQUIVO"
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getString(index)
            }
        }
        return null
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
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
        effectsSwitch.isEnabled = !busy && selectedMixer.compatibleEffects.isNotEmpty()
        bpmSwitch.isEnabled = !busy && selectedTempoScan.hasChanges
        audioSwitch.isEnabled = !busy &&
            selectedZipBundle != null &&
            selectedResolvedAudio.isNotEmpty()
        if (message != null) status.text = message
    }

    private fun showError(prefix: String, throwable: Throwable) {
        val detail = throwable.message ?: throwable::class.java.simpleName
        status.text = "$prefix: $detail"
        Toast.makeText(this, "$prefix: $detail", Toast.LENGTH_LONG).show()
    }

    private fun card(fill: Int = COLOR_CARD): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(fill, 18f, COLOR_STROKE)
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
        setLineSpacing(0f, 1.08f)
        setPadding(0, dp(5), 0, dp(8))
    }

    private fun optionSwitch(title: String, description: String): Switch = Switch(this).apply {
        text = "$title\n$description"
        textSize = 13f
        setTextColor(COLOR_TEXT)
        setLineSpacing(0f, 1.06f)
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun sanitizeName(value: String): String =
        value.replace(Regex("[\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "projeto-convertido" }

    private fun formatTempo(tempo: Double): String =
        if (tempo % 1.0 == 0.0) {
            tempo.toInt().toString()
        } else {
            String.format(Locale.US, "%.3f", tempo)
        }

    companion object {
        private const val REQUEST_OPEN_PROJECT = 1001
        private const val REQUEST_SAVE_FLM = 1002
        private const val REQUEST_SAVE_FLM_FOLDER = 1003
        private const val REQUEST_SAVE_PACKAGE = 1004

        private val COLOR_BG = Color.rgb(8, 12, 19)
        private val COLOR_CARD = Color.rgb(17, 24, 35)
        private val COLOR_CARD_SOFT = Color.rgb(15, 31, 28)
        private val COLOR_STROKE = Color.rgb(38, 53, 68)
        private val COLOR_ACCENT = Color.rgb(74, 236, 153)
        private val COLOR_BUTTON = Color.rgb(43, 77, 65)
        private val COLOR_TEXT = Color.rgb(225, 232, 240)
        private val COLOR_MUTED = Color.rgb(151, 164, 179)
        private val COLOR_DIM = Color.rgb(102, 117, 133)
    }
}
