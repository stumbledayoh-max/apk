import { useState } from "react";
import { GyroProvider } from "./lib/GyroContext";
import Launcher from "./components/Launcher";
import GameView from "./components/GameView";

export default function App() {
  const [current, setCurrent] = useState<string | null>(null);
  return (
    <GyroProvider>
      {current ? (
        <GameView gameId={current} onExit={() => setCurrent(null)} />
      ) : (
        <Launcher onPlay={setCurrent} />
      )}
    </GyroProvider>
  );
}
