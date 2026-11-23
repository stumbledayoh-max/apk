import { useEffect, useState } from "react";

const REPO = "stumbledayoh-max/apk";
const BRANCH = "arena/e3a80795-apk";
const ACTIONS_URL = `https://github.com/${REPO}/actions/workflows/questcraft-mobile.yml?query=branch%3A${encodeURIComponent(BRANCH)}`;
const GUIDE_URL = `https://github.com/${REPO}/blob/${BRANCH}/questcraft_mobile/README.md`;
const SOURCE_URL = "https://github.com/QuestCraftPlusPlus/QuestCraft";

interface BuildLink {
  url: string;
  direct: boolean;
  expiresAt?: string;
}

/** Artifacts expire, so resolve the latest successful build rather than baking in an old URL. */
function useLatestBuild(): BuildLink {
  const [link, setLink] = useState<BuildLink>({
    url: ACTIONS_URL,
    direct: false,
  });

  useEffect(() => {
    const controller = new AbortController();
    const fetchJson = async (url: string) => {
      const response = await fetch(url, { signal: controller.signal });
      if (!response.ok) throw new Error("GitHub indisponível");
      return response.json();
    };

    async function findBuild() {
      try {
        const runs = await fetchJson(
          `https://api.github.com/repos/${REPO}/actions/workflows/questcraft-mobile.yml/runs?branch=${encodeURIComponent(BRANCH)}&status=success&per_page=10`,
        );
        for (const run of runs.workflow_runs ?? []) {
          if (!Number.isSafeInteger(run.id)) continue;
          const result = await fetchJson(
            `https://api.github.com/repos/${REPO}/actions/runs/${run.id}/artifacts`,
          );
          const artifact = (result.artifacts ?? []).find(
            (item: { name?: string; expired?: boolean; id?: number }) =>
              item.name === "QuestCraft-Mobile-unico-experimental" &&
              !item.expired &&
              Number.isSafeInteger(item.id),
          );
          if (artifact) {
            setLink({
              url: `https://github.com/${REPO}/actions/runs/${run.id}/artifacts/${artifact.id}`,
              direct: true,
              expiresAt: artifact.expires_at,
            });
            return;
          }
        }
      } catch {
        // A stable link to Actions remains usable if the API is offline or rate-limited.
      }
    }
    void findBuild();
    return () => controller.abort();
  }, []);

  return link;
}

function Arrow({ diagonal = false }: { diagonal?: boolean }) {
  return (
    <svg
      aria-hidden="true"
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
    >
      {diagonal ? (
        <path d="M6 18 18 6M7 6h11v11" />
      ) : (
        <path d="M5 12h14m-6-6 6 6-6 6" />
      )}
    </svg>
  );
}

function FeatureIcon({ type }: { type: "gyro" | "hands" | "phone" }) {
  if (type === "gyro") {
    return (
      <svg aria-hidden="true" viewBox="0 0 32 32" fill="none">
        <circle cx="16" cy="16" r="8" />
        <path d="M27 16c0 6-5 11-11 11M5 16C5 10 10 5 16 5M25 13l2 3 3-2M7 19l-2-3-3 2M16 11v5l3 2" />
      </svg>
    );
  }
  if (type === "hands") {
    return (
      <svg aria-hidden="true" viewBox="0 0 32 32" fill="none">
        <path d="M10 15V8a2 2 0 0 1 4 0v6-8a2 2 0 0 1 4 0v8-6a2 2 0 0 1 4 0v8-3a2 2 0 0 1 4 0v5c0 6-3 9-9 9h-2c-3 0-5-2-7-5l-3-4a2 2 0 0 1 3-3l2 2" />
      </svg>
    );
  }
  return (
    <svg aria-hidden="true" viewBox="0 0 32 32" fill="none">
      <rect x="8" y="3" width="16" height="26" rx="3" />
      <path d="M13 7h6M14 25h4" />
    </svg>
  );
}

const features = [
  {
    type: "gyro" as const,
    eyebrow: "OLHAR",
    title: "Giroscópio",
    body: "O runtime integrado tenta usar os sensores do celular para orientar a visão; ainda falta testar no aparelho.",
  },
  {
    type: "hands" as const,
    eyebrow: "INTERAGIR",
    title: "Rastreamento de mãos",
    body: "A câmera traseira e o MediaPipe tentam converter gestos em controles dentro do próprio APK; ainda falta testar.",
  },
  {
    type: "phone" as const,
    eyebrow: "COMEÇAR",
    title: "Android 64 bits",
    body: "Um port experimental para Android 10 ou superior. Um VR Box e um controle Bluetooth podem ajudar.",
  },
];

