import * as M from "./math";
import type {
  FakeGyroConfig,
  MotionSample,
  OrientationSample,
  PositionalSample,
  SensorStatus,
} from "./types";
import { DEFAULT_CONFIG, EMPTY_STATUS } from "./types";
import type { Quat, Vec3 } from "./math";

const G = 9.80665;

interface RateWin {
  n: number;
  t0: number;
  rate: number;
}

/**
 * Motor de atitude: funde acelerômetro (+ giroscópio/magnetômetro quando
 * existirem) e produz alpha/beta/gamma prontos para um `DeviceOrientationEvent`
 * sintético. Também estima o 6DoF posicional por dead-reckoning.
 *
 * O "giroscópio emulado por acelerômetro" funciona assim:
 *   1. a gravidade medida pelo acelerômetro dá uma atitude absoluta (roll/pitch);
 *   2. a derivada dessa atitude é usada como pseudo-giroscópio (resposta rápida);
 *   3. um filtro complementar (Mahony) junta os dois e elimina o atraso;
 *   4. a guinada (yaw) vem do magnetômetro, ou é estimada pela rotação da
 *      aceleração horizontal, ou fica a cargo do arraste do próprio jogo.
 */
export class FakeGyroEngine {
  cfg: FakeGyroConfig = { ...DEFAULT_CONFIG };

  /* ------------------------------ estado ------------------------------ */
  private q: Quat = M.qIdentity();
  private acc: Vec3 | null = null;
  private accT = 0;
  private gyro: Vec3 | null = null; // rad/s (frame da tela)
  private gyroT = 0;
  private mag: Vec3 | null = null;
  private magT = 0;
  private rv: Quat | null = null; // atitude absoluta nativa (frame da tela)
  private rvT = 0;
  private rvRate: Vec3 = [0, 0, 0];
  private rvPrev: Quat | null = null;
  private rvPrevT = 0;
  private absAbg: M.Abg | null = null;
  private absT = 0;
  private native = false;

  private yawPseudo = 0;
  private lastYawRate = 0;
  private lastHorizAngle: number | null = null;
  private lastHorizT = 0;

  private prevAbg: M.Abg | null = null;
  private refAbg: M.Abg | null = null;
  private yawOffset = 0;
  private screenAngle = 0;

  private lastTick = 0;
  private accWin: RateWin = { n: 0, t0: 0, rate: 0 };
  private gyroWin: RateWin = { n: 0, t0: 0, rate: 0 };
  private motionT = 0;

  /* --------------------------- 6DoF posicional --------------------------- */
  private pos = { x: 0, y: 0, z: 0 };
  private vel = { x: 0, y: 0, z: 0 };
  private peakY = 0;
  private crouch = false;
  private crouchT = 0;
  private lastPositional: PositionalSample | null = null;
  private lastMotion: MotionSample | null = null;

  private freshAcc = false;
  private freshGyro = false;
  private freshMag = false;
  private freshRv = false;
  private freshAbs = false;

  /* ------------------------------ entradas ------------------------------ */

  /** Acelerômetro incluindo gravidade (m/s²), frame natural do aparelho. */
  pushAccel(x: number, y: number, z: number, t: number, native = false): void {
    this.acc = this.toScreen([x, y, z]);
    this.accT = t;
    this.native = this.native || native;
    this.accWin.n++;
    this.freshAcc = true;
  }

  /** Giroscópio (rad/s), frame natural do aparelho. */
  pushGyro(x: number, y: number, z: number, t: number): void {
    this.gyro = this.toScreen([x, y, z]);
    this.gyroT = t;
    this.gyroWin.n++;
    this.freshGyro = true;
  }

  /** Magnetômetro (µT). */
  pushMag(x: number, y: number, z: number, t: number): void {
    this.mag = this.toScreen([x, y, z]);
    this.magT = t;
    this.freshMag = true;
  }

  /** Vetor de rotação (quaternion device->terra, frame natural do aparelho). */
  pushRotationVector(q: Quat, t: number): void {
    const qs = M.qFromAxisAngle([0, 0, 1], -this.screenAngle * M.D2R);
    const q2 = M.qNormalize(M.qMul(q, qs));
    if (this.rvPrev && t > this.rvPrevT) {
      this.rvRate = M.qAngularRate(this.rvPrev, q2, (t - this.rvPrevT) / 1000);
    }
    this.rvPrev = q2;
    this.rvPrevT = t;
    this.rv = q2;
    this.rvT = t;
    this.native = true;
    this.freshRv = true;
  }

