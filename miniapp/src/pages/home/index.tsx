import homeFilmRoll from '@/assets/images/home_film_roll.png';
import homeInstantCamera from '@/assets/images/home_instant_camera.png';
import { Button, Image, Text, View } from '@tarojs/components';
import Taro from '@tarojs/taro';
import React from 'react';
import styles from './index.module.scss';

interface FeatureCardProps {
  image: string;
  title: string;
  description: string;
  onClick: () => void;
}

const FeatureCard: React.FC<FeatureCardProps> = ({
  image,
  title,
  description,
  onClick,
}) => (
  <Button className={styles.featureCard} onClick={onClick} aria-label={title}>
    <Image className={styles.featureImage} src={image} mode='aspectFit' />
    <View className={styles.featureCopy}>
      <View className={styles.copySpacer} />
      <Text className={styles.featureTitle}>{title}</Text>
      <Text className={styles.featureDescription}>{description}</Text>
      <View className={styles.copySpacer} />
      <View className={styles.arrowButton}>›</View>
    </View>
  </Button>
);

const HomePage: React.FC = () => (
  <View className={styles.page}>
    <View className={styles.header}>
      <Button
        className={styles.settingsButton}
        onClick={() => Taro.navigateTo({ url: '/pages/settings/index' })}
        aria-label='设置'
      >
        <Text className={styles.settingsIcon}>⚙</Text>
      </Button>
    </View>

    <Text className={styles.brand}>一拍即合</Text>
    <Text className={styles.subtitle}>Click & Click</Text>

    <View className={styles.cards}>
      <FeatureCard
        image={homeFilmRoll}
        title='胶片模拟'
        description={'多胶片预设\n预见成片效果'}
        onClick={() => Taro.navigateTo({ url: '/pages/disposable/index' })}
      />
      <FeatureCard
        image={homeInstantCamera}
        title='拍立得预览'
        description={'多种拍立得相纸\n即时成像模拟'}
        onClick={() => Taro.navigateTo({ url: '/pages/instant/index' })}
      />
    </View>
  </View>
);

export default HomePage;
