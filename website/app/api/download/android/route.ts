import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";

import { getAndroidArtifactPath } from "@/lib/dal";

export const dynamic = "force-dynamic";

export async function GET() {
  const artifactPath = getAndroidArtifactPath();
  let artifactSize: number;
  try {
    if (!artifactPath) throw new Error("artifact path is not configured");
    const artifactStat = await stat(artifactPath);
    if (!artifactStat.isFile()) throw new Error("artifact is not a file");
    artifactSize = artifactStat.size;
  } catch {
    return Response.json(
      { errorCode: "ANDROID_BUILD_UNAVAILABLE" },
      { status: 404 },
    );
  }
  const source = createReadStream(artifactPath);
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      source.on("data", (chunk) => {
        controller.enqueue(
          typeof chunk === "string"
            ? new TextEncoder().encode(chunk)
            : new Uint8Array(chunk),
        );
      });
      source.on("end", () => controller.close());
      source.on("error", (error) => controller.error(error));
    },
    cancel() {
      source.destroy();
    },
  });
  return new Response(stream, {
    headers: {
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Length": String(artifactSize),
      "Content-Disposition":
        'attachment; filename="FilmLightMeter-0.1.0-android-release.apk"',
      "Cache-Control": "no-store",
    },
  });
}
