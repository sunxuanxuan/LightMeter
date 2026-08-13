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
                    Picker("测光模式", selection: $settings.meteringMode) {
                        ForEach(MeteringMode.allCases, id: \.self) {
                            Text($0.displayName).tag($0)
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
                            value: $settings.centerAreaPercent,
                            in: 5...80,
                            step: 5
                        )
                        Stepper(
                            "中央权重 \(settings.centerWeightPercent)%",
                            value: $settings.centerWeightPercent,
                            in: 50...95,
                            step: 5
                        )
                    }
                }

                Section("曝光风险") {
                    Toggle("启用风险预览", isOn: $settings.exposureRiskEnabled)
                    Picker(
                        "胶片预设",
                        selection: Binding(
                            get: { settings.filmLatitudePreset },
                            set: applyPreset
                        )
                    ) {
                        Text("自定义").tag(FilmLatitudePreset?.none)
                        ForEach(FilmLatitudePreset.allCases) {
                            Text($0.values.name).tag(FilmLatitudePreset?.some($0))
                        }
                    }
                    Stepper(
                        "高光 +\(stopText(settings.highlightLatitude)) EV",
                        value: $settings.highlightLatitude,
                        in: 1.0 / 3...8,
                        step: 1.0 / 3
                    )
                    Stepper(
                        "暗部 -\(stopText(settings.shadowLatitude)) EV",
                        value: $settings.shadowLatitude,
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

    private func applyPreset(_ preset: FilmLatitudePreset?) {
        settings.filmLatitudePreset = preset
        if let values = preset?.values {
            settings.highlightLatitude = values.highlight
            settings.shadowLatitude = values.shadow
        }
    }

    private func stopText(_ value: Double) -> String {
        String(format: "%.1f", value)
    }
}
