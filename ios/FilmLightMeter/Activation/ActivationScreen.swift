import SwiftUI
import UIKit

struct ActivationScreen: View {
    @ObservedObject var store: ActivationStore
    @State private var credential = ""

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "camera.aperture")
                .font(.system(size: 72))
                .foregroundStyle(.orange)
            Text("激活 FilmLightMeter")
                .font(.largeTitle.bold())
            Text("在官网购买时填写设备 ID，然后粘贴付款后生成的签名激活凭证。")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            Button {
                UIPasteboard.general.string = store.deviceID
            } label: {
                Label(store.deviceID, systemImage: "doc.on.doc")
                    .font(.system(.title3, design: .monospaced))
                    .padding()
                    .frame(maxWidth: .infinity)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
            }

            TextField("激活凭证", text: $credential, axis: .vertical)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .lineLimit(3...6)
                .textFieldStyle(.roundedBorder)

            if let error = store.errorMessage {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            Button("激活") {
                store.activate(credential: credential)
            }
            .buttonStyle(.borderedProminent)
            .tint(.orange)
            .controlSize(.large)
            .disabled(credential.isEmpty)
            Spacer()
        }
        .padding(28)
    }
}