  /** Atitude absoluta do navegador (deviceorientationabsolute), em graus. */
  pushAbsolute(alpha: number, beta: number, gamma: number, t: number): void {
    this.absAbg = { alpha, beta, gamma };
    this.absT = t;
    this.freshAbs = true;
  }

  /** Aceleração já no frame da tela, como o navegador entrega em `devicemotion`. */
  pushBrowserAccel(x: number, y: number, z: number, t: number): void {
    this.acc = [x, y, z];
    this.accT = t;
    this.accWin.n++;
    this.freshAcc = true;
  }

  /** `rotationRate` do navegador, em graus/s. */
  pushBrowserGyro(x: number, y: number, z: number, t: number): void {
    this.gyro = [x * M.D2R, y * M.D2R, z * M.D2R];
    this.gyroT = t;
    this.gyroWin.n++;
    this.freshGyro = true;
  }

  /** Compensa a rotação da tela: v_tela = Rz(ângulo) · v_sensor. */
  private toScreen(v: Vec3): Vec3 {
    const a = this.screenAngle * M.D2R;
    if (!a) return v;
    const c = Math.cos(a), s = Math.sin(a);
    return [c * v[0] - s * v[1], s * v[0] + c * v[1], v[2]];
  }

  setScreenAngle(deg: number): void {
    this.screenAngle = ((deg % 360) + 360) % 360;
  }

  /* ------------------------------ controle ------------------------------ */

  reset(): void {
    this.q = M.qIdentity();
    this.prevAbg = null;
    this.refAbg = null;
    this.yawOffset = 0;
    this.yawPseudo = 0;
    this.lastYawRate = 0;
    this.lastHorizAngle = null;
    this.pos = { x: 0, y: 0, z: 0 };
    this.vel = { x: 0, y: 0, z: 0 };
    this.peakY = 0;
    this.crouch = false;
    this.rvPrev = null;
  }

  /** Zera a guinada: a direção atual passa a ser o "frente" da visão. */
  recenter(): void {
    this.yawOffset = M.qToAbg(this.q).alpha;
    this.refAbg = null;
    this.prevAbg = null;
  }

  /* ------------------------------- fusão ------------------------------- */

  /**
   * Avança o filtro e devolve a amostra de orientação, ou null quando ainda
   * não existe acelerômetro suficiente.
   */
  tick(now: number): OrientationSample | null {
    const dt = this.lastTick ? M.clamp((now - this.lastTick) / 1000, 0.0005, 0.1) : 0.016;
    this.lastTick = now;
    this.updateRates(now);
    if (!this.acc) return null;

    const cfg = this.cfg;
    const g = M.normalize(this.acc, [0, 0, 1]);
    const useNative = this.wantNative();

    /* 1. velocidade angular de predição */
    let w: Vec3;
    let source: string;
    if (useNative && this.rv && now - this.rvT < 250) {
      w = [this.rvRate[0], this.rvRate[1], this.rvRate[2]];
      source = "vetor de rotação nativo (9DoF)";
    } else if (useNative && this.gyro && now - this.gyroT < 250) {
      w = [this.gyro[0], this.gyro[1], this.gyro[2]];
      source = "giroscópio real + acelerômetro";
    } else {
      // GIROSCÓPIO EMULADO: derivada da atitude medida pelo acelerômetro
      const gq = M.qFromGravity(g);
      const r = M.qAngularRate(this.q, gq, dt);
      const k = M.clamp(cfg.pseudoGain, 0, 4);
      w = [r[0] * k, r[1] * k, r[2] * k];
      source = "acelerômetro → giro emulado";
    }
    this.lastSource = source;

    /* 2. correção pela gravidade (filtro complementar / Mahony) */
    const gPred = M.qRotate(M.qConj(this.q), [0, 0, 1]);
    const kp = 2 + 12 * (1 - M.clamp(cfg.smoothing, 0, 1));
    const corr = M.cross(g, gPred);
    w = [w[0] + kp * corr[0], w[1] + kp * corr[1], w[2] + kp * corr[2]];

    /* 3. correção pelo magnetômetro (guinada absoluta) */
    let yawFromMag = false;
    const wantMag = cfg.yawSource === "mag" || (cfg.yawSource === "auto" && !!this.mag);
    if (wantMag && this.mag && now - this.magT < 500) {
      const nv = M.normalize(gPred);
      const m = M.normalize(this.mag);
      const mh = M.normalize(M.sub(m, M.scale(nv, M.dot(m, nv))));
      const nPred = M.qRotate(M.qConj(this.q), [0, 1, 0]);
      const nh = M.normalize(M.sub(nPred, M.scale(nv, M.dot(nPred, nv))));
      const err = M.dot(M.cross(mh, nh), nv); // erro angular sobre a vertical
      const ky = 1.2;
      w = [w[0] + ky * err * nv[0], w[1] + ky * err * nv[1], w[2] + ky * err * nv[2]];
      yawFromMag = true;
    }

    /* 4. integra a rotação */
    this.q = M.qNormalize(M.qMul(this.q, M.qFromSmallAngle([w[0] * dt, w[1] * dt, w[2] * dt])));

    /* 5. guinada pseudo pelo acelerômetro (experimental) */
    if (this.wantPseudoYaw()) {
      const dy = this.estimateYawFromAccel(g, now);
      if (dy !== 0) {
        const axis = gPred; // vertical do mundo no frame do aparelho
        this.q = M.qNormalize(M.qMul(this.q, M.qFromAxisAngle(axis, dy * dt)));
      }
    }

    /* 6. dead-reckoning posicional (6DoF falso) */
    this.updatePositional(g, dt, now);

    /* 7. saída em alpha/beta/gamma */
    let abg = M.qToAbg(this.q);
    abg = M.abgContinuous(this.prevAbg, abg);
    this.prevAbg = abg;
    if (!this.refAbg) this.refAbg = { ...abg };
    const sens = M.clamp(cfg.sensitivity, 0.1, 4);
    const out = this.applySensitivity(abg, sens);
    out.alpha = M.wrap360(out.alpha - this.yawOffset);

    if (now - this.motionT > 12) {
      this.motionT = now;
      this.lastMotion = this.buildMotion(dt);
    }

    const absolute =
      yawFromMag ||
      (useNative && (!!this.rv || (!!this.absAbg && now - this.absT < 500)));
    return {
      type: "fg:orient",
      alpha: out.alpha,
      beta: out.beta,
      gamma: out.gamma,
      absolute,
      yawRate: this.lastYawRate,
    };
  }

