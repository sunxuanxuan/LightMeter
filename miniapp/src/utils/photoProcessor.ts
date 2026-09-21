import Taro from '@tarojs/taro';
import type { CameraMode, FilmFrame, ProcessedPhoto } from '@/types/camera';

interface RenderOptions {
  canvasId: string;
  sourcePath: string;
  mode: CameraMode;
  frame: FilmFrame;
  filmId: string;
}

const CANVAS_WIDTH = 900;
const DISPOSABLE_HEIGHT = 1350;
const INSTANT_HEIGHT = 1433;

const drawToCanvas = async (options: RenderOptions): Promise<void> => {
  const image = await Taro.getImageInfo({ src: options.sourcePath });
  const height = options.mode === 'instant' ? INSTANT_HEIGHT : DISPOSABLE_HEIGHT;
  const frameInsetX = options.mode === 'instant' ? 67 : 0;
  const frameInsetTop = options.mode === 'instant' ? 117 : 0;
  const targetWidth = options.mode === 'instant' ? 767 : CANVAS_WIDTH;
  const targetHeight = options.mode === 'instant' ? 1033 : DISPOSABLE_HEIGHT;
  const context = Taro.createCanvasContext(options.canvasId);

  if (options.mode === 'instant') {
    context.setFillStyle(options.frame === 'black' ? '#171918' : '#F7F5EE');
    context.fillRect(0, 0, CANVAS_WIDTH, height);
  }

  const sourceRatio = image.width / image.height;
  const targetRatio = targetWidth / targetHeight;
  let sourceX = 0;
  let sourceY = 0;
  let sourceWidth = image.width;
  let sourceHeight = image.height;
  if (sourceRatio > targetRatio) {
    sourceWidth = image.height * targetRatio;
    sourceX = (image.width - sourceWidth) / 2;
  } else {
    sourceHeight = image.width / targetRatio;
    sourceY = (image.height - sourceHeight) / 2;
  }
  context.drawImage(
    options.sourcePath,
    sourceX,
    sourceY,
    sourceWidth,
    sourceHeight,
    frameInsetX,
    frameInsetTop,
    targetWidth,
    targetHeight,
  );

  context.globalCompositeOperation = 'source-atop';
  if (options.filmId.includes('fuji') || options.filmId.includes('superia') || options.filmId === 'c400') {
    context.setFillStyle('rgba(29, 118, 88, 0.08)');
  } else if (options.filmId.includes('gold') || options.filmId.includes('kodak')) {
    context.setFillStyle('rgba(232, 161, 48, 0.08)');
  } else {
    context.setFillStyle('rgba(211, 165, 43, 0.045)');
  }
  context.fillRect(frameInsetX, frameInsetTop, targetWidth, targetHeight);
  context.globalCompositeOperation = 'source-over';

  await new Promise<void>((resolve) => context.draw(false, resolve));
};

const sampleLuminance = async (canvasId: string, mode: CameraMode): Promise<number | null> => {
  try {
    const sample = await Taro.canvasGetImageData({
      canvasId,
      x: 434,
      y: mode === 'instant' ? 618 : 651,
      width: 32,
      height: 48,
    });
    let total = 0;
    let pixels = 0;
    for (let index = 0; index < sample.data.length; index += 4) {
      total += sample.data[index] * 0.2126
        + sample.data[index + 1] * 0.7152
        + sample.data[index + 2] * 0.0722;
      pixels += 1;
    }
    return pixels > 0 ? total / pixels : null;
  } catch (error) {
    console.error('[PhotoProcessor] Failed to sample luminance', error);
    return null;
  }
};

export const processPhoto = async (options: RenderOptions): Promise<ProcessedPhoto> => {
  console.info('[PhotoProcessor] Rendering captured photo', {
    mode: options.mode,
    filmId: options.filmId,
  });
  await drawToCanvas(options);
  const height = options.mode === 'instant' ? INSTANT_HEIGHT : DISPOSABLE_HEIGHT;
  const [result, luminance] = await Promise.all([
    Taro.canvasToTempFilePath({
      canvasId: options.canvasId,
      x: 0,
      y: 0,
      width: CANVAS_WIDTH,
      height,
      destWidth: CANVAS_WIDTH,
      destHeight: height,
      fileType: 'jpg',
      quality: 0.94,
    }),
    sampleLuminance(options.canvasId, options.mode),
  ]);
  return { path: result.tempFilePath, luminance };
};

export const lightAdvice = (
  luminance: number | null,
  flashRange?: string,
): string => {
  if (luminance == null) return '暂时无法判断，请以模拟画面为准';
  if (luminance < 68) {
    return flashRange
      ? `建议开启闪光灯，主体保持在 ${flashRange} 内`
      : '画面可能偏暗，建议增加现场光线';
  }
  if (luminance > 205) return '画面可能偏亮，建议避开强光';
  return '当前构图适合直接拍摄';
};
