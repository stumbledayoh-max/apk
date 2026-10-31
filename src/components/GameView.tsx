import { useEffect, useRef, useState } from "react";
import { gameById } from "../games";
import { useGyro } from "../lib/GyroContext";
import SettingsPanel from "./SettingsPanel";
import ApkModal from "./ApkModal";

export default function GameView({ gameId, onExit }: { gameId: string; onExit: () => void }) {
  const game = gameById(gameId);
  const { status, sample, attachFrame, active, start, streaming, setStreaming, recenter, resetView, config, setConfig } =
    useGyro();
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const [uiVisible, setUiVisible] = useState(true);
  const [showSettings, setShowSettings] = useState(false);
  const [showApkModal, setShowApkModal] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const hideTimer = useRef<number | null>(null);

  /* inicia os sensores ao abrir o jogo */
  useEffect(() => {
    if (!active) void start();
  }, [active, start]);

  /* auto-oculta a interface durante o jogo */
  useEffect(() => {
    const bump = () => {
      setUiVisible(true);
      if (hideTimer.current) window.clearTimeout(hideTimer.current);
      hideTimer.current = window.setTimeout(() => setUiVisible(false), 4000);
    };
    bump();
    window.addEventListener("pointerdown", bump);
    window.addEventListener("keydown", bump);
    return () => {
      window.removeEventListener("pointerdown", bump);
      window.removeEventListener("keydown", bump);
      if (hideTimer.current) window.clearTimeout(hideTimer.current);
    };
  }, []);

  /* ESC volta ao launcher */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !showSettings) onExit();
      if (e.key.toLowerCase() === "r") recenter();
      if (e.key.toLowerCase() === "f") toggleFullscreen();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onExit, recenter, showSettings]);

  const flash = (msg: string) => {
    setToast(msg);
    window.setTimeout(() => setToast(null), 2200);
  };

  const callGame = (fn: (api: NonNullable<Window["FakeGyro"]>) => void, okMsg: string, failMsg: string) => {
    const w = wrapRef.current?.querySelector("iframe")?.contentWindow;
    const api = w?.FakeGyro;
    if (api) {
      try {
        fn(api);
        flash(okMsg);
        return;
      } catch {
        /* segue */
      }
    }
    flash(failMsg);
  };

  const toggleFullscreen = () => {
    const el = wrapRef.current;
    if (!el) return;
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => {});
    else void el.requestFullscreen?.().catch(() => {});
  };

  if (!game) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-950 text-slate-300">
        <div className="text-center">
          <p>Jogo não encontrado.</p>
          <button onClick={onExit} className="mt-3 rounded-lg bg-cyan-400 px-4 py-2 text-sm font-bold text-slate-950">
            Voltar
          </button>
        </div>
      </div>
    );
  }

  return (
    <div ref={wrapRef} className="fixed inset-0 bg-black">
      <iframe
        key={game.id}
        ref={attachFrame}
        src={game.url}
        title={game.title}
        className="h-full w-full border-0"
        allow="autoplay; camera; microphone; fullscreen; xr-spatial-tracking; gyroscope; accelerometer; magnetometer; clipboard-write"
        allowFullScreen
      />

      {/* ------------------------------- HUD ------------------------------- */}
      <div
        className={`pointer-events-none absolute inset-x-0 top-0 flex items-start justify-between gap-3 bg-gradient-to-b from-black/70 to-transparent p-3 transition-opacity duration-300 ${
          uiVisible ? "opacity-100" : "opacity-0"
        }`}
      >
        <div className="pointer-events-auto flex items-center gap-2">
          <button
            onClick={onExit}
            className="flex items-center gap-1.5 rounded-lg border border-white/15 bg-black/50 px-3 py-2 text-sm text-white backdrop-blur transition hover:border-cyan-300/60"
          >
            <span aria-hidden>←</span> Jogos
          </button>
          <div className="hidden rounded-lg border border-white/10 bg-black/50 px-3 py-2 text-xs text-slate-300 backdrop-blur sm:block">
            <b className="text-white">{game.title}</b>
            <span className="ml-2 text-cyan-300">{status.source}</span>
            <span className="ml-2 font-mono text-slate-500">{status.accelRate} Hz</span>
          </div>
        </div>

        <div className="pointer-events-auto rounded-lg border border-white/10 bg-black/50 px-3 py-2 font-mono text-[11px] text-slate-300 backdrop-blur">
          <span className="text-slate-500">α</span> {sample ? sample.alpha.toFixed(0) : "–"}°
          <span className="ml-2 text-slate-500">β</span> {sample ? sample.beta.toFixed(0) : "–"}°
          <span className="ml-2 text-slate-500">γ</span> {sample ? sample.gamma.toFixed(0) : "–"}°
          <span
            className={`ml-2 ${streaming ? "text-emerald-300" : "text-rose-300"}`}
          >
            {streaming ? "● sintético" : "○ pausado"}
          </span>
        </div>
      </div>

      {/* ---------------------------- controles ---------------------------- */}
      <div
        className={`absolute bottom-0 right-0 flex flex-col items-end gap-2 p-3 transition-opacity duration-300 ${
          uiVisible ? "opacity-100" : "pointer-events-none opacity-0"
        }`}
      >
        <div className="pointer-events-auto flex flex-wrap justify-end gap-2">
          <HudButton
            label="Recentralizar (R)"
            onClick={() => {
              recenter();
              callGame((api) => api.recenter(), "Visão recentralizada", "Recentralizado no launcher");
            }}
          />
          <HudButton label="Zerar (reset)" onClick={resetView} />
          <HudButton
            label={streaming ? "Pausar sensor" : "Retomar sensor"}
            onClick={() => setStreaming(!streaming)}
          />
          {game.hasGameGyro ? (
            <HudButton
              label="Ligar giro no jogo"
              onClick={() =>
                callGame(
                  (api) => api.enableGameGyro(),
                  "Giroscópio do jogo ativado",
                  "Este jogo já usa o sensor automático",
                )
              }
            />
          ) : null}
          <HudButton
            label={config.positional ? "6DoF posicional: ON" : "6DoF posicional: OFF"}
            onClick={() => {
              const next = !config.positional;
              setConfig({ positional: next });
              callGame(
                (api) => api.setPositional(next),
                next ? "Ande de verdade para andar no jogo" : "6DoF posicional desligado",
                next ? "Sem 6DoF posicional neste jogo" : "6DoF posicional desligado",
              );
            }}
          />
          <HudButton label="Tela cheia (F)" onClick={toggleFullscreen} />
          <HudButton label="Ajustes" onClick={() => setShowSettings((v) => !v)} />
          <button
            onClick={() => setShowApkModal(true)}
            className="rounded-lg border border-emerald-400/40 bg-emerald-500/20 px-3 py-2 text-xs font-bold text-emerald-200 backdrop-blur transition hover:bg-emerald-500/30"
          >
            📱 APK
          </button>
        </div>
      </div>

      {toast ? (
        <div className="pointer-events-none absolute bottom-24 left-1/2 -translate-x-1/2 rounded-lg border border-cyan-300/40 bg-black/80 px-4 py-2 text-sm text-cyan-100 backdrop-blur">
          {toast}
        </div>
      ) : null}

      {!active ? (
        <div className="pointer-events-none absolute inset-x-0 bottom-16 flex justify-center">
          <button
            onClick={() => void start()}
            className="pointer-events-auto rounded-xl bg-cyan-400 px-5 py-3 text-sm font-bold text-slate-950 shadow-lg shadow-cyan-500/30"
          >
            Ativar sensores para jogar
          </button>
        </div>
      ) : null}

      {/* --------------------------- configurações --------------------------- */}
      {showSettings ? (
        <div className="absolute inset-y-0 right-0 w-full max-w-sm overflow-y-auto border-l border-white/10 bg-slate-950/95 p-5 backdrop-blur">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
              Ajustes do sensor
            </h2>
            <button
              onClick={() => setShowSettings(false)}
              className="rounded-lg border border-white/15 px-3 py-1.5 text-xs text-slate-300 hover:text-white"
            >
              fechar
            </button>
          </div>
          <SettingsPanel compact />
          <div className="mt-6 space-y-3 border-t border-white/10 pt-4 text-[12px] text-slate-400">
            <p>
              <b className="text-slate-300">Dica:</b> se o jogo oferecer arraste/toque para olhar, use
              como ajuste fino da guinada — o acelerômetro não mede o giro vertical sozinho.
            </p>
            <p>
              Atalhos: <b className="text-slate-300">R</b> recentraliza · <b className="text-slate-300">F</b>{" "}
              tela cheia · <b className="text-slate-300">ESC</b> volta aos jogos.
            </p>
          </div>
        </div>
      ) : null}

      {showApkModal && <ApkModal onClose={() => setShowApkModal(false)} />}
    </div>
  );
}

function HudButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="rounded-lg border border-white/15 bg-black/60 px-3 py-2 text-xs text-slate-200 backdrop-blur transition hover:border-cyan-300/60 hover:text-white"
    >
      {label}
    </button>
  );
}
