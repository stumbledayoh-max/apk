# XR de Bolso — detalhes do aplicativo nativo

## O que está implementado

- Seletor Android para um APK e um OBB opcional. O APK selecionado é copiado para o cache privado, inspecionado e enviado ao instalador do Android; não é modificado nem instalado silenciosamente.
- Cópia opcional do OBB após a instalação para `Android/obb/<pacote>/`, apenas se o nome for `main.<número>.<pacote>.obb` ou `patch.<número>.<pacote>.obb`. Arquivos existentes só são substituídos após confirmação; uma cópia temporária evita truncá-los durante a transferência. Algumas versões/fabricantes podem negar acesso à pasta.
- Broker OpenXR padrão, serviço Monado e bibliotecas ARM64 no APK do launcher. Jogos separados só conseguem usar o runtime se seu loader e suas declarações de visibilidade/compatibilidade forem adequados. Jogos de Quest/Pico com dependências específicas podem falhar.
- Câmera traseira com MediaPipe Hand Landmarker e prévia nativa dos 21 pontos por mão. Uma ponte PH5 envia posições, orientação e gestos **aproximados** como entradas semelhantes a controles ao runtime local. Não é uma implementação completa de `XR_EXT_hand_tracking` ou rastreamento calibrado 6DoF.
- Indicador nativo de giroscópio. O runtime integrado contém o driver de sensores Android; exibir dados no launcher não comprova orientação correta dentro de um jogo.

## Permissões e privacidade

A câmera é solicitada somente quando você escolhe ativar mãos. O serviço em primeiro plano permanece ativo ao abrir outro aplicativo e deve ser parado explicitamente pelo botão **Parar câmera** ou pela notificação. Android 13+ pode solicitar permissão de notificações para que essa notificação seja visível. Os quadros da câmera são processados no aparelho; a prévia recebe somente os pontos detectados.

A ponte de controles usa UDP em `127.0.0.1:42424`. Essa comunicação não é criptografada nem isolada de outros aplicativos locais. O broker e o serviço Monado precisam ser públicos para que jogos instalados separadamente possam acessá-los. O launcher pede `REQUEST_INSTALL_PACKAGES`, mas a instalação é sempre iniciada e confirmada pelo usuário. Não há permissão de instalação silenciosa nem acesso geral a todos os arquivos.

## Compilação

A CI em [`.github/workflows/xr-mobile.yml`](../.github/workflows/xr-mobile.yml) usa JDK 17, Gradle 8.11.1, Android SDK/build-tools 35, Python 3 e apktool 2.11.1. Modelo e runtime são fixados por SHA-256. Para compilar localmente, coloque o modelo `hand_landmarker.task` em um diretório e obtenha o APK fixado do runtime usado na CI; então execute:

```sh
XR_HAND_MODEL_DIR=/caminho/do/modelo \
  gradle -p xr_mobile/android testReleaseUnitTest assembleRelease
python3 -m unittest discover -s xr_mobile/tests -v
python3 xr_mobile/build_runtime.py \
  --app xr_mobile/android/app/build/outputs/apk/release/app-release-unsigned.apk \
  --runtime /caminho/phonexr-runtime.apk \
  --apktool-jar /caminho/apktool.jar \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --debug-keystore /caminho/xr-debug.p12 \
  --output xr-mobile-build/XR-de-Bolso-debug.apk
```

O empacotador verifica o hash do runtime, incorpora as bibliotecas e classes IPC, aplica uma guarda para uma extensão EGL opcional e assina com uma chave de teste. Também marca o cliente Monado com `DF_1_NODELETE`, mantendo a biblioteca mapeada até o processo cliente terminar: isso evitou um crash observado no emulador depois de `xrDestroyInstance`, mas é uma solução experimental e mantém a biblioteca na memória por mais tempo. Não substitui uma correção no código upstream.

O APK `xr_mobile/probe/` é uma fixture de diagnóstico separada, **não é um jogo**, e não é necessário para o usuário. A [captura 45102870985](https://github.com/stumbledayoh-max/apk/actions/runs/45102870985) instalou a fixture mediante confirmação, copiou um OBB sintético, iniciou/parou a câmera e criou/encerrou três instâncias OpenXR a partir de outro pacote. Em uma sessão OpenXR sem renderização, enviou pacotes PH5 sintéticos e observou estado de gatilho, pose e movimento do sensor virtual. No [teste diferencial 45103608864](https://github.com/stumbledayoh-max/apk/actions/runs/45103608864), a orientação foi medida com o giroscópio virtual zerado e novamente após aplicar rotação; houve uma mudança maior que 11 graus. Um teste de instrumentação executou o modelo no [exemplo de mãos do MediaPipe](https://github.com/google-ai-edge/mediapipe-samples/blob/b74eeb6d4952d97f244e03fefe4513637e59210f/examples/hand_landmarker/android/app/src/androidTest/assets/test_image.jpg), detectando pontos e um gesto. Cópias resumidas dos resultados estão em [`diagnosticos/`](diagnosticos/). Nada disso prova gameplay, gestos em câmera ao vivo ou funcionamento em celular físico. O fluxo completo está em [`.github/workflows/xr-mobile-capture.yml`](../.github/workflows/xr-mobile-capture.yml).

## Limitações importantes

Esta é uma prova de conceito, não um emulador universal. Instalação, broker, modelo ou sensor aparecendo não provam que o jogo desenhe quadros ou aceite controles. O resultado da câmera depende do posicionamento das mãos no campo de visão; não há calibração física, rastreamento de corpo, posição absoluta nem garantia de baixa latência. A aplicação e o runtime precisam ser testados em celular real com um jogo específico.

O ZIP do APK passa na verificação de alinhamento de 16 KB, mas as bibliotecas Monado fixadas têm segmentos ELF de 4 KB; portanto, isso não comprova compatibilidade com dispositivos de páginas de 16 KB. A autoridade `org.khronos.openxr.system_runtime_broker` também pode entrar em conflito com outro runtime já instalado.

O runtime binário é proveniente de [PhoneXR](https://github.com/samrat1games/phonexr) no commit `f393ea8c3f4579995efb7402d61a2108121b0900`, e o modelo é do MediaPipe. Os direitos de redistribuição do PhoneXR não foram confirmados; confirme-os antes de distribuir builds incorporando esse componente.
