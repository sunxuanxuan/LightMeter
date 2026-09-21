import {
    DEFAULT_MANUAL_CAMERA_CONFIG,
    isValidManualCameraConfig,
    MANUAL_CAMERA_ID,
} from '@/data/manualCamera';
import type {
    CameraPreset,
    FilmOption,
    ManualCameraConfig,
    SelectorKind,
} from '@/types/camera';
import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components';
import classnames from 'classnames';
import React, { useState } from 'react';
import styles from './index.module.scss';

interface SelectorSheetProps {
  kind: SelectorKind;
  cameras: CameraPreset[];
  films: FilmOption[];
  selectedCameraId: string;
  selectedFilmId: string;
  onCameraSelect: (camera: CameraPreset) => void;
  onFilmSelect: (film: FilmOption) => void;
  manualCameraConfig?: ManualCameraConfig;
  onManualCameraSave?: (config: ManualCameraConfig) => void;
  onClose: () => void;
}

interface ManualCameraDraft {
  iso: string;
  shutterDenominator: string;
  aperture: string;
  focalLengthMm: string;
}

const toDraft = (config: ManualCameraConfig): ManualCameraDraft => ({
  iso: config.iso.toString(),
  shutterDenominator: config.shutterDenominator.toString(),
  aperture: config.aperture.toString(),
  focalLengthMm: config.focalLengthMm.toString(),
});

const parseInteger = (value: string): number => (
  /^\d+$/.test(value) ? Number(value) : Number.NaN
);

const parseDecimal = (value: string): number => (
  /^\d+(?:\.\d+)?$/.test(value) ? Number(value) : Number.NaN
);

const SelectorSheet: React.FC<SelectorSheetProps> = ({
  kind,
  cameras,
  films,
  selectedCameraId,
  selectedFilmId,
  onCameraSelect,
  onFilmSelect,
  manualCameraConfig,
  onManualCameraSave,
  onClose,
}) => {
  const [editingManual, setEditingManual] = useState(false);
  const [manualDraft, setManualDraft] = useState<ManualCameraDraft>(
    toDraft(manualCameraConfig || DEFAULT_MANUAL_CAMERA_CONFIG),
  );
  const options: Array<CameraPreset | FilmOption> = kind === 'camera' ? cameras : films;
  const filmTitle = films[0]?.frame === 'none' ? '底片' : '选择相纸';
  const pendingManualConfig: ManualCameraConfig = {
    iso: parseInteger(manualDraft.iso),
    shutterDenominator: parseInteger(manualDraft.shutterDenominator),
    aperture: parseDecimal(manualDraft.aperture),
    focalLengthMm: parseDecimal(manualDraft.focalLengthMm),
  };
  const manualConfigValid = isValidManualCameraConfig(pendingManualConfig);

  const updateManualDraft = (
    key: keyof ManualCameraDraft,
    value: string,
  ) => {
    setManualDraft((current) => ({ ...current, [key]: value }));
  };

  const saveManualCamera = () => {
    if (!manualConfigValid || !onManualCameraSave) return;
    console.info('[SelectorSheet] Manual camera saved', pendingManualConfig);
    onManualCameraSave(pendingManualConfig);
  };

  return (
    <View className={styles.backdrop} onClick={onClose}>
      <View className={styles.sheet} onClick={(event) => event.stopPropagation()}>
        <View className={styles.handle} />
        {editingManual ? (
          <>
            <View className={styles.editorHeader}>
              <Button
                className={styles.editorBack}
                onClick={() => setEditingManual(false)}
                aria-label='返回机型列表'
              >
                ‹
              </Button>
              <Text className={styles.title}>自定义机型</Text>
            </View>
            <View className={styles.editor}>
              <View className={styles.fieldGrid}>
                <View className={styles.field}>
                  <Text className={styles.fieldLabel}>ISO</Text>
                  <Input
                    className={styles.fieldInput}
                    type='number'
                    maxlength={4}
                    value={manualDraft.iso}
                    placeholder='25-6400'
                    onInput={(event) => updateManualDraft('iso', event.detail.value)}
                  />
                </View>
                <View className={styles.field}>
                  <Text className={styles.fieldLabel}>快门 1/x 秒</Text>
                  <Input
                    className={styles.fieldInput}
                    type='number'
                    maxlength={4}
                    value={manualDraft.shutterDenominator}
                    placeholder='1-8000'
                    onInput={(event) => updateManualDraft(
                      'shutterDenominator',
                      event.detail.value,
                    )}
                  />
                </View>
                <View className={styles.field}>
                  <Text className={styles.fieldLabel}>光圈 f/</Text>
                  <Input
                    className={styles.fieldInput}
                    type='digit'
                    maxlength={4}
                    value={manualDraft.aperture}
                    placeholder='1-64'
                    onInput={(event) => updateManualDraft('aperture', event.detail.value)}
                  />
                </View>
                <View className={styles.field}>
                  <Text className={styles.fieldLabel}>焦段 mm</Text>
                  <Input
                    className={styles.fieldInput}
                    type='digit'
                    maxlength={5}
                    value={manualDraft.focalLengthMm}
                    placeholder='20-150'
                    onInput={(event) => updateManualDraft(
                      'focalLengthMm',
                      event.detail.value,
                    )}
                  />
                </View>
              </View>
              <Text
                className={classnames(
                  styles.editorStatus,
                  !manualConfigValid && styles.editorError,
                )}
              >
                {manualConfigValid ? '参数仅保存在本机' : '请检查参数范围和数字格式'}
              </Text>
              <Button
                className={styles.saveButton}
                disabled={!manualConfigValid}
                onClick={saveManualCamera}
              >
                保存并使用
              </Button>
            </View>
          </>
        ) : (
          <>
            <View className={styles.header}>
              <Text className={styles.title}>{kind === 'camera' ? '选择机型' : filmTitle}</Text>
            </View>
            <ScrollView scrollX className={styles.list} enhanced showScrollbar={false}>
              {options.map((option) => {
                const selected = kind === 'camera'
                  ? option.id === selectedCameraId
                  : option.id === selectedFilmId;
                const isCamera = 'filmIds' in option;
                const isManual = isCamera && option.id === MANUAL_CAMERA_ID;
                return (
                  <Button
                    key={option.id}
                    className={classnames(styles.option, selected && styles.selected)}
                    onClick={() => {
                      if (isManual && onManualCameraSave) {
                        setEditingManual(true);
                        return;
                      }
                      if (isCamera) {
                        onCameraSelect(option);
                      } else {
                        onFilmSelect(option);
                      }
                    }}
                    aria-label={isCamera ? `选择机型：${option.name}` : `选择底片：${option.name}`}
                  >
                    <Image
                      className={styles.objectPreview}
                      src={option.image}
                      mode='aspectFit'
                      onError={(event) => console.error('[SelectorSheet] Image failed', {
                        id: option.id,
                        detail: event.detail,
                      })}
                    />
                    {isManual && <Text className={styles.editBadge}>＋</Text>}
                  </Button>
                );
              })}
            </ScrollView>
          </>
        )}
      </View>
    </View>
  );
};

export default SelectorSheet;
