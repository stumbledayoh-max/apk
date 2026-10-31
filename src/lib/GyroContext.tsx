import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { FakeGyroEngine } from "./fakeGyro";
import { SensorHub } from "./sensors";
import type {
  BridgeMessage,
  BridgeReply,
  FakeGyroConfig,
  MotionSample,
  OrientationSample,
  PositionalSample,
  SensorStatus,
} from "./types";
import { DEFAULT_CONFIG, EMPTY_STATUS } from "./types";

interface GyroContextValue {
  engine: FakeGyroEngine;
  hub: SensorHub;
  status: SensorStatus;
  config: FakeGyroConfig;
  sample: OrientationSample | null;
  motion: MotionSample | null;
  positional: PositionalSample | null;
  /** sensores ligados */
  active: boolean;
  /** enviando eventos sintéticos para o jogo */
  streaming: boolean;
  /** jogo respondeu ao bridge */
  gameReady: boolean;
  gameInfo: string;
  start(): Promise<void>;
  stop(): void;
  setConfig(patch: Partial<FakeGyroConfig>): void;
  recenter(): void;
  resetView(): void;
  setStreaming(on: boolean): void;
  attachFrame(f: HTMLIFrameElement | null): void;
  send(msg: BridgeMessage): void;
  /** usado pelo simulador de desktop */
  simulate(ax: number, ay: number, az: number): void;
}

const GyroContext = createContext<GyroContextValue | null>(null);

export function GyroProvider({ children }: { children: ReactNode }) {
  const engineRef = useRef<FakeGyroEngine | null>(null);
  if (!engineRef.current) engineRef.current = new FakeGyroEngine();
  const engine = engineRef.current;

  const hubRef = useRef<SensorHub | null>(null);
  if (!hubRef.current) hubRef.current = new SensorHub(engine);
  const hub = hubRef.current;

  const frameRef = useRef<HTMLIFrameElement | null>(null);
  const [status, setStatus] = useState<SensorStatus>(EMPTY_STATUS);
  const [config, setConfigState] = useState<FakeGyroConfig>({ ...DEFAULT_CONFIG });
  const [sample, setSample] = useState<OrientationSample | null>(null);
  const [motion, setMotion] = useState<MotionSample | null>(null);
  const [positional, setPositional] = useState<PositionalSample | null>(null);
  const [active, setActive] = useState(false);
  const [streaming, setStreamingState] = useState(true);
  const [gameReady, setGameReady] = useState(false);
  const [gameInfo, setGameInfo] = useState("");

  /* ------- loop principal: funde os sensores e entrega ao jogo ------- */
  useEffect(() => {
    let raf = 0;
    let lastStatus = 0;
    const loop = () => {
      raf = requestAnimationFrame(loop);
      const now = performance.now();
      const s = engine.tick(now);
      if (s) {
        setSample(s);
        if (streaming) send({ ...s });
      }
      const m = engine.getMotion();
      if (m && streaming) send({ ...m });
      const p = engine.getPositional();
      if (p && streaming) send({ ...p });
      if (now - lastStatus > 200) {
        lastStatus = now;
        setStatus({ ...engine.status(now), permission: hub.permission, native: hub.hasNativeBridge() });
        setMotion(engine.getMotion());
        setPositional(engine.getPositional());
      }
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [engine, hub, streaming]);

  /* ------- ponte de mensagens com o iframe ------- */
  useEffect(() => {
    const onMsg = (e: MessageEvent) => {
      const d = e.data as BridgeReply | undefined;
      if (!d || typeof d !== "object") return;
      if (d.type === "fg:ready") {
        setGameReady(true);
        setGameInfo(`${d.game} · bridge v${d.version}${d.gyroApi ? " · giro do jogo" : ""}${d.positionalApi ? " · 6DoF posicional" : ""}`);
        // reenvia a configuração atual
        send({ type: "fg:config", config, streaming });
      } else if (d.type === "fg:pong") {
        setGameReady(true);
      }
    };
    window.addEventListener("message", onMsg);
    return () => window.removeEventListener("message", onMsg);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, streaming]);

  const send = useCallback((msg: BridgeMessage) => {
    const w = frameRef.current?.contentWindow;
    if (!w) return;
    try {
      w.postMessage(msg, "*");
    } catch {
      /* segue */
    }
  }, []);

  const start = useCallback(async () => {
    await hub.start();
    setActive(true);
    // dá um tempo para o sensor acordar e ainda pergunta ao jogo
    setTimeout(() => send({ type: "fg:ping" }), 400);
  }, [hub, send]);

  const stop = useCallback(() => {
    hub.stop();
    setActive(false);
  }, [hub]);

  const pushConfig = useCallback(
    (patch: Partial<FakeGyroConfig>) => {
      setConfigState((prev) => {
        const next = { ...prev, ...patch };
        engine.cfg = { ...next };
        send({ type: "fg:config", config: next, streaming });
        return next;
      });
    },
    [engine, send, streaming],
  );

  const recenter = useCallback(() => {
    engine.recenter();
    send({ type: "fg:recenter" });
  }, [engine, send]);

  const resetView = useCallback(() => {
    engine.reset();
    send({ type: "fg:reset" });
  }, [engine, send]);

  const setStreaming = useCallback(
    (on: boolean) => {
      setStreamingState(on);
      send({ type: "fg:config", config, streaming: on });
    },
    [config, send],
  );

  const attachFrame = useCallback((f: HTMLIFrameElement | null) => {
    frameRef.current = f;
    setGameReady(false);
    setGameInfo("");
    if (f) setTimeout(() => send({ type: "fg:ping" }), 500);
  }, [send]);

  const simulate = useCallback(
    (ax: number, ay: number, az: number) => {
      engine.pushBrowserAccel(ax, ay, az, performance.now());
    },
    [engine],
  );

  const value = useMemo<GyroContextValue>(
    () => ({
      engine,
      hub,
      status,
      config,
      sample,
      motion,
      positional,
      active,
      streaming,
      gameReady,
      gameInfo,
      start,
      stop,
      setConfig: pushConfig,
      recenter,
      resetView,
      setStreaming,
      attachFrame,
      send,
      simulate,
    }),
    [
      engine,
      hub,
      status,
      config,
      sample,
      motion,
      positional,
      active,
      streaming,
      gameReady,
      gameInfo,
      start,
      stop,
      pushConfig,
      recenter,
      resetView,
      setStreaming,
      attachFrame,
      send,
      simulate,
    ],
  );

  return <GyroContext.Provider value={value}>{children}</GyroContext.Provider>;
}

export function useGyro(): GyroContextValue {
  const ctx = useContext(GyroContext);
  if (!ctx) throw new Error("useGyro deve ser usado dentro de <GyroProvider>");
  return ctx;
}
