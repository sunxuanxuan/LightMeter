import AVFoundation
import SwiftUI
import UIKit

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewContainer {
        let view = PreviewContainer()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewContainer, context: Context) {
        uiView.previewLayer.session = session
    }
}

final class PreviewContainer: UIView {
    override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if previewLayer.connection?.isVideoRotationAngleSupported(90) == true {
            previewLayer.connection?.videoRotationAngle = 90
        }
    }
}
