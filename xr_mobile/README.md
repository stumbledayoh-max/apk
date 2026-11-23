# BigXR — detalhes do criador nativo

## Fluxo de cópia integrada

O criador funciona no próprio Android. Seleciona-se um APK assinado que o usuário tem direito de modificar, um nome, uma imagem opcional e até um OBB opcional. O app verifica a assinatura original, o ZIP e os DEX, injeta uma tela inicial e os DEX/bibliotecas do suporte BigXR, atualiza os recursos de ícone quando solicitado e assina a cópia localmente com uma chave RSA do Android Keystore. A assinatura gerada é verificada antes de a cópia ficar disponível para salvar ou instalar.

O pacote, a atividade e os dados do jogo original permanecem, mas o arquivo é modificado e não mantém a assinatura oficial. Não há obtenção automática de jogos, remoção de DRM, instalação silenciosa ou garantia de licença. A chave local pertence à instalação do BigXR: desinstalar o BigXR ou limpar seus dados pode inutilizar futuras atualizações do jogo criado. A cópia não atualiza diretamente a versão oficial; desinstalar a versão original pode apagar dados. Cada build do próprio BigXR na CI também usa uma chave de depuração diferente.

Para evitar conflitos entre jogos gerados, o loader ARM64 presente no APK original precisa conter exatamente os identificadores de broker OpenXR reconhecidos. Eles são substituídos por uma autoridade privada derivada do pacote, sem alterar o tamanho do ELF. O runtime apontará para o mesmo pacote. O criador rejeita loaders desconhecidos, bibliotecas ou classes conflitantes, APKs divididos, múltiplas telas iniciais e pacotes longos demais para o runtime fixado. Outras ABIs são retiradas da cópia; o resultado é ARM64. Um jogo com `libopenxr_loader.so` reconhecível ainda pode não funcionar: depende de APIs, recursos gráficos, serviços, direitos e hardware que o BigXR não fornece.

O ícone escolhido é recortado ao centro e convertido em PNG de 512×512. Um OBB opcional é incluído no APK como dado e conferido por SHA-256 antes de ser copiado para `getObbDir()` no primeiro início. O nome deve ser `main.<número>.<pacote>.obb` ou `patch.<número>.<pacote>.obb`. Um arquivo diferente já existente não é sobrescrito; o Android ou fabricante pode bloquear a pasta. O tamanho máximo é 1,6 GB para o APK de entrada, 1,7 GB para APK + OBB e 1,8 GB para a saída; é necessário mais espaço temporário para edição e assinatura. Exportar pelo seletor Android evita depender do cache privado.

## Mãos, sensores e privacidade

A tela integrada solicita câmera apenas se escolhida. O serviço em primeiro plano processa imagens no aparelho com MediaPipe e envia estados aproximados de controle ao Monado via UDP local, porta 42424. O processamento também usa componentes adaptados do OLFX: conversão YUV, relógio e rotação da câmera, associação das mãos e histerese da pinça. Estão no criador e em cada cópia independente; a licença BSL-1.0 é incluída nos APKs. Origem e escopo estão em [`olfx/README.md`](olfx/README.md).

A integração não substitui o runtime PhoneXR/Monado pelo runtime completo do OLFX e não inclui suas 26 juntas OpenXR nem o compositor SBS. Outros aplicativos locais podem enviar pacotes nessa porta. Não há implementação completa de `XR_EXT_hand_tracking`, rastreamento de corpo, posição absoluta ou calibragem. Quando visível, a notificação permite parar a câmera; em Android 13+ ela pode não aparecer sem permissão de notificações. O serviço tenta parar quando as atividades do jogo deixam o primeiro plano; jogos que usam processos ou ciclos de vida diferentes precisam de validação adicional. Verifique o indicador de câmera e as configurações do Android.

O Monado contém o driver de sensores Android. O aplicativo também mostra o giroscópio no modo antigo, mas isso não prova funcionamento dentro de jogos. O modo antigo de launcher instala o jogo separadamente e usa broker público padrão, que pode conflitar com outros runtimes. O APK criado usa seu próprio broker privado e não precisa do launcher ou de outro runtime instalado.

## Compilação

