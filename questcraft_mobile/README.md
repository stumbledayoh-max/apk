# QuestCraft Mobile — adaptação experimental

O arquivo do link informado se chama **QCXR-6.0.0.apk**. Ele corresponde ao
nome do release oficial do QuestCraft 6.0.0, mas **não pudemos baixar o arquivo
do GoFile neste ambiente e portanto não sabemos se os dois são idênticos**. Este
projeto não modifica os jogos web do repositório: ele prepara separadamente um
APK experimental do QuestCraft para experimentar no Android.

O script usa por padrão o [release oficial 6.0.0](https://github.com/QuestCraftPlusPlus/QuestCraft/releases/tag/6.0.0)
e verifica seu SHA-256 (`4d474dff57ba131a8719e073078c9212c739c18841716f37968dfee6732b783c`).
Também aceita o seu arquivo local com `--apk`. Nenhum APK de terceiros ou chave é
armazenado neste repositório.

> **Importante:** isto é um patch experimental, **não é uma conversão comprovada**.
> Até agora, a compilação foi exercitada com um APK sintético e a lógica do
> manifesto foi testada; não houve teste em dispositivo real. O Unity, o
> Vivecraft, extensões proprietárias de Quest ou o mapeamento das mãos podem
> falhar. O patch não garante que o seu telefone execute o jogo.

## O que o patch faz

- Mantém o pacote `com.qcxr.qcxr`, torna opcionais alguns requisitos de headset
  no manifesto e acrescenta a visibilidade/permissões do broker OpenXR.
- Substitui `lib/arm64-v8a/libopenxr_loader.so` pelo loader do PhoneXR,
  [fixado neste commit](https://github.com/samrat1games/phonexr/tree/f393ea8c3f4579995efb7402d61a2108121b0900),
  com SHA-256 conferido. Remove outras ABIs para não instalar um loader não
  adaptado. Só gera build para **Android ARM64**.
- Reempacota, alinha bibliotecas para páginas de 16 KiB, assina com uma chave
  local de **debug** e verifica assinatura e alinhamento.
- Não inclui o runtime, a câmera nem os sensores dentro do APK. Eles vêm do
  [PhoneXR 2.0.1](https://github.com/samrat1games/phonexr/releases/tag/v2.0.1),
  instalado separadamente. O PhoneXR usa a câmera para estimar mãos/gestos e
  envia entrada ao seu runtime Monado OpenXR; a orientação depende dos
  sensores do telefone. Os gestos efetivamente aceitos pelo QuestCraft precisam
  ser verificados no aparelho. Sem giroscópio, aceleração sozinha não mede
  guinada completa; posição 6DoF também não é garantida.
- Não muda verificações de compra, conta ou autenticação do Minecraft.

Inspirado no [script PXRCraft do PhoneXR](https://github.com/samrat1games/phonexr/blob/f393ea8c3f4579995efb7402d61a2108121b0900/pxrcraft/build_pxrcraft.py)
e na lógica de [visibilidade OpenXR](https://github.com/samrat1games/phonexr/blob/f393ea8c3f4579995efb7402d61a2108121b0900/app/src/main/java/com/samrat/cardboardhands/AndroidManifestPatcher.kt).
QuestCraft, Unity wrapper e Pojlib têm seus próprios autores/licenças; Minecraft
pertence à Mojang/Microsoft. Confira as licenças dos componentes antes de
redistribuir qualquer build.

## Baixar uma build no GitHub

1. Depois que a branch com `.github/workflows/questcraft-mobile.yml` estiver no
   GitHub, um push que altere esses arquivos inicia **QuestCraft Mobile
   (experimental)** em **Actions**. Também é possível usar **Run workflow**
   quando o workflow estiver disponível na branch padrão.
2. Abra a execução **bem-sucedida** da branch e baixe o artefato
   `QuestCraft-Mobile-debug-experimental`. Nele há o APK, um `.sha256`, o
   `build-report.json` e as instruções. Um workflow que falhar não gera APK.
3. Essa automação baixa o release **oficial**, não o conteúdo do link GoFile.
   Para usar exatamente seu arquivo, faça a build local abaixo.

Cada execução do Actions cria uma chave de debug nova e não guarda a chave no
artefato. Logo, uma build posterior não atualiza a anterior com a mesma
assinatura. O Android pode exigir que a versão anterior seja desinstalada:
**faça backup antes, pois desinstalar pode apagar mundos e configurações.**

## Gerar localmente a partir do seu APK

Pré-requisitos: Python 3.9+, Java/JDK 17 (incluindo `keytool`),
[Apktool](https://apktool.org/) 2.9+ e Android SDK Build Tools **35+**
(`zipalign`, `apksigner`). Configure `ANDROID_HOME` ou passe `--build-tools`.
Se o Apktool estiver em um `.jar`, passe `--apktool-jar caminho/apktool.jar`.

```bash
# Com o arquivo do usuário (se o hash coincidir com o release oficial):
python3 questcraft_mobile/build_apk.py --apk /caminho/QCXR-6.0.0.apk

# Ou, sem --apk, baixar e verificar o release oficial automaticamente:
python3 questcraft_mobile/build_apk.py

# Testes da lógica (não precisam de SDK):
python3 -m unittest discover -s questcraft_mobile/tests -v
```

Se o arquivo local tiver SHA-256 diferente do oficial, o script para. Só depois
que você conferir sua procedência, é possível tentar explicitamente com
`--allow-unverified-apk`. O manifesto ainda precisa declarar `com.qcxr.qcxr` e o
APK precisa conter um loader arm64 OpenXR; versões diferentes podem quebrar.
Um loader alternativo também exige `--loader` e, se tiver outro hash,
`--allow-unverified-loader`.

O resultado fica em `questcraft-build/QuestCraft-Mobile-debug.apk`, acompanhado
do checksum e do relatório. Por padrão, a chave de teste fica fora do
repositório em `~/.android/questcraft-mobile-debug.p12`. Guarde-a com segurança
se quiser que builds locais posteriores possam ser atualizadas; ela é apenas
para debug, não para publicar. Não compartilhe a chave.

## Instalar e testar no celular

1. Use um telefone **Android 10 ou superior, 64-bit (arm64)**, com câmera e
   sensores; um VR Box ajuda a visualizar a imagem estéreo. Hardware, suporte
   OpenGL/Vulkan e desempenho variam: não dá para prometer compatibilidade com
   todo celular. Mantenha as mãos visíveis para a câmera e a área de jogo livre.
2. Baixe e instale o **PhoneXR-2.0.1.apk** (versão completa, não BE) da
   [página oficial](https://github.com/samrat1games/phonexr/releases/tag/v2.0.1).
   Abra, conceda câmera e instale/selecione o **PhoneXR Runtime** se solicitado.
3. Instale o APK produzido. Como é assinado com outra chave, ele não pode
   atualizar o QuestCraft original de mesma identificação. Se precisar remover
   qualquer versão, **faça backup primeiro**.
4. Abra o QuestCraft **pela biblioteca do PhoneXR**, não pelo ícone do Android:
   o PhoneXR precisa iniciar o serviço de câmera de mãos antes de abrir o jogo.
   Confira o ajuste de rastreamento de mãos e o posicionamento do telefone;
   controle Bluetooth pode ser necessário para funções sem mapeamento por gesto.
5. Na primeira abertura, o launcher pode precisar de internet para baixar Java,
   Vivecraft e outros componentes. É necessária conta Microsoft com
   **Minecraft: Java Edition**. O runtime e o game podem não funcionar em seu
   aparelho; se houver erro, guarde o log do Android (`adb logcat`) para análise.

O Android solicitará autorização para instalar aplicativos de fontes externas.
Só conceda para os arquivos cuja origem você verificou. A câmera opera apenas
com permissão; interrompa o rastreamento no PhoneXR quando terminar.

## Alternativa já publicada

O [HyperMagic 0.4.0](https://github.com/mt-VR/HyperXR/releases/tag/v0.4.0)
é outro projeto que declara um launcher próprio baseado em Pojlib/QuestCraft,
com perfil QuestCraft, runtime e mãos. **Não é este APK modificado** e não usa
o mesmo fluxo de PhoneXR descrito acima; pode ser uma alternativa para testar
no telefone enquanto esta adaptação ainda é experimental.
