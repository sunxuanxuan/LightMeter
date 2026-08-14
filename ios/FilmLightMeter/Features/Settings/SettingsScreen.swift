import SwiftUI

struct SettingsScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var settings: AppSettings
    let onSave: (AppSettings) -> Void

    init(initialSettings: AppSettings, onSave: @escaping (AppSettings) -> Void) {
        _settings = State(initialValue: initialSettings)
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("画幅与测光") {
                    Picker("画幅", selection: $settings.frameFormat) {
                        ForEach(FrameFormat.allCases, id: \.self) {
                            Text($0.displayName).tag($0)
                        }
                    }
                    Picker("测光模式", selection: meteringMode) {
                        ForEach(MeteringMode.allCases, id: \.self) {
                            Text($0.displayName).tag($0)
                        }
                    }
                    Picker("机型预设", selection: cameraMeteringPresetID) {
                        Text("自定义").tag("")
                        ForEach(CameraMeteringPreset.allCases) { preset in
                            Text(preset.displayName).tag(preset.rawValue)
                        }
                    }
                    if settings.meteringMode == .spot {
                        Stepper(
                            "点测光面积 \(settings.spotAreaPercent)%",
                            value: $settings.spotAreaPercent,
                            in: 1...10
                        )
                    }
                    if settings.meteringMode == .centerWeighted {
                        Stepper(
                            "中央区域 \(settings.centerAreaPercent)%",
                            value: centerAreaPercent,
                            in: 5...80,
                            step: 5
                        )
                        Stepper(
                            "中央权重 \(settings.centerWeightPercent)%",
                            value: centerWeightPercent,
                            in: 50...95,
                            step: 5
                        )
                    }
                }

                Section("曝光风险") {
                    Toggle("启用风险预览", isOn: $settings.exposureRiskEnabled)
                    Picker(
                        "胶片预设",
                        selection: filmLatitudePresetID
                    ) {
                        Text("自定义").tag("")
                        ForEach(FilmLatitudePreset.allCases) { preset in
                            Text(preset.values.name).tag(preset.rawValue)
                        }
                    }
                    Stepper(
                        "高光 +\(stopText(settings.highlightLatitude)) EV",
                        value: highlightLatitude,
                        in: 1.0 / 3...8,
                        step: 1.0 / 3
                    )
                    Stepper(
                        "暗部 -\(stopText(settings.shadowLatitude)) EV",
                        value: shadowLatitude,
                        in: 1.0 / 3...8,
                        step: 1.0 / 3
                    )
                }

                Section("设备校准") {
                    Stepper(
                        "校准偏移 \(String(format: "%+.1f", settings.calibrationOffset)) EV",
                        value: $settings.calibrationOffset,
                        in: -3...3,
                        step: 0.1
                    )
                    Text("应使用标准灰卡和参考测光表完成校准。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("设置")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { onSave(settings) }
                }
            }
        }
    }

    private var meteringMode: Binding<MeteringMode> {
        Binding(
            get: { settings.meteringMode },
            set: { mode in
                settings.meteringMode = mode
                settings.cameraMeteringPreset = nil
            }
        )
    }

    private var cameraMeteringPresetID: Binding<String> {
        Binding(
            get: { settings.cameraMeteringPreset?.rawValue ?? "" },
            set: { rawValue in
                applyCameraMeteringPreset(CameraMeteringPreset(rawValue: rawValue))
            }
        )
    }

    private var centerAreaPercent: Binding<Int> {
        Binding(
            get: { settings.centerAreaPercent },
            set: {
                settings.centerAreaPercent = $0
                settings.cameraMeteringPreset = nil
            }
        )
    }

    private var centerWeightPercent: Binding<Int> {
        Binding(
            get: { settings.centerWeightPercent },
            set: {
                settings.centerWeightPercent = $0
                settings.cameraMeteringPreset = nil
            }
        )
    }

    private var filmLatitudePresetID: Binding<String> {
        Binding(
            get: { settings.filmLatitudePreset?.rawValue ?? "" },
            set: { rawValue in
                applyPreset(FilmLatitudePreset(rawValue: rawValue))
            }
        )
    }

    private var highlightLatitude: Binding<Double> {
        Binding(
            get: { settings.highlightLatitude },
            set: {
                settings.highlightLatitude = $0
                settings.filmLatitudePreset = nil
            }
        )
    }

    private var shadowLatitude: Binding<Double> {
        Binding(
            get: { settings.shadowLatitude },
            set: {
                settings.shadowLatitude = $0
                settings.filmLatitudePreset = nil
            }
        )
    }

    private func applyPreset(_ preset: FilmLatitudePreset?) {
        settings.filmLatitudePreset = preset
        if let values = preset?.values {
            settings.highlightLatitude = values.highlight
            settings.shadowLatitude = values.shadow
        }
    }

    private func applyCameraMeteringPreset(_ preset: CameraMeteringPreset?) {
        settings.cameraMeteringPreset = preset
        guard let preset else { return }
        settings.meteringMode = .centerWeighted
        settings.centerAreaPercent = preset.centerAreaPercent
        settings.centerWeightPercent = preset.centerWeightPercent
    }

    private func stopText(_ value: Double) -> String {
        String(format: "%.1f", value)
    }
}
