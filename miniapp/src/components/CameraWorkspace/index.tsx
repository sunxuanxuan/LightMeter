import SelectorSheet from '@/components/SelectorSheet';
import type {
    CameraMode,
    CameraPreset,
    FilmOption,
    ManualCameraConfig,
    SelectorKind,
} from '@/types/camera';
import { lightAdvice, processPhoto } from '@/utils/photoProcessor';
import { Button, Camera, Canvas, Image, Text, View } from '@tarojs/components';
import Taro from '@tarojs/taro';
import classnames from 'classnames';
import React, { useMemo, useState } from 'react';
import styles from './index.module.scss';

interface CameraWorkspaceProps {
  mode: CameraMode;
  cameras: CameraPreset[];
  films: FilmOption[];
  selectedCameraId: string;
  selectedFilmId: string;
  onSelectionChange: (cameraId: string, filmId: string) => void;
  manualCameraConfig?: ManualCameraConfig;
  onManualCameraSave?: (config: ManualCameraConfig) => void;
}

const DEMO_IMAGE = 'https://copilot-cn.bytedance.net/api/ide/v1/text_to_image?prompt=realistic%20vertical%20travel%20photograph%20of%20a%20quiet%20sunlit%20street%2C%20natural%20daylight%2C%2035mm%20film%20photography%2C%20subtle%20grain%2C%20balanced%20composition%2C%20no%20text%2C%20no%20logos&image_size=portrait_4_3';

