import SwiftUI

struct FilmPreviewSettingsScreen: View {
    let presets: [DisposableCameraPreset]
    let themeStyle: AppThemeStyle
    let onThemeStyleChanged: (AppThemeStyle) -> Void
    let onSave: (String, ManualCameraConfig) -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var selectedPresetID: String
    @State private var expandedPresetIDs: Set<String> = []
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
                        presetDisclosure(preset)
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
    }

    private func presetDisclosure(
        _ preset: DisposableCameraPreset
    ) -> some View {
        let selected = selectedPresetID == preset.id
        return DisclosureGroup(
            isExpanded: Binding(
                get: { expandedPresetIDs.contains(preset.id) },
                set: { expanded in
                    if expanded {
                        expandedPresetIDs.insert(preset.id)
                    } else {
                        expandedPresetIDs.remove(preset.id)
                    }
                }
            )
        ) {
            VStack(alignment: .leading, spacing: 6) {
                Text(parameterSummary(preset))
                    .font(.subheadline)
                Text(flashSummary(preset))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let region = preset.regionOrBatch {
                    Text(region)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                if preset.id == ManualCameraConfig.presetID {
                    Divider().padding(.vertical, 4)
                    manualEditor
                }
            }
            .padding(.top, 8)
        } label: {
            Button {
                selectedPresetID = preset.id
            } label: {
                HStack(spacing: 10) {
                    Image(
                        systemName: selected
                            ? "checkmark.circle.fill" : "circle"
                    )
                    .foregroundStyle(selected ? .orange : .secondary)
                    Text(preset.displayName)
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(14)
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
