import React from 'react';
import { Button, Text, View } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useCameraStore } from '@/store/useCameraStore';
import styles from './index.module.scss';

const SettingsPage: React.FC = () => {
  const reset = useCameraStore((state) => state.reset);

  const resetPreferences = async () => {
    const result = await Taro.showModal({
      title: '恢复默认选择',
      content: '将重置一次性相机机型、自定义参数、胶片和拍立得相纸选择。',
      confirmText: '恢复',
      confirmColor: '#E45649',
    });
    if (!result.confirm) return;
    reset();
    Taro.showToast({ title: '已恢复默认选择', icon: 'success' });
  };

  return (
    <View className={styles.page}>
      <View className={styles.header}>
        <Button
          className={styles.backButton}
          onClick={() => Taro.navigateBack()}
          aria-label='返回'
        >
          ‹
        </Button>
        <View>
          <Text className={styles.brand}>Click & Click</Text>
          <Text className={styles.title}>设置</Text>
        </View>
      </View>

      <View className={styles.section}>
        <Text className={styles.sectionTitle}>照片与隐私</Text>
        <View className={styles.privacyRow}>
          <Text className={styles.privacyMark}>✓</Text>
          <View className={styles.rowContent}>
            <Text className={styles.rowTitle}>仅在本机处理</Text>
            <Text className={styles.rowDescription}>
              相机画面、冻结照片和模拟结果不会上传。只有点击保存后，成片才会写入系统相册。
            </Text>
          </View>
        </View>
      </View>

      <View className={styles.section}>
        <Text className={styles.sectionTitle}>当前版本</Text>
        <View className={styles.featureList}>
          <View className={styles.feature}>
            <Text className={styles.featureName}>一次性相机预览</Text>
            <Text className={styles.featureValue}>4 款预设 + 自定义</Text>
          </View>
          <View className={styles.feature}>
            <Text className={styles.featureName}>拍立得预览</Text>
            <Text className={styles.featureValue}>黑 / 白框相纸</Text>
          </View>
          <View className={styles.feature}>
            <Text className={styles.featureName}>图像处理</Text>
            <Text className={styles.featureValue}>本地 Canvas</Text>
          </View>
        </View>
      </View>

      <View className={styles.section}>
        <Button className={styles.resetButton} onClick={resetPreferences}>恢复默认选择</Button>
        <Text className={styles.footnote}>Click & Click MiniApp 1.0.0</Text>
      </View>
    </View>
  );
};

export default SettingsPage;
