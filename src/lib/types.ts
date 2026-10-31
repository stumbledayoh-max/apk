/** Tipos e protocolo compartilhados entre o launcher, os jogos e o app Android. */

/* ---------------------------- configuração ---------------------------- */

/** Como a atitude é estimada. */
export type GyroMode =
  /** escolhe automaticamente a melhor fonte disponível */
  | "auto"
  /** GIROS CÓPIO EMULADO: apenas acelerômetro (filtro complementar + pseudo-giro) */
  | "accel"
  /** sensores nativos reais (giroscópio / vetor de rotação) quando existirem */
  | "native";

/** De onde vem a guinada (yaw / alpha), que o acelerômetro não observa. */
export type YawSource =
  | "auto"
  /** magnetômetro / vetor de rotação absoluto */
  | "mag"
  /** guinada estimada pela aceleração horizontal (experimental) */
  | "pseudo"
  /** sem guinada automática (use o arraste do próprio jogo) */
  | "none";

export interface FakeGyroConfig {
  mode: GyroMode;
  yawSource: YawSource;
  /** multiplica a rotação em relação ao ponto de referência */
  sensitivity: number;
  /** 0 = encostado no sensor (rápido e ruidoso) · 1 = muito suave */
  smoothing: number;
  /** ganho do pseudo-giroscópio (derivada da atitude vinda da gravidade) */
  pseudoGain: number;
  /** ganho da guinada pseudo (acelerômetro) */
  yawGain: number;
  /** escala do deslocamento no 6DoF posicional */
  walkScale: number;
  /** limiar de ruído linear (m/s²) para o dead-reckoning */
  noiseGate: number;
  /** liga o 6DoF posicional (andar de verdade pelo acelerômetro) */
  positional: boolean;
  /** liga o giroscópio do próprio jogo quando os dados começam a chegar */
  autoEnable: boolean;
  /** permite o uso da câmera (hand tracking do Gorilla Rush) */
  camera: boolean;
}

export const DEFAULT_CONFIG: FakeGyroConfig = {
  mode: "auto",
  yawSource: "auto",
  sensitivity: 1,
  smoothing: 0.35,
  pseudoGain: 1,
  yawGain: 1,
  walkScale: 1,
  noiseGate: 0.35,
  positional: false,
  autoEnable: true,
  camera: true,
};

/* ------------------------------ sensores ------------------------------ */

export interface SensorStatus {
  /** acelerômetro recebendo dados */
  hasAccel: boolean;
  /** giroscópio real recebendo dados */
  hasGyro: boolean;
  /** magnetômetro / vetor de rotação absoluto */
  hasMag: boolean;
  /** ponte nativa do Android ativa */
  native: boolean;
  /** permissão do navegador */
  permission: "unknown" | "granted" | "denied" | "unsupported";
  /** amostras por segundo */
  accelRate: number;
  gyroRate: number;
  /** texto legível da fonte em uso */
  source: string;
  /** ângulo da tela (graus) usado na compensação sensor→tela */
  screenAngle: number;
}

export const EMPTY_STATUS: SensorStatus = {
  hasAccel: false,
  hasGyro: false,
  hasMag: false,
  native: false,
  permission: "unknown",
  accelRate: 0,
  gyroRate: 0,
  source: "sem dados",
  screenAngle: 0,
};

/** Amostra de atitude pronta para ser entregue ao jogo. */
export interface OrientationSample {
  type: "fg:orient";
  alpha: number;
  beta: number;
  gamma: number;
  absolute: boolean;
  /** velocidade de guinada em rad/s (para HUD) */
  yawRate: number;
}

export interface MotionSample {
  type: "fg:motion";
  /** aceleração incluindo gravidade (m/s²), já no frame da tela */
  ax: number;
  ay: number;
  az: number;
  /** velocidade angular (graus/s) — sintetizada quando não há giroscópio */
  rx: number;
  ry: number;
  rz: number;
  interval: number;
}

/** 6DoF posicional estimado por dead-reckoning (metros). */
export interface PositionalSample {
  type: "fg:pos6";
  dx: number;
  dz: number;
  dy: number;
  crouch: boolean;
}

export type BridgeMessage =
  | OrientationSample
  | MotionSample
  | PositionalSample
  | { type: "fg:config"; config: Partial<FakeGyroConfig>; streaming?: boolean }
  | { type: "fg:recenter" }
  | { type: "fg:reset" }
  | { type: "fg:ping" };

/** Respostas que o script injetado nos jogos envia de volta. */
export type BridgeReply =
  | { type: "fg:ready"; version: string; game: string; gyroApi: boolean; positionalApi: boolean }
  | { type: "fg:pong"; t: number }
  | { type: "fg:log"; text: string };

/* ------------------------- ponte nativa (APK) ------------------------- */

/**
 * Objeto injetado pelo app Android (`@JavascriptInterface`).
 * O WebView não entrega `deviceorientation`/`devicemotion` de forma confiável,
 * então lemos os sensores nativamente e empurramos para o JS.
 * Todas as unidades são "cruas" (frame natural do aparelho) — a compensação
 * de rotação de tela é feita aqui no JS.
 */
export interface NativeSensors {
  /** inicia a leitura dos sensores */
  start(): void;
  /** para a leitura */
  stop(): void;
  /** define a taxa de amostragem desejada (Hz) */
  setRate(hz: number): void;
  /** true se o aparelho tem giroscópio */
  hasGyro(): boolean;
  /** true se tem magnetômetro */
  hasMag(): boolean;
  /** true se tem vetor de rotação (graus de liberdade completos) */
  hasRotationVector(): boolean;
  /** nome do aparelho / versão do bridge */
  info(): string;
}

/** Dados empurrados pelo Android via `window.__onNativeSensors`. */
export interface NativeSample {
  /** acelerômetro (m/s², incluindo gravidade) */
  a?: [number, number, number];
  /** giroscópio (rad/s) */
  g?: [number, number, number];
  /** magnetômetro (µT) */
  m?: [number, number, number];
  /** vetor de rotação como quaternion [x,y,z,w] */
  rv?: [number, number, number, number];
  /** timestamp em ms */
  t: number;
}

declare global {
  interface Window {
    /** presente apenas dentro do APK Android */
    NativeSensors?: NativeSensors;
    /** callback chamado pelo Android a cada amostra */
    __onNativeSensors?: (s: NativeSample) => void;
    /** injetado nos jogos (public/gyro-inject.js) */
    FakeGyro?: FakeGyroGameApi;
  }
}

/** API que o script injetado nos jogos expõe para o launcher. */
export interface FakeGyroGameApi {
  /** versão do bridge */
  version: string;
  /** sensores que o jogo está recebendo do launcher */
  streaming: boolean;
  /** liga/desliga o envio de eventos sintéticos */
  setStreaming(on: boolean): void;
  /** aplica uma configuração */
  setConfig(cfg: Partial<FakeGyroConfig>): void;
  /** recentraliza a visão */
  recenter(): void;
  /** tenta ligar o giroscópio do próprio jogo (quando ele tem essa opção) */
  enableGameGyro(): boolean;
  /** liga o 6DoF posicional no jogo (CraftVR) */
  setPositional(on: boolean): boolean;
}
