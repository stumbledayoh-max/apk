import { useGyro } from "../lib/GyroContext";
import type { GyroMode, YawSource } from "../lib/types";

const MODES: { value: GyroMode; label: string; hint: string }[] = [
  {
    value: "auto",
    label: "Automático",
    hint: "Usa giroscópio real se existir; senão emula pelo acelerômetro.",
  },
  {
    value: "accel",
    label: "Giroscópio emulado (acelerômetro)",
    hint: "Ignora o giroscópio: só acelerômetro + filtro complementar.",
  },
  {
    value: "native",
    label: "Sensores nativos (giro + magnetômetro)",
    hint: "Preferência pelos sensores reais lidos pelo app Android.",
  },
];

const YAW: { value: YawSource; label: string; hint: string }[] = [
  { value: "auto", label: "Automática", hint: "Magnetômetro se existir; senão aceleração horizontal." },
  { value: "mag", label: "Magnetômetro / bússola", hint: "Guinada absoluta (norte)." },
  { value: "pseudo", label: "Aceleração horizontal (experimental)", hint: "Estima o giro vertical pelo movimento da mão." },
  { value: "none", label: "Desligada", hint: "Use o arraste do próprio jogo para girar." },
];

function Slider(props: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  format?: (v: number) => string;
  onChange: (v: number) => void;
  hint?: string;
}) {
  const { label, value, min, max, step, onChange, hint } = props;
  const fmt = props.format ?? ((v: number) => v.toFixed(2));
  return (
    <label className="block">
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-sm text-slate-300">{label}</span>
        <span className="font-mono text-xs text-cyan-300">{fmt(value)}</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="mt-1 h-1.5 w-full cursor-pointer appearance-none rounded-full bg-slate-700 accent-cyan-400"
      />
      {hint ? <p className="mt-1 text-[11px] leading-snug text-slate-500">{hint}</p> : null}
    </label>
  );
}

function Toggle(props: { label: string; checked: boolean; onChange: (v: boolean) => void; hint?: string }) {
  return (
    <label className="flex cursor-pointer items-start gap-3">
      <input
        type="checkbox"
        checked={props.checked}
        onChange={(e) => props.onChange(e.target.checked)}
        className="mt-0.5 h-4 w-4 accent-cyan-500"
      />
      <span>
        <span className="block text-sm text-slate-200">{props.label}</span>
        {props.hint ? <span className="block text-[11px] text-slate-500">{props.hint}</span> : null}
      </span>
    </label>
  );
}

export default function SettingsPanel({ compact = false }: { compact?: boolean }) {
  const { config, setConfig, status } = useGyro();
  return (
    <div className={compact ? "space-y-4" : "space-y-5"}>
      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">Fusão de atitude</p>
        <div className="space-y-2">
          {MODES.map((m) => (
            <label
              key={m.value}
              className={`flex cursor-pointer gap-3 rounded-xl border p-3 transition ${
                config.mode === m.value
                  ? "border-cyan-400/60 bg-cyan-400/10"
                  : "border-white/10 bg-white/[0.02] hover:border-white/20"
              }`}
            >
              <input
                type="radio"
                name="gyromode"
                className="mt-0.5 accent-cyan-500"
                checked={config.mode === m.value}
                onChange={() => setConfig({ mode: m.value })}
              />
              <span>
                <span className="block text-sm font-medium text-slate-100">{m.label}</span>
                <span className="block text-[11px] leading-snug text-slate-400">{m.hint}</span>
              </span>
            </label>
          ))}
        </div>
      </div>

      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">Guinada (yaw)</p>
        <select
          value={config.yawSource}
          onChange={(e) => setConfig({ yawSource: e.target.value as YawSource })}
          className="w-full rounded-lg border border-white/10 bg-slate-800 px-3 py-2 text-sm text-slate-100 outline-none focus:border-cyan-400"
        >
          {YAW.map((y) => (
            <option key={y.value} value={y.value}>
              {y.label}
            </option>
          ))}
        </select>
        <p className="mt-1 text-[11px] text-slate-500">
          {YAW.find((y) => y.value === config.yawSource)?.hint}
        </p>
        {!status.hasMag && config.yawSource === "mag" ? (
          <p className="mt-1 text-[11px] text-amber-300">Nenhum magnetômetro detectado ainda.</p>
        ) : null}
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <Slider
          label="Sensibilidade"
          value={config.sensitivity}
          min={0.2}
          max={3}
          step={0.05}
          onChange={(v) => setConfig({ sensitivity: v })}
          hint="Multiplica a rotação em relação ao ponto de referência."
        />
        <Slider
          label="Suavização"
          value={config.smoothing}
          min={0}
          max={1}
          step={0.01}
          onChange={(v) => setConfig({ smoothing: v })}
          hint="0 = colado no sensor (rápido, tremido) · 1 = macio (com atraso)."
        />
        <Slider
          label="Ganho do giro emulado"
          value={config.pseudoGain}
          min={0}
          max={2.5}
          step={0.05}
          onChange={(v) => setConfig({ pseudoGain: v })}
          hint="Quanta velocidade angular é inventada a partir da derivada da gravidade."
        />
        <Slider
          label="Ganho da guinada"
          value={config.yawGain}
          min={-3}
          max={3}
          step={0.05}
          onChange={(v) => setConfig({ yawGain: v })}
          hint="Só vale quando a guinada vem da aceleração horizontal."
        />
      </div>

      <div className="rounded-xl border border-white/10 bg-white/[0.02] p-3">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">6DoF posicional</p>
        <div className="space-y-3">
          <Toggle
            label="Andar de verdade move o jogador"
            checked={config.positional}
            onChange={(v) => setConfig({ positional: v })}
            hint="Dead-reckoning do acelerômetro com atualização de velocidade zero (experimental)."
          />
          {config.positional ? (
            <>
              <Slider
                label="Escala do passo"
                value={config.walkScale}
                min={0.2}
                max={3}
                step={0.05}
                onChange={(v) => setConfig({ walkScale: v })}
              />
              <Slider
                label="Limiar de ruído"
                value={config.noiseGate}
                min={0.05}
                max={1.5}
                step={0.01}
                format={(v) => `${v.toFixed(2)} m/s²`}
                onChange={(v) => setConfig({ noiseGate: v })}
                hint="Abaixo disso o jogador é considerado parado (zera a velocidade)."
              />
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}
