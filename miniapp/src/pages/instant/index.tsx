import React from 'react';
import { View } from '@tarojs/components';
import CameraWorkspace from '@/components/CameraWorkspace';
import { instantCameras, instantFilms } from '@/data/presets';
import { useCameraStore } from '@/store/useCameraStore';
import styles from './index.module.scss';

const InstantPage: React.FC = () => {
  const cameraId = useCameraStore((state) => state.instantCameraId);
  const filmId = useCameraStore((state) => state.instantFilmId);
  const setSelection = useCameraStore((state) => state.setInstantSelection);

  return (
    <View className={styles.page}>
      <CameraWorkspace
        mode='instant'
        cameras={instantCameras}
        films={instantFilms}
        selectedCameraId={cameraId}
        selectedFilmId={filmId}
        onSelectionChange={setSelection}
      />
    </View>
  );
};

export default InstantPage;
