import { existsSync, readFileSync } from "node:fs";

import { getAndroidArtifactPath } from "@/lib/dal";

export const dynamic = "force-dynamic";

export function GET() {
  const artifactPath = getAndroidArtifactPath();
  if (!artifactPath || !existsSync(artifactPath)) {
    return Response.json(
      { errorCode: "ANDROID_BUILD_UNAVAILABLE" },
      { status: 404 },
    );
  }
  const artifact = readFileSync(artifactPath);
  return new Response(new Uint8Array(artifact), {
    headers: {
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Disposition":
        'attachment; filename="FilmLightMeter-0.1.0-android-release.apk"',
      "Cache-Control": "no-store",
    },
  });
}