  private lastSource = "sem dados";

  private wantNative(): boolean {
    if (this.cfg.mode === "native") return true;
    if (this.cfg.mode === "accel") return false;
    return !!(this.rv || this.gyro); // auto
  }

  private wantPseudoYaw(): boolean {
    const c = this.cfg.yawSource;
    if (c === "pseudo") return true;
    if (c === "none") return false;
    // auto/mag: só usa pseudo quando não há magnetômetro algum
    return !this.mag && !this.rv;
  }

  private applySensitivity(a: M.Abg, s: number): M.Abg {
    const r = this.refAbg ?? a;
    return {
      alpha: r.alpha + M.wrap180(a.alpha - r.alpha) * s,
      beta: M.clamp(r.beta + (a.beta - r.beta) * s, -180, 180),
      gamma: M.clamp(r.gamma + (a.gamma - r.gamma) * s, -90, 90),
    };
  }

  /**
   * Guinada (rad/s sobre a vertical) estimada pela rotação da aceleração
   * horizontal no plano do aparelho. É uma heurística: o acelerômetro não
   * observa o yaw diretamente, mas quando a mão gira o aparelho a direção da
   * aceleração horizontal gira junto — e isso dá um "giro vertical" utilizável.
   */
  private estimateYawFromAccel(g: Vec3, now: number): number {
    if (!this.acc) return 0;
    const lin = M.sub(this.acc, M.scale(g, G));
    const ex: Vec3 = [1, 0, 0];
    const ey: Vec3 = [0, 1, 0];
    const e = Math.abs(M.dot(g, ex)) < Math.abs(M.dot(g, ey)) ? ex : ey;
    const u = M.normalize(M.cross(g, e));
    const v = M.cross(g, u);
    const lu = M.dot(lin, u);
    const lv = M.dot(lin, v);
    const mag = Math.hypot(lu, lv);
    const ang = Math.atan2(lv, lu);

    let rate = 0;
    if (this.lastHorizAngle !== null && now > this.lastHorizT) {
      const dtt = (now - this.lastHorizT) / 1000;
      let d = ang - this.lastHorizAngle;
      while (d > Math.PI) d -= M.TAU;
      while (d < -Math.PI) d += M.TAU;
      if (dtt > 0.002 && dtt < 0.2) rate = d / dtt;
    }
    this.lastHorizAngle = ang;
    this.lastHorizT = now;

    const active = mag > 0.6;
    const target = active ? -rate * M.clamp(this.cfg.yawGain, -4, 4) : 0;
    this.yawPseudo += (target - this.yawPseudo) * (active ? 0.35 : 0.06);
    this.lastYawRate = this.yawPseudo;
    return this.yawPseudo;
  }

