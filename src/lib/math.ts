/* ------------------------------------------------------------------
 *  Matemática de atitude (quaternions) + conversão para alpha/beta/gamma
 *  Convenções (idênticas à especificação W3C DeviceOrientation):
 *
 *  Frame do dispositivo: X = direita da tela, Y = topo da tela,
 *                        Z = saída da tela (em direção ao usuário).
 *  Frame da Terra:       X = Leste, Y = Norte, Z = para cima.
 *
 *  R (device -> terra) = Rz(alpha) * Rx(beta) * Ry(gamma)
 *
 *  O acelerômetro em repouso mede accelerationIncludingGravity ~= 9,81 * (R^T * (0,0,1)),
 *  ou seja, a direção "para cima" do mundo expressa no frame do aparelho.
 * ------------------------------------------------------------------ */

export type Vec3 = [number, number, number];
/** quaternion [x, y, z, w] */
export type Quat = [number, number, number, number];

export const D2R = Math.PI / 180;
export const R2D = 180 / Math.PI;
export const TAU = Math.PI * 2;

export const v3 = (x = 0, y = 0, z = 0): Vec3 => [x, y, z];

export function dot(a: Vec3, b: Vec3): number {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

export function cross(a: Vec3, b: Vec3): Vec3 {
  return [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  ];
}

export function norm(a: Vec3): number {
  return Math.hypot(a[0], a[1], a[2]);
}

export function normalize(a: Vec3, fallback: Vec3 = [0, 0, 1]): Vec3 {
  const n = norm(a);
  if (!isFinite(n) || n < 1e-9) return [fallback[0], fallback[1], fallback[2]];
  return [a[0] / n, a[1] / n, a[2] / n];
}

export function scale(a: Vec3, s: number): Vec3 {
  return [a[0] * s, a[1] * s, a[2] * s];
}

export function sub(a: Vec3, b: Vec3): Vec3 {
  return [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
}

export function add(a: Vec3, b: Vec3): Vec3 {
  return [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
}

export function clamp(x: number, lo: number, hi: number): number {
  return x < lo ? lo : x > hi ? hi : x;
}

export function wrap360(a: number): number {
  let x = a % 360;
  if (x < 0) x += 360;
  return x;
}

export function wrap180(a: number): number {
  let x = wrap360(a);
  if (x > 180) x -= 360;
  return x;
}

/* ------------------------------ quaternions ------------------------------ */

export const qIdentity = (): Quat => [0, 0, 0, 1];

export function qNormalize(q: Quat): Quat {
  const n = Math.hypot(q[0], q[1], q[2], q[3]);
  if (!isFinite(n) || n < 1e-12) return qIdentity();
  return [q[0] / n, q[1] / n, q[2] / n, q[3] / n];
}

/** qa * qb (composição de rotações) */
export function qMul(a: Quat, b: Quat): Quat {
  const [ax, ay, az, aw] = a;
  const [bx, by, bz, bw] = b;
  return [
    aw * bx + ax * bw + ay * bz - az * by,
    aw * by - ax * bz + ay * bw + az * bx,
    aw * bz + ax * by - ay * bx + az * bw,
    aw * bw - ax * bx - ay * by - az * bz,
  ];
}

export function qConj(q: Quat): Quat {
  return [-q[0], -q[1], -q[2], q[3]];
}

/** quaternion a partir de uma rotação pequena (parte vetorial = eixo * ângulo) */
export function qFromSmallAngle(w: Vec3): Quat {
  const th = norm(w);
  if (th < 1e-12) return qIdentity();
  const s = Math.sin(th / 2) / th;
  return [w[0] * s, w[1] * s, w[2] * s, Math.cos(th / 2)];
}

export function qFromAxisAngle(axis: Vec3, angle: number): Quat {
  const a = normalize(axis);
  const s = Math.sin(angle / 2);
  return [a[0] * s, a[1] * s, a[2] * s, Math.cos(angle / 2)];
}

/** rotaciona um vetor por q (rotação ativa) */
export function qRotate(q: Quat, v: Vec3): Vec3 {
  const [x, y, z, w] = q;
  const [vx, vy, vz] = v;
  const tx = 2 * (y * vz - z * vy);
  const ty = 2 * (z * vx - x * vz);
  const tz = 2 * (x * vy - y * vx);
  return [
    vx + w * tx + (y * tz - z * ty),
    vy + w * ty + (z * tx - x * tz),
    vz + w * tz + (x * ty - y * tx),
  ];
}

/** matriz de rotação 3x3 (row-major) device -> terra */
export function qToMat3(q: Quat): number[][] {
  const [x, y, z, w] = q;
  return [
    [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
    [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
    [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
  ];
}

export function qFromMat3(m: number[][]): Quat {
  const t = m[0][0] + m[1][1] + m[2][2];
  let x = 0, y = 0, z = 0, w = 1;
  if (t > 0) {
    const s = Math.sqrt(t + 1) * 2;
    w = 0.25 * s;
    x = (m[2][1] - m[1][2]) / s;
    y = (m[0][2] - m[2][0]) / s;
    z = (m[1][0] - m[0][1]) / s;
  } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
    const s = Math.sqrt(1 + m[0][0] - m[1][1] - m[2][2]) * 2;
    w = (m[2][1] - m[1][2]) / s;
    x = 0.25 * s;
    y = (m[0][1] + m[1][0]) / s;
    z = (m[0][2] + m[2][0]) / s;
  } else if (m[1][1] > m[2][2]) {
    const s = Math.sqrt(1 + m[1][1] - m[0][0] - m[2][2]) * 2;
    w = (m[0][2] - m[2][0]) / s;
    x = (m[0][1] + m[1][0]) / s;
    y = 0.25 * s;
    z = (m[1][2] + m[2][1]) / s;
  } else {
    const s = Math.sqrt(1 + m[2][2] - m[0][0] - m[1][1]) * 2;
    w = (m[1][0] - m[0][1]) / s;
    x = (m[0][2] + m[2][0]) / s;
    y = (m[1][2] + m[2][1]) / s;
    z = 0.25 * s;
  }
  return qNormalize([x, y, z, w]);
}

export function qSlerp(a: Quat, b: Quat, t: number): Quat {
  let [bx, by, bz, bw] = b;
  let cos = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw;
  if (cos < 0) {
    bx = -bx; by = -by; bz = -bz; bw = -bw;
    cos = -cos;
  }
  if (cos > 0.9995) {
    return qNormalize([
      a[0] + (bx - a[0]) * t,
      a[1] + (by - a[1]) * t,
      a[2] + (bz - a[2]) * t,
      a[3] + (bw - a[3]) * t,
    ]);
  }
  const th = Math.acos(clamp(cos, -1, 1));
  const s = Math.sin(th);
  const wa = Math.sin((1 - t) * th) / s;
  const wb = Math.sin(t * th) / s;
  return [
    a[0] * wa + bx * wb,
    a[1] * wa + by * wb,
    a[2] * wa + bz * wb,
    a[3] * wa + bw * wb,
  ];
}

/** R = Rz(alpha) * Rx(beta) * Ry(gamma)  ->  quaternion device->terra */
export function qFromAbg(alphaDeg: number, betaDeg: number, gammaDeg: number): Quat {
  const a = alphaDeg * D2R * 0.5;
  const b = betaDeg * D2R * 0.5;
  const g = gammaDeg * D2R * 0.5;
  const ca = Math.cos(a), sa = Math.sin(a);
  const cb = Math.cos(b), sb = Math.sin(b);
  const cg = Math.cos(g), sg = Math.sin(g);
  const qz: Quat = [0, 0, sa, ca];
  const qx: Quat = [sb, 0, 0, cb];
  const qy: Quat = [0, sg, 0, cg];
  return qNormalize(qMul(qMul(qz, qx), qy));
}

export interface Abg {
  alpha: number;
  beta: number;
  gamma: number;
}

/**
 * Extrai (alpha, beta, gamma) em GRAUS de um quaternion device->terra.
 * alpha em [0,360), beta em [-180,180], gamma em [-90,90] (podendo passar
 * de ±90 perto do "gimbal lock", como nos aparelhos reais).
 */
export function qToAbg(q: Quat): Abg {
  const m = qToMat3(qNormalize(q));
  const sinB = m[2][1];
  const cosB = Math.hypot(m[0][1], m[1][1]);
  const beta = Math.atan2(sinB, cosB) * R2D;
  const gamma = Math.atan2(-m[2][0], m[2][2]) * R2D;
  const alpha = Math.atan2(-m[0][1], m[1][1]) * R2D;
  return { alpha: wrap360(alpha), beta: wrap180(beta), gamma: wrap180(gamma) };
}

/** Representação alternativa no "gimbal lock": (a+180, 180-b, g+180) */
export function abgAlt(a: Abg): Abg {
  return {
    alpha: wrap360(a.alpha + 180),
    beta: wrap180(180 - a.beta),
    gamma: wrap180(a.gamma + 180),
  };
}

/** Escolhe a representação mais próxima da anterior (evita saltos em beta ±90) */
export function abgContinuous(prev: Abg | null, cur: Abg): Abg {
  if (!prev) return cur;
  const alt = abgAlt(cur);
  return angDist(prev, alt) < angDist(prev, cur) ? alt : cur;
}

function angDist(a: Abg, b: Abg): number {
  const da = Math.abs(wrap180(a.alpha - b.alpha));
  const db = Math.abs(wrap180(a.beta - b.beta));
  const dg = Math.abs(wrap180(a.gamma - b.gamma));
  return da + db + dg;
}

/**
 * Atitude (quaternion) estimada apenas pela gravidade — usada como
 * "pseudo-giroscópio": a derivada dessa atitude fornece uma velocidade
 * angular utilizável em frequências altas.
 */
export function qFromGravity(gDev: Vec3): Quat {
  // queremos q tal que qRotate(qConj(q), [0,0,1]) == gDev  =>  qRotate(q, gDev) == [0,0,1]
  const g = normalize(gDev, [0, 0, 1]);
  const target: Vec3 = [0, 0, 1];
  const axis = cross(g, target);
  const s = norm(axis);
  const c = dot(g, target);
  if (s < 1e-9) {
    if (c > 0) return qIdentity();
    return qFromAxisAngle([1, 0, 0], Math.PI);
  }
  return qFromAxisAngle(axis, Math.atan2(s, c));
}

/** Velocidade angular (rad/s, frame do dispositivo) que leva qa até qb */
export function qAngularRate(qa: Quat, qb: Quat, dt: number): Vec3 {
  if (dt <= 1e-6) return [0, 0, 0];
  const dq = qMul(qb, qConj(qa));
  const n = Math.hypot(dq[0], dq[1], dq[2], dq[3]);
  if (n < 1e-9) return [0, 0, 0];
  const th = 2 * Math.atan2(n, dq[3]);
  const ax = dq[0] / n, ay = dq[1] / n, az = dq[2] / n;
  return [(ax * th) / dt, (ay * th) / dt, (az * th) / dt];
}
