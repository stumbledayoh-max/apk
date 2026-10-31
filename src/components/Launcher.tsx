import { useState } from "react";
import { GAMES } from "../games";
import { useGyro } from "../lib/GyroContext";
import SensorPanel from "./SensorPanel";
import SettingsPanel from "./SettingsPanel";
import ApkModal from "./ApkModal";

export default function Launcher({ onPlay }: { onPlay: (id: string) => void }) {
  const { active, status } = useGyro();
  const [showSettings, setShowSettings] = useState(false);
  const [showApkModal, setShowApkModal] = useState(false);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      {/* brilho de fundo */}
      <div className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute -left-40 -top-40 h-96 w-96 rounded-full bg-cyan-500/20 blur-[120px]" />
        <div className="absolute -bottom-40 right-0 h-96 w-96 rounded-full bg-violet-600/20 blur-[120px]" />
      </div>

      <div className="relative mx-auto max-w-6xl px-5 py-8 sm:py-12">
        <header className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <div className="inline-flex items-center gap-2 rounded-full border border-cyan-400/30 bg-cyan-400/10 px-3 py-1 text-[11px] font-medium tracking-wide text-cyan-200">
              <span className="h-1.5 w-1.5 rounded-full bg-cyan-300" />
              GIROSCÓPIO EMULADO POR ACELERÔMETRO · 6DoF FALSO
            </div>
            <h1 className="mt-3 text-3xl font-black tracking-tight text-white sm:text-5xl">
              VR <span className="text-cyan-300">Launcher</span>
            </h1>
            <p className="mt-2 max-w-2xl text-sm text-slate-400 sm:text-base">
              Os dois jogos abaixo ganharam uma camada de rastreamento de cabeça: o acelerômetro é
              transformado em giroscópio por um filtro complementar, e os eventos sintéticos são
              entregues dentro do jogo. Funciona no navegador e no APK.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <button
              onClick={() => setShowApkModal(true)}
              className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-600 px-4 py-2 text-sm font-bold text-slate-950 shadow-lg shadow-emerald-500/20 transition hover:from-emerald-400 hover:to-teal-500"
            >
              <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
                <rect x="5" y="2" width="14" height="20" rx="2" ry="2" />
                <line x1="12" y1="18" x2="12.01" y2="18" />
              </svg>
              <span>Gerar / Baixar APK</span>
            </button>
            <button
              onClick={() => setShowSettings((v) => !v)}
              className="rounded-xl border border-white/15 px-4 py-2 text-sm text-slate-200 transition hover:border-cyan-300/60 hover:text-white"
            >
              {showSettings ? "Ocultar ajustes" : "Ajustes do sensor"}
            </button>
          </div>
        </header>

        <div className="mt-8 grid gap-6 lg:grid-cols-[1.35fr_1fr]">
          <div className="space-y-6">
            <SensorPanel />

            <section>
              <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-slate-400">
                Jogos
              </h2>
              <div className="grid gap-4 sm:grid-cols-2">
                {GAMES.map((g) => (
                  <article
                    key={g.id}
                    className="group overflow-hidden rounded-2xl border border-white/10 bg-slate-900/60 backdrop-blur transition hover:border-cyan-300/40"
                  >
                    <div className={`relative h-36 bg-gradient-to-br ${g.accent}`}>
                      <img
                        src={g.cover}
                        alt={g.title}
                        className="h-full w-full object-cover opacity-90 transition group-hover:opacity-100"
                      />
                      <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/20 to-transparent" />
                      <h3 className="absolute bottom-3 left-4 text-xl font-bold text-white drop-shadow">
                        {g.title}
                      </h3>
                    </div>
                    <div className="p-4">
                      <p className="text-xs font-medium text-cyan-300">{g.tagline}</p>
                      <p className="mt-2 text-[13px] leading-relaxed text-slate-400">{g.description}</p>
                      <div className="mt-3 flex flex-wrap gap-1.5">
                        {g.tags.map((t) => (
                          <span
                            key={t}
                            className="rounded-full border border-white/10 bg-white/[0.04] px-2 py-0.5 text-[10px] text-slate-300"
                          >
                            {t}
                          </span>
                        ))}
                      </div>
                      <button
                        onClick={() => onPlay(g.id)}
                        className="mt-4 w-full rounded-xl bg-cyan-400 px-4 py-2.5 text-sm font-bold text-slate-950 transition hover:bg-cyan-300"
                      >
                        Jogar
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            </section>
          </div>

          <aside className="space-y-4">
            <div className="rounded-2xl border border-white/10 bg-slate-900/60 p-5 backdrop-blur">
              <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-400">
                Como funciona
              </h2>
              <ol className="mt-3 space-y-3 text-[13px] leading-relaxed text-slate-300">
                <li className="flex gap-3">
                  <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-cyan-400/20 text-[11px] font-bold text-cyan-200">
                    1
                  </span>
                  <span>
                    O <b>acelerômetro</b> mede a gravidade no aparelho. Isso dá inclinação e rolagem
                    absolutas — não dá guinada.
                  </span>
                </li>
                <li className="flex gap-3">
                  <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-cyan-400/20 text-[11px] font-bold text-cyan-200">
                    2
                  </span>
                  <span>
                    A <b>derivada</b> dessa atitude vira um pseudo-giroscópio, e um filtro
                    complementar (Mahony) junta os dois: resposta rápida sem deriva.
                  </span>
                </li>
                <li className="flex gap-3">
                  <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-cyan-400/20 text-[11px] font-bold text-cyan-200">
                    3
                  </span>
                  <span>
                    O resultado é disparado como <code className="text-cyan-200">deviceorientation</code>{" "}
                    dentro do jogo, que nem percebe a diferença.
                  </span>
                </li>
                <li className="flex gap-3">
                  <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-cyan-400/20 text-[11px] font-bold text-cyan-200">
                    4
                  </span>
                  <span>
                    Guinada vem do <b>magnetômetro</b>; sem ele, é estimada pela aceleração horizontal
                    (experimental) ou pelo arraste do jogo.
                  </span>
                </li>
              </ol>
            </div>

            <div className="rounded-2xl border border-white/10 bg-slate-900/60 p-5 backdrop-blur">
              <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-400">Estado</h2>
              <dl className="mt-3 space-y-2 text-[13px]">
                <div className="flex justify-between gap-3">
                  <dt className="text-slate-500">Sensores</dt>
                  <dd className={active ? "text-emerald-300" : "text-slate-300"}>
                    {active ? "ligados" : "desligados"}
                  </dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-slate-500">Fonte</dt>
                  <dd className="text-right text-cyan-300">{status.source}</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-slate-500">Taxa</dt>
                  <dd className="font-mono text-slate-300">{status.accelRate} Hz</dd>
                </div>
              </dl>
            </div>

            {showSettings ? (
              <div className="rounded-2xl border border-white/10 bg-slate-900/60 p-5 backdrop-blur">
                <h2 className="mb-4 text-sm font-semibold uppercase tracking-wider text-slate-400">
                  Ajustes do sensor
                </h2>
                <SettingsPanel />
              </div>
            ) : null}
          </aside>
        </div>

        <footer className="mt-10 border-t border-white/10 pt-6 text-[12px] leading-relaxed text-slate-500">
          <p>
            <b className="text-slate-400">APK:</b> o projeto Android em <code>android/</code> empacota
            este launcher em um WebView com ponte nativa de sensores (acelerômetro, giroscópio,
            magnetômetro e vetor de rotação). Rode <code>./gradlew assembleDebug</code> ou use o
            workflow do GitHub Actions para gerar o <code>.apk</code>.
          </p>
          <p className="mt-2">
            Os jogos são originais e permanecem em <code>public/games/</code>; o launcher apenas
            adiciona a camada de rastreamento.
          </p>
        </footer>
      </div>

      {showApkModal && <ApkModal onClose={() => setShowApkModal(false)} />}
    </div>
  );
}
