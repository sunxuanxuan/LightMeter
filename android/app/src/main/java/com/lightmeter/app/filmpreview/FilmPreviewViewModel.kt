package com.lightmeter.app.filmpreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lightmeter.app.metering.ExposureSnapshot
import com.lightmeter.app.metering.MeteringResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FilmPreviewUiState(
    val presets: List<DisposableCameraPreset>,
    val manualConfig: ManualCameraConfig = ManualCameraConfig(),
    val selectedPreset: DisposableCameraPreset? = null,
    val isCameraReady: Boolean = false,
    val isFrozen: Boolean = false,
    val freezeRequestId: Int = 0,
    val meteredEv100: Double? = null,
    val evaluation: FilmPreviewEvaluation? = null,
    val errorMessage: String? = null,
)

class FilmPreviewViewModel(
    private val repository: DisposableCameraRepository = BuiltInDisposableCameraRepository,
    private val settingsStore: FilmPreviewSettingsStore = FilmPreviewSettingsStore.None,
) : ViewModel() {
    private val initialSettings = settingsStore.load()
    private val initialPresets = listOf(initialSettings.manualConfig.toPreset()) +
        repository.presets()
    private val initialPreset = initialPresets.firstOrNull {
        it.id == initialSettings.selectedPresetId
    } ?: repository.presets().firstOrNull() ?: initialPresets.firstOrNull()
    private val mutableState = MutableStateFlow(
        FilmPreviewUiState(
            presets = initialPresets,
            manualConfig = initialSettings.manualConfig,
            selectedPreset = initialPreset,
            evaluation = initialPreset?.let { FilmPreviewEngine.evaluate(null, it) },
        ),
    )
    val state: StateFlow<FilmPreviewUiState> = mutableState.asStateFlow()

    fun selectPreset(id: String) {
        val preset = mutableState.value.presets.firstOrNull { it.id == id } ?: return
        val currentSettings = settingsStore.load()
        if (!settingsStore.save(currentSettings.copy(selectedPresetId = id))) {
            mutableState.update { it.copy(errorMessage = "预设保存失败，请重试") }
            return
        }
        mutableState.update {
            if (it.selectedPreset?.id == preset.id &&
                it.selectedPreset.presetVersion == preset.presetVersion
            ) {
                return@update it
            }
            it.copy(
                selectedPreset = preset,
                isFrozen = false,
                meteredEv100 = null,
                evaluation = FilmPreviewEngine.evaluate(null, preset),
                errorMessage = null,
            )
        }
    }

    fun savePresetSettings(
        selectedPresetId: String,
        manualConfig: ManualCameraConfig,
    ): Boolean {
        if (!manualConfig.isValid()) {
            mutableState.update { it.copy(errorMessage = "手动配置参数超出有效范围") }
            return false
        }
        val presets = listOf(manualConfig.toPreset()) + repository.presets()
        val selectedPreset = presets.firstOrNull { it.id == selectedPresetId }
            ?: return false
        val saved = settingsStore.save(
            FilmPreviewSettings(
                selectedPresetId = selectedPreset.id,
                manualConfig = manualConfig,
            ),
        )
        if (!saved) {
            mutableState.update { it.copy(errorMessage = "预设保存失败，请重试") }
            return false
        }
        mutableState.update {
            it.copy(
                presets = presets,
                manualConfig = manualConfig,
                selectedPreset = selectedPreset,
                isFrozen = false,
                meteredEv100 = null,
                evaluation = FilmPreviewEngine.evaluate(null, selectedPreset),
                errorMessage = null,
            )
        }
        return true
    }

    fun onCameraReady() {
        mutableState.update { it.copy(isCameraReady = true, errorMessage = null) }
    }

    fun onCameraError(error: Throwable) {
        mutableState.update {
            it.copy(
                isCameraReady = false,
                errorMessage = error.message ?: "相机初始化失败",
            )
        }
    }

    fun invalidateMetering() {
        mutableState.update { current ->
            val preset = current.selectedPreset ?: return@update current
            current.copy(
                isFrozen = false,
                meteredEv100 = null,
                evaluation = FilmPreviewEngine.evaluate(null, preset),
                errorMessage = null,
            )
        }
    }

    fun freezePreview() {
        mutableState.update {
            if (!it.isCameraReady || it.meteredEv100 == null || it.isFrozen) {
                return@update it
            }
            it.copy(
                isFrozen = true,
                freezeRequestId = it.freezeRequestId + 1,
                errorMessage = null,
            )
        }
    }

    fun resumeLivePreview() {
        mutableState.update {
            it.copy(isFrozen = false, errorMessage = null)
        }
    }

    fun onFreezeCaptureFailed() {
        mutableState.update {
            it.copy(
                isFrozen = false,
                errorMessage = "无法定格当前预览，请重试",
            )
        }
    }

    fun onMeteringResult(result: MeteringResult) {
        mutableState.update { current ->
            val preset = current.selectedPreset ?: return@update current
            if (current.isFrozen) return@update current
            current.copy(
                meteredEv100 = result.ev100,
                evaluation = FilmPreviewEngine.evaluate(result.ev100, preset),
            )
        }
    }

    fun onFrozenSnapshot(requestId: Int, snapshot: ExposureSnapshot) {
        mutableState.update { current ->
            val preset = current.selectedPreset ?: return@update current
            if (!current.isFrozen || current.freezeRequestId != requestId) {
                return@update current
            }
            current.copy(
                meteredEv100 = snapshot.meteredEv100,
                evaluation = FilmPreviewEngine.evaluate(snapshot.meteredEv100, preset),
            )
        }
    }
}

class FilmPreviewViewModelFactory(
    private val settingsStore: FilmPreviewSettingsStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FilmPreviewViewModel::class.java))
        return FilmPreviewViewModel(settingsStore = settingsStore) as T
    }
}