const CameraWorkspace: React.FC<CameraWorkspaceProps> = ({
  mode,
  cameras,
  films,
  selectedCameraId,
  selectedFilmId,
  onSelectionChange,
  manualCameraConfig,
  onManualCameraSave,
}) => {
  const [selector, setSelector] = useState<SelectorKind | null>(null);
  const [sourcePhoto, setSourcePhoto] = useState('');
  const [resultPhoto, setResultPhoto] = useState('');
  const [busy, setBusy] = useState(false);
  const [split, setSplit] = useState(50);
  const [advice, setAdvice] = useState('拍摄后分析当前光线');
  const [cameraError, setCameraError] = useState(false);
  const canvasId = `${mode}-photo-canvas`;
  const isH5 = process.env.TARO_ENV === 'h5';

  const selectedCamera = useMemo(
    () => cameras.find((camera) => camera.id === selectedCameraId) || cameras[0],
    [cameras, selectedCameraId],
  );
  const availableFilms = useMemo(
    () => films.filter((film) => selectedCamera.filmIds.includes(film.id)),
    [films, selectedCamera],
  );
  const selectedFilm = availableFilms.find((film) => film.id === selectedFilmId)
    || availableFilms[0];

  const selectCamera = (camera: CameraPreset) => {
    const filmId = camera.filmIds.includes(selectedFilmId)
      ? selectedFilmId
      : camera.defaultFilmId;
    onSelectionChange(camera.id, filmId);
    setSelector(null);
  };

  const selectFilm = (film: FilmOption) => {
    onSelectionChange(selectedCamera.id, film.id);
    setSelector(null);
  };

  const saveManualCamera = (config: ManualCameraConfig) => {
    if (!onManualCameraSave) return;
    onManualCameraSave(config);
    setSelector(null);
    Taro.showToast({ title: '已使用自定义机型', icon: 'success' });
  };

  const renderCapture = async (path: string) => {
    setBusy(true);
    setSourcePhoto(path);
    setSplit(50);
    try {
      const processed = await processPhoto({
        canvasId,
        sourcePath: path,
        mode,
        frame: selectedFilm.frame,
        filmId: selectedFilm.id,
      });
      setResultPhoto(processed.path);
      setAdvice(lightAdvice(processed.luminance, selectedCamera.flashRange));
      console.info('[CameraWorkspace] Capture ready', { mode, luminance: processed.luminance });
    } catch (error) {
      console.error('[CameraWorkspace] Failed to process capture', error);
      setSourcePhoto('');
      Taro.showToast({ title: '成片生成失败，请重试', icon: 'none' });
    } finally {
      setBusy(false);
    }
  };

  const capture = async () => {
    if (busy) return;
    try {
      if (isH5) {
        const picked = await Taro.chooseMedia({
          count: 1,
          mediaType: ['image'],
          sourceType: ['camera', 'album'],
        });
        const path = picked.tempFiles[0]?.tempFilePath;
        if (path) await renderCapture(path);
        return;
      }
      const context = Taro.createCameraContext();
      const photoPath = await new Promise<string>((resolve, reject) => {
        context.takePhoto({
          quality: 'high',
          success: (result) => resolve(result.tempImagePath),
          fail: reject,
        });
      });
      await renderCapture(photoPath);
    } catch (error) {
      console.error('[CameraWorkspace] Capture failed', error);
      Taro.showToast({ title: '无法拍摄，请检查相机权限', icon: 'none' });
    }
  };

  const save = async () => {
    if (!resultPhoto) return;
    try {
      await Taro.saveImageToPhotosAlbum({ filePath: resultPhoto });
      console.info('[CameraWorkspace] Processed photo saved', { mode });
      Taro.showToast({ title: '已保存到相册', icon: 'success' });
      setSourcePhoto('');
      setResultPhoto('');
      setAdvice('拍摄后分析当前光线');
    } catch (error) {
      console.error('[CameraWorkspace] Save failed', error);
      Taro.showToast({ title: '保存失败，请检查相册权限', icon: 'none' });
    }
  };

  const resetCapture = () => {
    setSourcePhoto('');
    setResultPhoto('');
    setAdvice('拍摄后分析当前光线');
  };

  const goBack = () => {
    const pages = Taro.getCurrentPages();
    if (pages.length > 1) {
      Taro.navigateBack();
      return;
    }
    Taro.reLaunch({ url: '/pages/home/index' });
  };

  const openSettings = () => {
    Taro.navigateTo({ url: '/pages/settings/index' });
  };

  const liveCamera = (
    <>
      {!isH5 && !cameraError ? (
        <Camera
          className={styles.camera}
          mode='normal'
          devicePosition='back'
          flash='off'
          resolution='high'
          onInitDone={() => console.info('[CameraWorkspace] Camera ready', { mode })}
          onError={(event) => {
            console.error('[CameraWorkspace] Camera error', event.detail);
            setCameraError(true);
          }}
        />
      ) : (
        <Image
          className={styles.demoImage}
          src={DEMO_IMAGE}
          mode='aspectFill'
          onError={(event) => console.error('[CameraWorkspace] Demo image failed', event.detail)}
        />
      )}
    </>
  );

  return (
    <View className={styles.page}>
      <View className={styles.topBar}>
        <Button className={styles.toolbarAction} onClick={goBack} aria-label='返回'>
          <Text className={styles.toolbarIcon}>‹</Text>
          {mode === 'disposable' && <Text className={styles.toolbarLabel}>返回</Text>}
        </Button>
        <Button className={styles.toolbarAction} onClick={openSettings} aria-label='设置'>
          <Text className={styles.settingsIcon}>⚙</Text>
          {mode === 'disposable' && <Text className={styles.toolbarLabel}>设置</Text>}
        </Button>
      </View>

      <View className={styles.workspace}>
      <View
        className={classnames(styles.stage, mode === 'instant' && styles.instantStage)}
        onTouchMove={(event) => {
          if (mode !== 'disposable' || !resultPhoto) return;
          const touchEvent = event as unknown as {
            touches: Array<{ clientX: number }>;
          };
          const touch = touchEvent.touches[0];
          if (!touch) return;
          const windowWidth = Taro.getSystemInfoSync().windowWidth;
          const stageWidth = Math.min(windowWidth, 430);
          const stageLeft = Math.max((windowWidth - stageWidth) / 2, 0);
          setSplit(Math.max(0, Math.min(100, ((touch.clientX - stageLeft) / stageWidth) * 100)));
        }}
      >
        {!sourcePhoto && (
          mode === 'instant' ? (
            <>
              <View className={styles.instantImageWindow}>{liveCamera}</View>
              {selectedFilm.frameImage && (
                <Image
                  className={styles.instantFrame}
                  src={selectedFilm.frameImage}
                  mode='scaleToFill'
                />
              )}
            </>
          ) : (
            <>
              {liveCamera}
              <View className={styles.viewfinderMask}>
                <View className={styles.viewfinderOpening} />
              </View>
            </>
          )
        )}
        {sourcePhoto && (
          <View className={classnames(styles.result, mode === 'instant' && styles.instantResult)}>
            {resultPhoto ? (
              <>
                <Image className={styles.resultImage} src={resultPhoto} mode='aspectFill' />
                {mode === 'disposable' && (
                  <View className={styles.originalMask} style={{ width: `${split}%` }}>
                    <Image className={styles.originalImage} src={sourcePhoto} mode='aspectFill' />
                  </View>
                )}
                {mode === 'disposable' && (
                  <View className={styles.splitLine} style={{ left: `${split}%` }}>
                    <View className={styles.splitHandle}>↔</View>
                  </View>
                )}
                <View className={styles.resultLabels}>
                  <Text>{mode === 'disposable' ? '手机画面' : '模拟成片'}</Text>
                  {mode === 'disposable' && <Text>模拟成片</Text>}
                </View>
              </>
            ) : (
              <View className={styles.processing}>
                <Text className={styles.processingMark}>···</Text>
                <Text>正在生成成片模拟</Text>
              </View>
            )}
          </View>
        )}
      </View>

      <View className={styles.controlPanel}>
        {!resultPhoto ? (
          <View className={styles.controlRow}>
            <Button
              className={styles.selectionButton}
              onClick={() => setSelector('camera')}
              aria-label={`选择机型：${selectedCamera.shortName}`}
            >
              <Image
                className={styles.selectionImage}
                src={selectedCamera.image}
                mode='aspectFit'
              />
            </Button>
            <Button className={styles.shutter} disabled={busy} onClick={capture} aria-label='拍摄'>
              <View className={styles.shutterRing} />
            </Button>
            <Button
              className={styles.selectionButton}
              onClick={() => setSelector('film')}
              aria-label={`选择耗材：${selectedFilm.shortName}`}
            >
              <Image
                className={styles.selectionImage}
                src={selectedFilm.image}
                mode='aspectFit'
              />
            </Button>
          </View>
        ) : (
          <View className={styles.resultActions}>
            <Button className={styles.returnAction} onClick={resetCapture} aria-label='重拍'>↻</Button>
            <Button className={styles.saveAction} onClick={save} aria-label='保存成片'>✓</Button>
          </View>
        )}
        <View className={styles.advicePill}>
          <Text className={styles.sunMark}>☼</Text>
          <Text className={styles.adviceText}>{busy ? '正在生成成片模拟' : advice}</Text>
        </View>
      </View>
      </View>

      <Canvas
        className={styles.processingCanvas}
        canvasId={canvasId}
        id={canvasId}
        style={{ width: '900px', height: mode === 'instant' ? '1433px' : '1350px' }}
      />

      {selector && (
        <SelectorSheet
          kind={selector}
          cameras={cameras}
          films={availableFilms}
          selectedCameraId={selectedCamera.id}
          selectedFilmId={selectedFilm.id}
          onCameraSelect={selectCamera}
          onFilmSelect={selectFilm}
          manualCameraConfig={manualCameraConfig}
          onManualCameraSave={onManualCameraSave ? saveManualCamera : undefined}
          onClose={() => setSelector(null)}
        />
      )}
    </View>
  );
};

export default CameraWorkspace;