  /** Dead-reckoning: integra a aceleração linear com atualização de velocidade zero. */
  private updatePositional(g: Vec3, dt: number, now: number): PositionalSample | null {
    if (!this.cfg.positional || !this.acc) {
      this.lastPositional = null;
      return null;
    }
    const lin = M.sub(this.acc, M.scale(g, G));
    const le = M.qRotate(this.q, lin);
    const gate = M.clamp(this.cfg.noiseGate, 0.05, 3);
    if (M.norm(lin) > gate) {
      this.vel.x += le[0] * dt;
      this.vel.y += le[1] * dt;
      this.vel.z += le[2] * dt;
    } else {
      const d = Math.exp(-dt * 5); // zera a velocidade devagar (tira o drift)
      this.vel.x *= d;
      this.vel.y *= d;
      this.vel.z *= d;
    }
    const vm = Math.hypot(this.vel.x, this.vel.z);
    if (vm > 3) {
      this.vel.x *= 3 / vm;
      this.vel.z *= 3 / vm;
    }
    const sc = M.clamp(this.cfg.walkScale, 0.1, 5);
    const dx = this.vel.x * dt * sc;
    const dz = this.vel.z * dt * sc;
    this.pos.x += dx;
    this.pos.y += this.vel.y * dt;
    this.pos.z += dz;
    this.peakY = Math.max(this.peakY, this.pos.y);

    const want = this.pos.y < this.peakY - 0.25;
    if (want !== this.crouch && now - this.crouchT > 250) {
      this.crouch = want;
      this.crouchT = now;
    }
    this.lastPositional = {
      type: "fg:pos6",
      dx,
      dz,
      dy: this.vel.y * dt,
      crouch: this.crouch,
    };
    return this.lastPositional;
  }

  private buildMotion(dt: number): MotionSample {
    const a = this.acc ?? [0, 0, G];
    const gr = this.gyro;
    const yr = this.lastYawRate;
    return {
      type: "fg:motion",
      ax: a[0],
      ay: a[1],
      az: a[2],
      rx: gr ? gr[0] * M.R2D : 0,
      ry: gr ? gr[1] * M.R2D : yr * M.R2D,
      rz: gr ? gr[2] * M.R2D : 0,
      interval: Math.max(1, Math.round(dt * 1000)),
    };
  }

  getMotion(): MotionSample | null {
    return this.lastMotion;
  }

  getPositional(): PositionalSample | null {
    return this.lastPositional;
  }

  /* ---------------------------- estatísticas ---------------------------- */

  private updateRates(now: number): void {
    const bump = (w: RateWin) => {
      if (!w.t0) w.t0 = now;
      if (now - w.t0 >= 500) {
        w.rate = (w.n * 1000) / (now - w.t0);
        w.n = 0;
        w.t0 = now;
      }
    };
    bump(this.accWin);
    bump(this.gyroWin);
  }

  status(now: number): SensorStatus {
    const accelAlive = !!this.acc && now - this.accT < 700;
    const gyroAlive = !!this.gyro && now - this.gyroT < 700;
    const magAlive = !!this.mag && now - this.magT < 900;
    const rvAlive = !!this.rv && now - this.rvT < 700;
    return {
      ...EMPTY_STATUS,
      hasAccel: accelAlive,
      hasGyro: gyroAlive || rvAlive,
      hasMag: magAlive || rvAlive || !!this.absAbg,
      native: this.native,
      accelRate: Math.round(this.accWin.rate),
      gyroRate: Math.round(this.gyroWin.rate),
      source: accelAlive ? this.lastSource : "sem dados",
      screenAngle: this.screenAngle,
    };
  }

  /** Consome as flags de "chegou dado novo" (usado pelo launcher). */
  consumeFlags(): { acc: boolean; gyro: boolean; mag: boolean; rv: boolean; abs: boolean } {
    const f = {
      acc: this.freshAcc,
      gyro: this.freshGyro,
      mag: this.freshMag,
      rv: this.freshRv,
      abs: this.freshAbs,
    };
    this.freshAcc = false;
    this.freshGyro = false;
    this.freshMag = false;
    this.freshRv = false;
    this.freshAbs = false;
    return f;
  }
}
