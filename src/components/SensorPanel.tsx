import { useCallback, useRef, useState } from "react";
import { useGyro } from "../lib/GyroContext";

function Pill(props: { ok: boolean; label: string; detail?: string }) {
  return (
    <div
      className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-xs ${
        props.ok
          ? "border-emerald-400/40 bg-emerald-400/10 text-emerald-200"
          : "border-white/10 bg-white/[0.03] text-slate-400"
      }`}
    >
      <span
        className={`h-2 w-2 rounded-full ${props.ok ? "bg-emerald-400 shadow-[0_0_8px] shadow-emerald-400" : "bg-slate-600"}`}
      />
      <span>{props.label}</span>
      {props.detail ? <span className="font-mono text-[10px] opacity-70">{props.detail}</span> : null}
    </div>
  );
}

/** Painel de status dos sensores + ativação + simulador para desktop. */
export default function SensorPanel() {
  const { status, active, start, stop, sample, simulate, hub, gameReady, gameInfo } = useGyro();
  const [busy, setBusy] = useState(false);
  const padRef = useRef<HTMLDivElement | null>(null);
  const dragging = useRef(false);

  const onMove = useCallback(
    (clientX: number, clientY: number) => {
      const el = padRef.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      const nx = ((clientX - r.left) / r.width) * 2 - 1;
      const ny = ((clientY - r.top) / r.height) * 2 - 1;
      // gravidade sintética: centro = aparelho deitado com a tela para cima
      simulate(-nx * 6.5, -ny * 6.5, 9.81);
    },
    [simulate],
  );

  const permLabel: Record<string, string> = {
    unknown: "não testado",
    granted: "permitido",
    denied: "negado",
    unsupported: "sem suporte",
  };

  return (
    <section className="rounded-2xl border border-white/10 bg-slate-900/60 p-5 backdrop-blur">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-white">Sensores</h2>
          <p className="mt-1 max-w-xl text-sm text-slate-400">
            O launcher lê o acelerômetro (e o giroscópio/magnetômetro quando existem) e entrega ao jogo
            eventos de orientação sintéticos — é assim que o giroscópio funciona mesmo sem giroscópio.
          </p>
        </div>
        <button
          onClick={async () => {
            setBusy(true);
            try {
              if (active) stop();
              else await start();
            } finally {
              setBusy(false);
            }
          }}
          disabled={busy}
          className={`rounded-xl px-4 py-2.5 text-sm font-semibold transition ${
            active
              ? "border border-rose-400/40 bg-rose-500/15 text-rose-200 hover:bg-rose-500/25"
              : "bg-cyan-400 text-slate-950 hover:bg-cyan-300"
          }`}
        >
          {busy ? "Aguarde…" : active ? "Desligar sensores" : "Ativar sensores"}
        </button>
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        <Pill ok={status.hasAccel} label="Acelerômetro" detail={`${status.accelRate} Hz`} />
        <Pill ok={status.hasGyro} label="Giroscópio" detail={status.hasGyro ? `${status.gyroRate} Hz` : "—"} />
        <Pill ok={status.hasMag} label="Magnetômetro" />
        <Pill ok={status.native} label="Ponte nativa (APK)" />
        <Pill ok={status.permission === "granted"} label={`Permissão: ${permLabel[status.permission]}`} />
      </div>

      <div className="mt-4 grid gap-4 sm:grid-cols-[1fr_auto]">
        <div className="rounded-xl border border-white/10 bg-black/30 p-3 font-mono text-xs text-slate-300">
          <div className="flex justify-between border-b border-white/5 pb-1">
            <span className="text-slate-500">fonte</span>
            <span className="text-cyan-300">{status.source}</span>
          </div>
          <div className="flex justify-between border-b border-white/5 py-1">
            <span className="text-slate-500">alpha (guinada)</span>
            <span>{sample ? `${sample.alpha.toFixed(1)}°` : "—"}</span>
          </div>
          <div className="flex justify-between border-b border-white/5 py-1">
            <span className="text-slate-500">beta (inclinação)</span>
            <span>{sample ? `${sample.beta.toFixed(1)}°` : "—"}</span>
          </div>
          <div className="flex justify-between py-1">
            <span className="text-slate-500">gamma (rolagem)</span>
            <span>{sample ? `${sample.gamma.toFixed(1)}°` : "—"}</span>
          </div>
          {gameReady ? (
            <div className="mt-1 truncate border-t border-white/5 pt-1 text-[10px] text-emerald-300">
              jogo conectado: {gameInfo}
            </div>
          ) : null}
        </div>

        <div className="w-full sm:w-44">
          <p className="mb-1 text-[11px] text-slate-500">Simulador (desktop)</p>
          <div
            ref={padRef}
            onPointerDown={(e) => {
              dragging.current = true;
              (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
              onMove(e.clientX, e.clientY);
            }}
            onPointerMove={(e) => {
              if (dragging.current) onMove(e.clientX, e.clientY);
            }}
            onPointerUp={() => {
              dragging.current = false;
              simulate(0, 0, 9.81);
            }}
            className="flex h-24 cursor-crosshair select-none items-center justify-center rounded-xl border border-dashed border-white/20 bg-gradient-to-br from-slate-800 to-slate-900 text-center text-[11px] leading-tight text-slate-400"
          >
            arraste para
            <br />
            inclinar
          </div>
        </div>
      </div>

      {hub.lastError ? <p className="mt-3 text-[11px] text-amber-300">Sensor: {hub.lastError}</p> : null}
      {!active && !status.native ? (
        <p className="mt-3 text-[11px] text-slate-500">
          No celular, abra por <span className="text-slate-300">HTTPS</span> (ou instale o APK) e toque em
          “Ativar sensores”. Em desktop, use o simulador ao lado.
        </p>
      ) : null}
    </section>
  );
}
