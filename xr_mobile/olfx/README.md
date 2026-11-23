# Componentes OLFX usados no BigXR

Fonte: [OLFX, commit `32d457f9782127933bd5f9be7275d270bb70e73c`](https://github.com/samrat1games/OLFX/tree/32d457f9782127933bd5f9be7275d270bb70e73c). O OLFX é um fork do Monado, não um segundo runtime que possa ser simplesmente somado ao primeiro.

O BigXR utiliza esses componentes em **ambos** os serviços de câmera: o do criador e o incluído no APK independente do jogo:

- `HandFrameClock`, `HandImageGeometry` e `HandAssociation` são cópias dos arquivos OLFX, alterando apenas o pacote Java para evitar colisões. Um teste confere seus hashes originais.
- `Yuv420` adapta `src/xrt/drivers/android/olfx_yuv.h` para Java/ARGB: verifica o último byte realmente acessado em cada plano de câmera, aceita strides U/V independentes e não precisa de compressão JPEG. O tamanho do quadro é limitado para evitar alocações excessivas.
- `HandRouting` adapta a associação global de pulsos, rotação dos pontos e histerese de pinça 0,30/0,46 de `OlfxHands.java` e `src/xrt/drivers/ht_ctrl_emu/ht_ctrl_emu.cpp`. Ele alimenta os estados de controle PH5 já usados pelo runtime PhoneXR/Monado, com estimativas 2D aproximadas. Uma mão ausente libera o gatilho; a identidade é mantida por um curto intervalo. A precisão em aparelho real ainda precisa de teste.
- `MediaPipeRouting` conecta o resultado do modelo às rotinas acima; a geometria e os timestamps são usados efetivamente no processamento de cada quadro, não apenas incluídos no APK.

Os arquivos adaptados são licenciados em **BSL-1.0**, com os créditos originais. O texto completo e os avisos estão em `src/main/assets/bigxr_licenses/OLFX-BSL-1.0.txt`, empacotado tanto no criador quanto no APK gerado. Não copiamos o launcher completo, o compositor SBS, o runtime nativo do OLFX, o suporte a 26 juntas OpenXR, o serviço de acessibilidade ou os shims de VrApi/OVRPlugin. O runtime continua sendo o PhoneXR/Monado já fixado; seus direitos de redistribuição permanecem por confirmar. Nada disso inclui ou emula o runtime proprietário OculusXR, contorna licença/DRM ou garante compatibilidade com jogos Quest.

Os testes JVM compartilhados cobrem relógio, rotações, associação de duas mãos, perda de detecção, histerese e planos YUV truncados ou com strides diferentes. A CI verifica a presença das classes e da licença no criador e no payload. O teste em emulador verifica que um quadro passou pela câmera e pelo modelo; a detecção em uma foto de referência, entradas PH5 sintéticas e o runtime headless são testes distintos. Nenhum substitui gameplay ou gestos ao vivo em um celular físico.