const steps = [
  {
    number: "01",
    title: "Baixe o APK único",
    text: "Baixe a build experimental. Se vier em um ZIP do GitHub, extraia QuestCraft-Mobile-debug.apk.",
  },
  {
    number: "02",
    title: "Instale no Android",
    text: "Instale o APK em um celular Android 10 ou superior, com processador ARM64. Nenhum aplicativo auxiliar é necessário.",
  },
  {
    number: "03",
    title: "Abra o QuestCraft",
    text: "Toque no ícone do jogo e escolha se permite a câmera para tentar usar as mãos.",
  },
  {
    number: "04",
    title: "Prepare a primeira partida",
    text: "O jogo pode baixar componentes adicionais e precisa de uma conta com Minecraft: Java Edition.",
  },
];

export default function App() {
  const build = useLatestBuild();
  const expires = build.expiresAt
    ? new Date(build.expiresAt).toLocaleDateString("pt-BR", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
        timeZone: "UTC",
      })
    : null;

  return (
    <div className="site-shell">
      <header className="site-header">
        <div className="container header-inner">
          <a
            className="brand"
            href="#inicio"
            aria-label="QuestCraft Mobile, início"
          >
            <span className="brand-mark" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            <span className="brand-name">
              QUESTCRAFT <small>MOBILE</small>
            </span>
          </a>
          <nav aria-label="Navegação principal" className="main-nav">
            <a href="#sobre">O port</a>
            <a href="#instalacao">Instalação</a>
            <a href="#imagens">Imagens</a>
          </nav>
          <a
            className="header-link"
            href={`https://github.com/${REPO}/tree/${BRANCH}`}
            target="_blank"
            rel="noreferrer"
          >
            Projeto no GitHub <Arrow diagonal />
          </a>
        </div>
      </header>

      <main id="inicio">
        <section className="hero container" aria-labelledby="hero-title">
          <div className="hero-copy">
            <div className="eyebrow">
              <span className="pulse-dot" /> UM PROJETO EXPERIMENTAL{" "}
              <span className="eyebrow-separator">/</span> ANDROID
            </div>
            <h1 id="hero-title">
              QuestCraft<span className="hero-period">.</span>
              <br />
              <span>no seu celular.</span>
            </h1>
            <p className="hero-description">
              Um APK experimental do QuestCraft 6.0.0 com runtime, câmera e
              sensores integrados. Sem instalar um aplicativo auxiliar.
            </p>
            <div className="hero-actions">
              <a
                className="button button-primary"
                href={build.url}
                target="_blank"
                rel="noreferrer"
              >
                {build.direct ? "Baixar o port" : "Ver builds do port"}
                <Arrow diagonal />
              </a>
              <a className="button button-secondary" href="#instalacao">
                Como instalar <span aria-hidden="true">↓</span>
              </a>
            </div>
            <p className="download-note">
              <span className="mini-square" /> Download pelo GitHub ·{" "}
              {build.direct && expires
                ? `disponível até ${expires}`
                : "entre na conta para baixar"}
            </p>
            <div className="hero-meta" aria-label="Informações da versão">
              <div>
                <span>BASE DO PORT</span>
                <strong>QCXR 6.0.0</strong>
              </div>
              <div>
                <span>PLATAFORMA</span>
                <strong>ANDROID · ARM64</strong>
              </div>
              <div>
                <span>STATUS</span>
                <strong className="status-text">
                  <i /> EXPERIMENTAL
                </strong>
              </div>
            </div>
          </div>

          <div className="hero-visual">
            <div className="image-frame">
              <div className="image-topline">
                <span>
                  QUESTCRAFT <b>/</b> MOBILE
                </span>
                <span>
                  6.0.0 <span aria-hidden="true">✳</span>
                </span>
              </div>
              <div className="image-wrap">
                <img
                  src="images/questcraft-oficial.jpg"
                  alt="Arte oficial de divulgação do QuestCraft"
                />
                <div className="image-overlay" />
                <div className="image-label">
                  <span className="label-dot" /> ARTE ORIGINAL DO QUESTCRAFT
                </div>
              </div>
              <div className="image-footer">
                <div>
                  <span className="footer-label">A BASE DO PROJETO</span>
                  <strong>O mundo em outra perspectiva.</strong>
                </div>
                <span className="outline-arrow" aria-hidden="true">
                  ↗
                </span>
              </div>
            </div>
            <p className="image-caption">
              <span aria-hidden="true">✳</span> Arte oficial de referência; não
              é uma captura do nosso port.
            </p>
          </div>
        </section>

        <section id="sobre" className="section section-features">
          <div className="container">
            <div className="section-intro">
              <div>
                <span className="section-kicker">01 / O PROJETO</span>
                <h2>
                  Feito para experimentar<span>.</span>
                </h2>
              </div>
              <p>
                Uma adaptação, não uma promessa de compatibilidade. O resultado
                depende do seu aparelho e ainda precisa de testes.
              </p>
            </div>
            <div className="features-grid">
              {features.map((feature) => (
                <article className="feature-card" key={feature.type}>
                  <div className="feature-top">
                    <div className="feature-icon">
                      <FeatureIcon type={feature.type} />
                    </div>
                    <span>{feature.eyebrow}</span>
                  </div>
                  <h3>{feature.title}</h3>
                  <p>{feature.body}</p>
                </article>
              ))}
            </div>
            <div className="notice" role="note">
              <span className="notice-icon">!</span>
              <p>
                <strong>Antes de começar:</strong> esta versão de APK único é
                experimental e ainda não foi testada em um celular real. Ela
                pode não abrir ou os gestos podem não funcionar. Faça backup
                antes de desinstalar uma versão antiga.
              </p>
            </div>
          </div>
        </section>

        <section id="instalacao" className="section section-install">
          <div className="container install-layout">
            <div className="install-heading">
              <span className="section-kicker">02 / PASSO A PASSO</span>
              <h2>
                Vamos preparar
                <br />o seu celular<span>.</span>
              </h2>
              <p>
                Você vai precisar de internet e de uma conta Microsoft com
                Minecraft: Java Edition.
              </p>
              <a
                className="text-link"
                href={GUIDE_URL}
                target="_blank"
                rel="noreferrer"
              >
                Ver guia completo <Arrow diagonal />
              </a>
            </div>
            <ol className="steps-list">
              {steps.map((step) => (
                <li className="step" key={step.number}>
                  <span className="step-number">{step.number}</span>
                  <div>
                    <h3>{step.title}</h3>
                    <p>{step.text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section id="imagens" className="section section-images">
          <div className="container images-layout">
            <div>
              <span className="section-kicker">03 / IMAGENS</span>
              <h2>
                O port, como
                <br />
                ele está<span>.</span>
              </h2>
              <p>
                Esta captura é de uma versão anterior do port aberta no emulador
                Android. Ela mostrou um aviso de hardware e voltou à tela
                inicial. Ainda não temos imagem do jogo rodando em celular
                físico com o novo APK único.
              </p>
            </div>
            <figure className="capture-card">
              <div className="capture-topline">
                <span>TESTE NO EMULADOR</span>
                <span>INICIALIZAÇÃO</span>
              </div>
              <a
                href="images/questcraft-port-emulador.jpg"
                target="_blank"
                rel="noreferrer"
                aria-label="Abrir captura da inicialização do port em tamanho maior"
              >
                <img
                  src="images/questcraft-port-emulador.jpg"
                  alt="Tela de inicialização do port no emulador, com aviso de requisitos de hardware não atendidos"
                />
              </a>
              <figcaption>
                <strong>Inicialização de uma versão anterior</strong>
                <span>Aviso de hardware no emulador · Não é gameplay</span>
              </figcaption>
            </figure>
          </div>
        </section>

        <section className="container final-callout">
          <div>
            <span className="section-kicker">PRONTO PARA TESTAR?</span>
            <h2>
              Seu próximo mundo
              <br />
              começa aqui<span>.</span>
            </h2>
            <p>
              É experimental, mas já dá para baixar e tentar no seu Android.
            </p>
          </div>
          <a
            className="button button-primary"
            href={build.url}
            target="_blank"
            rel="noreferrer"
          >
            {build.direct ? "Baixar o port" : "Ver builds do port"}{" "}
            <Arrow diagonal />
          </a>
        </section>
      </main>

      <footer className="site-footer">
        <div className="container footer-inner">
          <a className="brand footer-brand" href="#inicio">
            <span className="brand-mark" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            <span className="brand-name">
              QUESTCRAFT <small>MOBILE</small>
            </span>
          </a>
          <p>
            Projeto experimental da comunidade. Não afiliado à Mojang ou à
            equipe QuestCraft.
          </p>
          <a href={SOURCE_URL} target="_blank" rel="noreferrer">
            QuestCraft original ↗
          </a>
        </div>
      </footer>
    </div>
  );
}