A CI em [`.github/workflows/xr-mobile.yml`](../.github/workflows/xr-mobile.yml) usa JDK 17, Gradle 8.11.1, SDK/build-tools 35, Python 3, apktool 2.11.1, ARSCLib 1.4.0 e apksig 8.7.3. O runtime e o modelo, assim como o JAR ARSCLib, são verificados por SHA-256. A tarefa Gradle `prepareArscLibForAndroid` remove do JAR as cópias de `org.xmlpull.v1` para usar as interfaces da plataforma Android; isso evita incompatibilidade com o `FileProvider`. ARSCLib é Apache-2.0; o runtime binário é proveniente de [PhoneXR](https://github.com/samrat1games/phonexr) no commit `f393ea8c3f4579995efb7402d61a2108121b0900`, com direitos de redistribuição ainda não confirmados.

Para compilar localmente após obter as ferramentas, o modelo e o runtime fixados:

```sh
export XR_HAND_MODEL_DIR=/caminho/do/modelo
export XR_ARSCLIB_JAR=/caminho/ARSCLib-1.4.0.jar
export XR_INJECTION_ASSETS_DIR=/caminho/para/assets-gerados

gradle -p xr_mobile/embedded testReleaseUnitTest assembleRelease
python3 xr_mobile/build_payload.py \
  --helper xr_mobile/embedded/app/build/outputs/apk/release/app-release-unsigned.apk \
  --runtime /caminho/phonexr-runtime.apk --apktool-jar /caminho/apktool.jar \
  --output "$XR_INJECTION_ASSETS_DIR"
gradle -p xr_mobile/android testReleaseUnitTest assembleRelease
python3 -m unittest discover -s xr_mobile/tests -v
python3 xr_mobile/build_runtime.py \
  --app xr_mobile/android/app/build/outputs/apk/release/app-release-unsigned.apk \
  --runtime /caminho/phonexr-runtime.apk --apktool-jar /caminho/apktool.jar \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --debug-keystore /caminho/xr-debug.p12 --output xr-mobile-build/BigXR-debug.apk
```

O empacotador aplica uma guarda a uma extensão EGL opcional e marca o cliente Monado com `DF_1_NODELETE`, mantendo a biblioteca mapeada até o processo terminar. Essa mitigação evitou um crash observado no emulador, mas não substitui uma correção no upstream e pode manter memória por mais tempo. O ZIP é alinhado para 16 KB, mas os segmentos ELF do Monado têm alinhamento de 4 KB; compatibilidade com aparelhos que exigem páginas de 16 KB não está comprovada.

## Testes

A fixture em `xr_mobile/probe/` é um aplicativo próprio de diagnóstico, **não é um jogo**. No [teste anterior 45281192075](https://github.com/stumbledayoh-max/apk/actions/runs/45281192075), antes da integração OLFX, o criador selecionou APK, imagem e OBB da fixture, gerou e assinou a cópia, salvou-a e a instalou. Depois de desinstalar o criador, a cópia iniciou a câmera e o modelo, extraiu o OBB e completou três ciclos OpenXR e entradas sintéticas em uma sessão sem renderização. A câmera acrescentada permaneceu opcional. Os resultados resumidos estão em [`diagnosticos/45281192075/`](diagnosticos/45281192075/); uma execução anterior está em [`diagnosticos/45276416280/`](diagnosticos/45276416280/). O fluxo pode ser repetido por [`.github/workflows/bigxr-creator-test.yml`](../.github/workflows/bigxr-creator-test.yml). Isso não valida um jogo real nem um celular físico.

Nos testes anteriores do modo separado, a [captura 45102870985](https://github.com/stumbledayoh-max/apk/actions/runs/45102870985) passou seleção/instalação da fixture, cópia de OBB sintético, início e parada da câmera e três ciclos de criação/destruição OpenXR. No [teste 45103608864](https://github.com/stumbledayoh-max/apk/actions/runs/45103608864), entradas PH5 sintéticas e mudança do giroscópio virtual chegaram a uma sessão headless. MediaPipe também detectou mãos em uma foto de referência. São evidências em emulador, não gameplay, rastreamento de mãos ao vivo ou teste em celular físico. Resumos anteriores estão em [`diagnosticos/`](diagnosticos/).
