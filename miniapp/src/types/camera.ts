export type CameraMode = 'disposable' | 'instant';
export type SelectorKind = 'camera' | 'film';
export type FilmFrame = 'none' | 'white' | 'black';

export interface FilmOption {
  id: string;
  name: string;
  shortName: string;
  iso: number;
  tint: string;
  frame: FilmFrame;
  image: string;
  frameImage?: string;
}

export interface CameraPreset {
  id: string;
  name: string;
  shortName: string;
  detail: string;
  accent: string;
  image: string;
  filmIds: string[];
  defaultFilmId: string;
  flashRange?: string;
}

export interface ManualCameraConfig {
  iso: number;
  shutterDenominator: number;
  aperture: number;
  focalLengthMm: number;
}

export interface ProcessedPhoto {
  path: string;
  luminance: number | null;
}
