import SwiftUI

struct FilmPreviewSettingsScreen: View {
    let presets: [DisposableCameraPreset]
    let themeStyle: AppThemeStyle
    let onThemeStyleChanged: (AppThemeStyle) -> Void
    let onSave: (String, ManualCameraConfig) -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var selectedPresetID: String
    @State private var imagePreviewPreset: DisposableCameraPreset?
    @State private var manualEditorExpanded: Bool
    @State private var manualISO: String
    @State private var manualShutter: String
    @State private var manualAperture: String
    @State private var manualFocalLength: String

    init(
        presets: [DisposableCameraPreset],
        selectedPreset: DisposableCameraPreset,
        manualConfig: ManualCameraConfig,
        themeStyle: AppThemeStyle,
        onThemeStyleChanged: @escaping (AppThemeStyle) -> Void,
        onSave: @escaping (String, ManualCameraConfig) -> Bool
    ) {
        self.presets = presets
        self.themeStyle = themeStyle
        self.onThemeStyleChanged = onThemeStyleChanged
        self.onSave = onSave
        _selectedPresetID = State(initialValue: selectedPreset.id)
        _manualEditorExpanded = State(
            initialValue: selectedPreset.id == ManualCameraConfig.presetID
        )
        _manualISO = State(initialValue: String(manualConfig.iso))
        _manualShutter = State(
            initialValue: String(manualConfig.shutterDenominator)
        )
        _manualAperture = State(
            initialValue: formatDecimal(manualConfig.aperture)
        )
        _manualFocalLength = State(
            initialValue: formatDecimal(manualConfig.focalLengthMillimeters)
        )
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text("外观")
                        .font(.headline)
                    Picker("外观", selection: themeBinding) {
                        ForEach(AppThemeStyle.allCases) { style in
                            Text(style.displayName).tag(style)
                        }
                    }
                    .pickerStyle(.segmented)
                    Text("曝光参数由预设提供，预览模式中不可单独修改。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 6)
                    ForEach(presets) { preset in
                        presetCard(preset)
                    }
                }
                .padding(16)
            }
            .navigationTitle("一次性相机预设")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        if onSave(selectedPresetID, manualConfigToSave) {
                            dismiss()
                        }
                    }
                    .disabled(
                        selectedPresetID == ManualCameraConfig.presetID
                            && !pendingManualConfig.isValid
                    )
                }
            }
        }
        .sheet(item: $imagePreviewPreset) { preset in
            PresetImagePreview(preset: preset)
        }
    }

    private func presetCard(
        _ preset: DisposableCameraPreset
    ) -> some View {
        let selected = selectedPresetID == preset.id
        let isManualPreset = preset.id == ManualCameraConfig.presetID
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                if let assetName = preset.previewAssetName {
                    Button {
                        imagePreviewPreset = preset
                    } label: {
                        Image(assetName)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 112, height: 72)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("查看\(preset.displayName)参考图")
                } else {
                    Image(systemName: "slider.horizontal.3")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                        .frame(width: 112, height: 72)
                        .background(
                            Color(uiColor: .tertiarySystemFill),
                            in: RoundedRectangle(cornerRadius: 6)
                        )
                }
                Button {
                    if isManualPreset && selected {
                        manualEditorExpanded.toggle()
                    } else {
                        selectedPresetID = preset.id
                        manualEditorExpanded = isManualPreset
                    }
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(preset.displayName)
                                .font(.headline)
                                .foregroundStyle(.primary)
                            Text(parameterSummary(preset))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Text(flashSummary(preset))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                        Image(
                            systemName: selected
                                ? "checkmark.circle.fill" : "circle"
                        )
                        .foregroundStyle(selected ? .orange : .secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("选择\(preset.displayName)")
            }
            if selected, isManualPreset, manualEditorExpanded {
                Divider()
                manualEditor
            }
        }
        .padding(10)
        .background(
            selected
                ? Color.orange.opacity(0.14)
                : Color(uiColor: .secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: 8)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(
                    selected ? Color.orange : Color(uiColor: .separator)
                )
        }
    }

    private var manualEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("手动参数")
                .font(.subheadline.bold())
            HStack(spacing: 8) {
                manualField("ISO", text: $manualISO, decimal: false)
                manualField(
                    "快门 1/x 秒",
                    text: $manualShutter,
                    decimal: false
                )
            }
            HStack(spacing: 8) {
                manualField("光圈 f/", text: $manualAperture, decimal: true)
                manualField(
                    "焦段 mm",
                    text: $manualFocalLength,
                    decimal: true
                )
            }
            Text(
                pendingManualConfig.isValid
                    ? "支持 ISO 25-6400、快门 1-1/8000 秒、f/1-f/64、20-150mm"
                    : "请检查参数范围和数字格式"
            )
            .font(.caption2)
            .foregroundStyle(
                pendingManualConfig.isValid
                    ? Color.secondary : Color.red
            )
        }
    }

    private func manualField(
        _ title: String,
        text: Binding<String>,
        decimal: Bool
    ) -> some View {
        TextField(title, text: text)
            .keyboardType(decimal ? .decimalPad : .numberPad)
            .textFieldStyle(.roundedBorder)
            .onChange(of: text.wrappedValue) { _, value in
                let allowed = decimal
                    ? value.filter { $0.isNumber || $0 == "." }
                    : value.filter(\.isNumber)
                let normalized = allowed.reduce(into: "") { result, character in
                    if character != "." || !result.contains(".") {
                        result.append(character)
                    }
                }
                if normalized != value {
                    text.wrappedValue = normalized
                }
            }
    }

    private var pendingManualConfig: ManualCameraConfig {
        ManualCameraConfig(
            iso: Int(manualISO) ?? 0,
            shutterDenominator: Int(manualShutter) ?? 0,
            aperture: Double(manualAperture) ?? .nan,
            focalLengthMillimeters: Double(manualFocalLength) ?? .nan
        )
    }

    private var manualConfigToSave: ManualCameraConfig {
        pendingManualConfig.isValid
            ? pendingManualConfig
            : presets.first(where: { $0.id == ManualCameraConfig.presetID })
                .map {
                    ManualCameraConfig(
                        iso: $0.film.iso,
                        shutterDenominator: Int(
                            (1 / $0.shutterSeconds).rounded()
                        ),
                        aperture: $0.optics.aperture,
                        focalLengthMillimeters:
                            $0.optics.focalLengthMillimeters
                    )
                } ?? ManualCameraConfig()
    }

    private var themeBinding: Binding<AppThemeStyle> {
        Binding(
            get: { themeStyle },
            set: { style in onThemeStyleChanged(style) }
        )
    }

    private func parameterSummary(_ preset: DisposableCameraPreset) -> String {
        "ISO \(preset.film.iso) · "
            + "f/\(formatDecimal(preset.optics.aperture)) · "
            + "\(shutterLabel(preset.shutterSeconds)) · "
            + "\(formatDecimal(preset.optics.focalLengthMillimeters)) mm"
    }

    private func flashSummary(_ preset: DisposableCameraPreset) -> String {
        preset.flash.map {
            "闪光有效距离 "
                + "\(formatDecimal($0.effectiveDistanceMinMeters))-"
                + "\(formatDecimal($0.effectiveDistanceMaxMeters)) m"
        } ?? "无闪光参数"
    }
}

private struct PresetImagePreview: View {
    let preset: DisposableCameraPreset

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let assetName = preset.previewAssetName {
                    Image(assetName)
                        .resizable()
                        .scaledToFit()
                        .padding(16)
                }
            }
            .navigationTitle(preset.displayName)
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large])
    }
}

private extension DisposableCameraPreset {
    var previewAssetName: String? {
        switch id {
        case "kodak-funsaver-800":
            "PresetFunSaver"
        case "kodak-power-flash-800":
            "PresetPowerFlash"
        case "fujifilm-quicksnap-flash-400":
            "PresetQuickSnap"
        case "fujifilm-c400-jelly":
            "PresetC400"
        default:
            nil
        }
    }
}
