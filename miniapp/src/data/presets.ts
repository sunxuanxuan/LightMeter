import type { CameraPreset, FilmOption } from '@/types/camera';
import filmFuji400 from '@/assets/images/film_fuji400.png';
import filmIso100 from '@/assets/images/film_iso100.png';
import filmIso800 from '@/assets/images/film_iso800.png';
import filmKodak200 from '@/assets/images/film_kodak200.png';
import filmKodak400 from '@/assets/images/film_kodak400.png';
import instantFilmBlack from '@/assets/images/instant_film_black.png';
import instantFilmWhite from '@/assets/images/instant_film_white.png';
import instantFrameBlack from '@/assets/images/instant_frame_black.png';
import instantFrameWhite from '@/assets/images/instant_frame_white.png';
import presetC400 from '@/assets/images/preset_c400.png';
import presetInstaxMini12 from '@/assets/images/preset_instax_mini_12.png';
import presetKodakEc35 from '@/assets/images/preset_kodak_ec35.png';
import presetPowerFlash from '@/assets/images/preset_power_flash.png';
import presetQuickSnap from '@/assets/images/preset_quick_snap.png';

export const disposableFilms: FilmOption[] = [
  { id: 'kodak-800', name: 'Kodak ISO 800 彩色负片', shortName: 'Kodak 800', iso: 800, tint: '#d7a43b', frame: 'none', image: filmIso800 },
  { id: 'superia-400', name: 'FUJICOLOR SUPERIA X-TRA 400', shortName: 'Superia 400', iso: 400, tint: '#3c8b69', frame: 'none', image: filmFuji400 },
  { id: 'c400', name: 'Fujifilm C400 ISO 400', shortName: 'C400', iso: 400, tint: '#74a58e', frame: 'none', image: filmFuji400 },
  { id: 'generic-100', name: '通用 ISO 100 彩色负片', shortName: 'ISO 100', iso: 100, tint: '#c95047', frame: 'none', image: filmIso100 },
  { id: 'gold-200', name: 'Kodak Gold 200', shortName: 'Gold 200', iso: 200, tint: '#e2b640', frame: 'none', image: filmKodak200 },
  { id: 'ultramax-400', name: 'Kodak UltraMax 400', shortName: 'UltraMax 400', iso: 400, tint: '#dc8b2f', frame: 'none', image: filmKodak400 },
  { id: 'generic-800', name: '通用 ISO 800 彩色负片', shortName: 'ISO 800', iso: 800, tint: '#a9413a', frame: 'none', image: filmIso800 },
];

export const disposableCameras: CameraPreset[] = [
  {
    id: 'kodak-power-flash-800',
    name: 'Kodak Power Flash / FunSaver',
    shortName: 'FunSaver 800',
    detail: '31mm · f/10 · 1/100s',
    accent: '#e8b728',
    image: presetPowerFlash,
    filmIds: ['kodak-800'],
    defaultFilmId: 'kodak-800',
    flashRange: '1.2–3.5 m',
  },
  {
    id: 'fujifilm-quicksnap-flash-400',
    name: 'Fujifilm QuickSnap Flash 400',
    shortName: 'QuickSnap 400',
    detail: '32mm · f/10 · 1/140s',
    accent: '#219267',
    image: presetQuickSnap,
    filmIds: ['superia-400'],
    defaultFilmId: 'superia-400',
    flashRange: '1–3 m',
  },
  {
    id: 'fujifilm-c400-jelly',
    name: 'Fujifilm C400 果冻胶卷相机',
    shortName: 'Fujifilm C400',
    detail: '32mm · f/11 · 1/125s',
    accent: '#67ad94',
    image: presetC400,
    filmIds: ['c400'],
    defaultFilmId: 'c400',
    flashRange: '1–3 m',
  },
  {
    id: 'kodak-ec35-reusable',
    name: 'Kodak EC35',
    shortName: 'Kodak EC35',
    detail: '25mm · f/10 · 1/100s',
    accent: '#cf3f35',
    image: presetKodakEc35,
    filmIds: ['generic-100', 'gold-200', 'ultramax-400', 'generic-800'],
    defaultFilmId: 'ultramax-400',
  },
];

export const instantFilms: FilmOption[] = [
  { id: 'instax-mini-white', name: 'instax mini 白框相纸', shortName: '白框相纸', iso: 800, tint: '#f7f5ee', frame: 'white', image: instantFilmWhite, frameImage: instantFrameWhite },
  { id: 'instax-mini-black', name: 'instax mini 黑框相纸', shortName: '黑框相纸', iso: 800, tint: '#171918', frame: 'black', image: instantFilmBlack, frameImage: instantFrameBlack },
];

export const instantCameras: CameraPreset[] = [
  {
    id: 'fujifilm-instax-mini-12',
    name: 'Fujifilm instax mini 12',
    shortName: 'instax mini 12',
    detail: '自动曝光 · 自动闪光',
    accent: '#aacfd0',
    image: presetInstaxMini12,
    filmIds: instantFilms.map((film) => film.id),
    defaultFilmId: 'instax-mini-white',
  },
];
