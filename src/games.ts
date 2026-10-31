export interface GameDef {
  id: string;
  title: string;
  /** caminho relativo (funciona no navegador e dentro do APK) */
  url: string;
  cover: string;
  tagline: string;
  description: string;
  tags: string[];
  /** o jogo tem opção de giroscópio própria que conseguimos ligar */
  hasGameGyro: boolean;
  /** o jogo usa câmera (hand tracking) */
  usesCamera: boolean;
  accent: string;
}

export const GAMES: GameDef[] = [
  {
    id: "gorilla",
    title: "Gorilla Rush",
    url: "games/Gorilla_Rush.html",
    cover: "img/gorilla-rush.jpg",
    tagline: "Pega-pega 3D multiplayer com física de braço",
    description:
      "Corra, pule e empurre os outros jogadores em arenas flutuantes. A visão usa o giroscópio — aqui ela vem do giroscópio emulado pelo acelerômetro, então funciona até em aparelho sem giroscópio ou dentro do WebView do APK.",
    tags: ["3DoF + 6DoF emulado", "multijogador", "acelerômetro"],
    hasGameGyro: true,
    usesCamera: true,
    accent: "from-orange-500/20 to-amber-500/5",
  },
  {
    id: "craft",
    title: "CraftVR",
    url: "games/CraftVR.html",
    cover: "img/craft-vr.jpg",
    tagline: "Mundo de blocos em primeira pessoa",
    description:
      "Um sandbox de blocos com modo giroscópio (3DoF), 6DoF falso por passos e 6DoF posicional por dead-reckoning do acelerômetro: ande de verdade para andar no jogo, agache para agachar.",
    tags: ["3DoF", "6DoF falso", "dead-reckoning"],
    hasGameGyro: true,
    usesCamera: false,
    accent: "from-emerald-500/20 to-sky-500/5",
  },
];

export function gameById(id: string): GameDef | undefined {
  return GAMES.find((g) => g.id === id);
}
