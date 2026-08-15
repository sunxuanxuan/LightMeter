import { ImageResponse } from "next/og";

export const size = {
  width: 64,
  height: 64,
};

export const contentType = "image/png";

export default function Icon() {
  return new ImageResponse(
    <div
      style={{
        width: "64px",
        height: "64px",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        color: "#fff8f3",
        background: "#d85f37",
        borderRadius: "12px",
        fontSize: "20px",
        fontWeight: 700,
      }}
    >
      FLM
    </div>,
    size,
  );
}
