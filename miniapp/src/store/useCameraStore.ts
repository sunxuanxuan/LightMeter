import Taro from '@tarojs/taro';
import { create } from 'zustand';
import {
  DEFAULT_MANUAL_CAMERA_CONFIG,
  isValidManualCameraConfig,
  MANUAL_CAMERA_ID,
  MANUAL_FILM_ID,
} from '@/data/manualCamera';
import type { ManualCameraConfig } from '@/types/camera';

interface CameraPreferences {
  disposableCameraId: string;
  disposableFilmId: string;
  instantCameraId: string;
  instantFilmId: string;
  manualCameraConfig: ManualCameraConfig;
}

interface CameraStore extends CameraPreferences {
  setDisposableSelection: (cameraId: string, filmId: string) => void;
  setInstantSelection: (cameraId: string, filmId: string) => void;
  saveManualCamera: (config: ManualCameraConfig) => void;
  reset: () => void;
}

const STORAGE_KEY = 'filmlightmeter-camera-preferences-v1';
const defaults: CameraPreferences = {
  disposableCameraId: 'kodak-power-flash-800',
  disposableFilmId: 'kodak-800',
  instantCameraId: 'fujifilm-instax-mini-12',
  instantFilmId: 'instax-mini-white',
  manualCameraConfig: DEFAULT_MANUAL_CAMERA_CONFIG,
};

const loadPreferences = (): CameraPreferences => {
  try {
    const cached = Taro.getStorageSync<Partial<CameraPreferences>>(STORAGE_KEY);
    const manualCameraConfig = cached?.manualCameraConfig
      && isValidManualCameraConfig(cached.manualCameraConfig)
      ? cached.manualCameraConfig
      : DEFAULT_MANUAL_CAMERA_CONFIG;
    return { ...defaults, ...(cached || {}), manualCameraConfig };
  } catch (error) {
    console.error('[CameraStore] Failed to load preferences', error);
    return defaults;
  }
};

const persist = (preferences: CameraPreferences) => {
  try {
    Taro.setStorageSync(STORAGE_KEY, preferences);
    console.info('[CameraStore] Preferences updated', preferences);
  } catch (error) {
    console.error('[CameraStore] Failed to persist preferences', error);
  }
};

export const useCameraStore = create<CameraStore>((set) => ({
  ...loadPreferences(),
  setDisposableSelection: (cameraId, filmId) => set((state) => {
    const next = { ...state, disposableCameraId: cameraId, disposableFilmId: filmId };
    persist({
      disposableCameraId: next.disposableCameraId,
      disposableFilmId: next.disposableFilmId,
      instantCameraId: next.instantCameraId,
      instantFilmId: next.instantFilmId,
      manualCameraConfig: next.manualCameraConfig,
    });
    return next;
  }),
  setInstantSelection: (cameraId, filmId) => set((state) => {
    const next = { ...state, instantCameraId: cameraId, instantFilmId: filmId };
    persist({
      disposableCameraId: next.disposableCameraId,
      disposableFilmId: next.disposableFilmId,
      instantCameraId: next.instantCameraId,
      instantFilmId: next.instantFilmId,
      manualCameraConfig: next.manualCameraConfig,
    });
    return next;
  }),
  saveManualCamera: (config) => set((state) => {
    if (!isValidManualCameraConfig(config)) {
      console.error('[CameraStore] Refused invalid manual camera config', config);
      return state;
    }
    const next = {
      ...state,
      disposableCameraId: MANUAL_CAMERA_ID,
      disposableFilmId: MANUAL_FILM_ID,
      manualCameraConfig: config,
    };
    persist({
      disposableCameraId: next.disposableCameraId,
      disposableFilmId: next.disposableFilmId,
      instantCameraId: next.instantCameraId,
      instantFilmId: next.instantFilmId,
      manualCameraConfig: next.manualCameraConfig,
    });
    return next;
  }),
  reset: () => {
    try {
      Taro.removeStorageSync(STORAGE_KEY);
    } catch (error) {
      console.error('[CameraStore] Failed to clear preferences', error);
    }
    set(defaults);
  },
}));
