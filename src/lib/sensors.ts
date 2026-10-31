import type { FakeGyroEngine } from "./fakeGyro";
import type { NativeSample, SensorStatus } from "./types";

type PermState = SensorStatus["permission"];

/**
 * Liga o motor às fontes de dados disponíveis:
 *  - `devicemotion` / `deviceorientationabsolute` do navegador (Android/iOS);
 *  - ponte nativa do APK (`window.NativeSensors`), que lê acelerômetro,
 *    giroscópio, magnetômetro e vetor de rotação direto do Android;
 *  - compensa a rotação da tela para os sensores nativos.
 */
export class SensorHub {
  permission: PermState = "unknown";
  lastError = "";
  private running = false;
  private screenAngle = 0;
  private nativeAttached = false;

  constructor(private engine: FakeGyroEngine) {
    this.readScreenAngle();
    if (typeof screen !== "undefined" && screen.orientation) {
      screen.orientation.addEventListener?.("change", this.readScreenAngle);
    }
  }

  private readScreenAngle = (): void => {
    let angle = 0;
    try {
      angle =
        (screen.orientation && typeof screen.orientation.angle === "number"
          ? screen.orientation.angle
          : (window as unknown as { orientation?: number }).orientation) || 0;
    } catch {
      angle = 0;
    }
    this.screenAngle = angle;
    this.engine.setScreenAngle(angle);
  };

  getScreenAngle(): number {
    return this.screenAngle;
  }

  hasNativeBridge(): boolean {
    return typeof window !== "undefined" && !!window.NativeSensors;
  }

  async start(): Promise<void> {
    if (this.running) return;
    this.running = true;

    // 1. permissões (iOS exige gesto do usuário)
    await this.requestPermission();

    // 2. sensores do navegador
    window.addEventListener("devicemotion", this.onDeviceMotion, true);
    window.addEventListener("deviceorientationabsolute", this.onAbsolute, true);

    // 3. ponte nativa do APK
    this.attachNative();
  }

  stop(): void {
    this.running = false;
    window.removeEventListener("devicemotion", this.onDeviceMotion, true);
    window.removeEventListener("deviceorientationabsolute", this.onAbsolute, true);
    if (this.nativeAttached) {
      try {
        window.NativeSensors?.stop();
      } catch {
        /* segue */
      }
      delete window.__onNativeSensors;
      this.nativeAttached = false;
    }
  }

  private async requestPermission(): Promise<void> {
    const need = (obj: unknown): boolean =>
      typeof (obj as { requestPermission?: unknown })?.requestPermission === "function";

    try {
      if (need(window.DeviceOrientationEvent)) {
        const r = await (window.DeviceOrientationEvent as unknown as {
          requestPermission: () => Promise<string>;
        }).requestPermission();
        this.permission = r === "granted" ? "granted" : "denied";
      } else if (typeof window.DeviceOrientationEvent === "undefined") {
        this.permission = "unsupported";
      } else {
        this.permission = "granted";
      }
    } catch (e) {
      this.lastError = String(e);
      this.permission = "denied";
    }

    try {
      if (need(window.DeviceMotionEvent)) {
        await (window.DeviceMotionEvent as unknown as {
          requestPermission: () => Promise<string>;
        }).requestPermission();
      }
    } catch {
      /* alguns navegadores não implementam; os eventos chegam mesmo assim */
    }
  }

  private attachNative(): void {
    if (this.nativeAttached || !this.hasNativeBridge()) return;
    window.__onNativeSensors = (s: NativeSample) => this.onNative(s);
    try {
      window.NativeSensors!.setRate(120);
      window.NativeSensors!.start();
      this.nativeAttached = true;
    } catch (e) {
      this.lastError = String(e);
    }
  }

  private onNative = (s: NativeSample): void => {
    if (s.a) this.engine.pushAccel(s.a[0], s.a[1], s.a[2], s.t, true);
    if (s.g) this.engine.pushGyro(s.g[0], s.g[1], s.g[2], s.t);
    if (s.m) this.engine.pushMag(s.m[0], s.m[1], s.m[2], s.t);
    if (s.rv) this.engine.pushRotationVector(s.rv as [number, number, number, number], s.t);
  };

  private onDeviceMotion = (e: DeviceMotionEvent): void => {
    const a = e.accelerationIncludingGravity;
    if (a && (a.x !== null || a.y !== null || a.z !== null)) {
      this.engine.pushBrowserAccel(a.x ?? 0, a.y ?? 0, a.z ?? 0, performance.now());
    }
    const r = e.rotationRate;
    if (r && (r.alpha !== null || r.beta !== null || r.gamma !== null)) {
      this.engine.pushBrowserGyro(r.alpha ?? 0, r.beta ?? 0, r.gamma ?? 0, performance.now());
    }
  };

  private onAbsolute = (e: DeviceOrientationEvent): void => {
    if (e.alpha === null || e.beta === null || e.gamma === null) return;
    this.engine.pushAbsolute(e.alpha, e.beta, e.gamma, performance.now());
  };
}
