import React, { useMemo } from 'react';
import { View } from '@tarojs/components';
import CameraWorkspace from '@/components/CameraWorkspace';
import { disposableCameras, disposableFilms } from '@/data/presets';
import { createManualCamera, createManualFilm } from '@/data/manualCamera';
import { useCameraStore } from '@/store/useCameraStore';
import styles from './index.module.scss';

const DisposablePage: React.FC = () => {
  const cameraId = useCameraStore((state) => state.disposableCameraId);
  const filmId = useCameraStore((state) => state.disposableFilmId);
  const manualCameraConfig = useCameraStore((state) => state.manualCameraConfig);
  const setSelection = useCameraStore((state) => state.setDisposableSelection);
  const saveManualCamera = useCameraStore((state) => state.saveManualCamera);
  const cameras = useMemo(
    () => [createManualCamera(manualCameraConfig), ...disposableCameras],
    [manualCameraConfig],
  );
  const films = useMemo(
    () => [createManualFilm(manualCameraConfig), ...disposableFilms],
    [manualCameraConfig],
  );

  return (
    <View className={styles.page}>
      <CameraWorkspace
        mode='disposable'
        cameras={cameras}
        films={films}
        selectedCameraId={cameraId}
        selectedFilmId={filmId}
        onSelectionChange={setSelection}
        manualCameraConfig={manualCameraConfig}
        onManualCameraSave={saveManualCamera}
      />
    </View>
  );
};

export default DisposablePage;
