# QuestCraft Mobile — APK único experimental

Adaptação **não oficial e não validada em celular físico** do QuestCraft 6.0.0 para Android ARM64. O processo incorpora no mesmo APK um runtime Monado/PhoneXR, o serviço de câmera e o modelo MediaPipe. **Não é necessário instalar o aplicativo PhoneXR nem um runtime separado.** Compilar ou instalar não prova que o jogo ou os controles funcionam.

## Baixar e instalar

A [página de builds desta branch](https://github.com/stumbledayoh-max/apk/actions/workflows/questcraft-mobile.yml?query=branch%3Aarena%2Fe3a80795-apk) lista as execuções. Se houver uma execução bem-sucedida com o artefato **QuestCraft-Mobile-unico-experimental**, abra-a, baixe o ZIP do artefato (o GitHub normalmente pede login), extraia `QuestCraft-Mobile-debug.apk` e instale-o. Não confunda com o artefato antigo `QuestCraft-Mobile-debug-experimental`, que dependia do aplicativo PhoneXR.

1. Use Android 10 (API 29) ou superior em um telefone **ARM64** com câmera traseira e sensores. É necessária internet para os componentes baixados pelo QuestCraft na primeira execução.
2. Abra o QuestCraft pelo ícone. Para tentar rastreamento de mãos, conceda a permissão da câmera. A posição e a iluminação podem influenciar os gestos; a ponte usa uma câmera 2D e posições aproximadas, não captura 6DoF confiável.
3. O jogo original informa que o jogo completo exige conta Microsoft com Minecraft: Java Edition. O lançamento original também menciona um modo de demonstração. Não sabemos se login, demo e controles funcionarão neste port; um controle externo pode ser necessário.

A assinatura é de **teste**, diferente da assinatura oficial e recriada em cada execução da CI. O Android pode exigir a remoção de instalações anteriores para instalar ou atualizar. **Faça backup dos dados antes: desinstalar pode apagá-los.** Não compartilhe suas credenciais com o autor desta adaptação.

## Estado e limitações

- A build unifica os componentes no APK; isso não é prova de gameplay, rastreamento, giroscópio ou compatibilidade. O suporte pode não iniciar, travar, exibir aviso de hardware ou não reconhecer gestos.
- O teste anterior no emulador mostrou um aviso de hardware e voltou à tela inicial depois de Continuar. A captura salva no site é desse teste anterior e **não é gameplay**. Emuladores não comprovam o funcionamento de câmera ou sensores em celular real.
- Não há acesso ao projeto Unity completo do jogo. A adaptação altera o APK oficial e integra bibliotecas binárias de terceiros; não é uma recompilação nativa do QuestCraft para celulares.
- O APK tem assinatura de depuração e não deve substituir uma versão de produção. Câmera e sensores são recursos sensíveis; não há garantia de qualidade, estabilidade ou segurança.
- Não sabemos se a câmera traseira conseguirá observar as mãos na forma como você segura ou usa o telefone. O reconhecimento e a conversão em controles são heurísticos e incompletos.

## Construir

Use JDK 17, Python 3, Gradle 8.11.1, Android SDK 35, build-tools, `zipalign`, `apksigner` e apktool. A CI em [`.github/workflows/questcraft-mobile.yml`](../.github/workflows/questcraft-mobile.yml) documenta as versões e os downloads fixados. O APK oficial QuestCraft 6.0.0 deve ter SHA-256 `4d474dff57ba131a8719e073078c9212c739c18841716f37968dfee6732b783c`; não conseguimos verificar se o arquivo da URL GoFile fornecida era idêntico.

Primeiro coloque o modelo `hand_landmarker.task` em um diretório à parte (a CI fixa o SHA-256 `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`) e execute:

```bash
QUESTCRAFT_HAND_MODEL_DIR=/caminho/para/modelo \
  gradle -p questcraft_mobile/embedded testReleaseUnitTest assembleRelease
python3 -m unittest discover -s questcraft_mobile/tests -v
python3 questcraft_mobile/build_apk.py --help
```

Depois execute o construtor passando o APK original e os caminhos das ferramentas, conforme `--help` ou o workflow. O suporte compilado é esperado em `questcraft_mobile/embedded/app/build/outputs/apk/release/app-release-unsigned.apk`. O builder verifica hashes fixados, mescla código/recursos e assina a saída com chave local de depuração. Ele aceita componentes locais correspondentes aos hashes fixados para builds reproduzíveis; consulte `--help`.

## Créditos e direitos

QuestCraft pertence a seus respectivos autores; Minecraft pertence à Mojang/Microsoft. Esta adaptação usa o loader/runtime e ideias do [PhoneXR](https://github.com/samrat1games/phonexr), o [MediaPipe](https://github.com/google-ai-edge/mediapipe) e componentes Monado. As permissões de redistribuição do QuestCraft e do PhoneXR não foram estabelecidas: o repositório PhoneXR consultado não apresentava uma licença geral. **Não redistribua builds que incorporem esses componentes sem confirmar os direitos com os respectivos titulares.** Licenças dos demais componentes e respectivos avisos também devem ser respeitados.
