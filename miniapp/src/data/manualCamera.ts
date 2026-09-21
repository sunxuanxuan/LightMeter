import bottomCamera from '@/assets/images/bottom_camera_left.png';
import bottomFilm from '@/assets/images/bottom_film_right.png';
import type { CameraPreset, FilmOption, ManualCameraConfig } from '@/types/camera';

export const MANUAL_CAMERA_ID = 'manual-camera';
export const MANUAL_FILM_ID = 'manual-color-film';

export const DEFAULT_MANUAL_CAMERA_CONFIG: ManualCameraConfig = {
  iso: 400,
  shutterDenominator: 125,
  aperture: 11,
  focalLengthMm: 32,
};

export const isValidManualCameraConfig = (
  config: ManualCameraConfig,
): boolean => config.iso >= 25
  && config.iso <= 6400
  && config.shutterDenominator >= 1
  && config.shutterDenominator <= 8000
  && Number.isFinite(config.aperture)
  && config.aperture >= 1
  && config.aperture <= 64
  && Number.isFinite(config.focalLengthMm)
  && config.focalLengthMm >= 20
  && config.focalLengthMm <= 150;

export const createManualFilm = (
  config: ManualCameraConfig,
): FilmOption => ({
  id: MANUAL_FILM_ID,
  name: `自定义 ISO ${config.iso} 彩色负片`,
  shortName: `ISO ${config.iso}`,
  iso: config.iso,
  tint: '#D3A52B',
  frame: 'none',
  image: bottomFilm,
});

export const createManualCamera = (
  config: ManualCameraConfig,
): CameraPreset => ({
  id: MANUAL_CAMERA_ID,
  name: '自定义机型',
  shortName: '自定义机型',
  detail: `${config.focalLengthMm}mm · f/${config.aperture} · 1/${config.shutterDenominator}s`,
  accent: '#D3A52B',
  image: bottomCamera,
  filmIds: [MANUAL_FILM_ID],
  defaultFilmId: MANUAL_FILM_ID,
});
