# XR de Bolso — launcher Android experimental

Aplicativo **Android nativo**, não uma página HTML. Ele permite escolher um APK de jogo e, opcionalmente, um arquivo OBB; inclui no próprio aplicativo um runtime Monado/PhoneXR e um modelo MediaPipe para experimentar mãos. **Não é preciso instalar o aplicativo PhoneXR ou outro runtime à parte.**

O Android instala o jogo escolhido como um aplicativo separado e pede sua confirmação. O launcher não executa APKs dentro do seu próprio processo e **não transforma qualquer jogo Android ou Quest em um jogo de celular**. A integração só pode ser tentada com jogos ARM64 que usem um loader OpenXR capaz de descobrir o runtime; mesmo neles, compatibilidade e gameplay não estão comprovados.

## Baixar e testar

A [build testada no emulador](https://github.com/stumbledayoh-max/apk/actions/runs/45102603776) contém o artefato `XR-de-Bolso-experimental`. Baixe o ZIP e extraia `XR-de-Bolso-debug.apk` (o GitHub pode exigir login e os artefatos expiram). O arquivo é uma build de teste para Android 10+ ARM64. SHA-256: `a068b8d7bf9cacfb36252169fa76d6f47a3daf43b73581202f6c5d30620858ad`.

A [lista de builds](https://github.com/stumbledayoh-max/apk/actions/workflows/xr-mobile.yml?query=branch%3Aarena%2Fe3a80795-apk) pode conter versões posteriores com assinatura de teste diferente.

1. Instale e abra o **XR de Bolso**. Use apenas APKs e dados de fontes confiáveis.
2. Toque em **Selecionar APK**. O app lê o pacote e informa se encontrou um loader OpenXR, mas isso não garante compatibilidade. Apenas APKs únicos são aceitos; conjuntos divididos `.apks`, `.apkm` e `.xapk` não são suportados.
3. Se houver OBB, selecione o arquivo original `main.<versão>.<pacote>.obb` ou `patch.<versão>.<pacote>.obb`. Depois de instalar o APK, toque em **Copiar OBB para o jogo**. Alguns aparelhos podem bloquear a gravação em `Android/obb`; nesse caso é necessário copiar o arquivo por outro meio permitido pelo aparelho.
4. Em **Instalar jogo**, autorize instalações para o launcher se o Android pedir e confirme a instalação na tela do sistema. O launcher não desinstala versões anteriores; diferenças de assinatura podem impedir atualizações.
5. Para experimentar mãos, toque em **Ativar e testar mãos**, permita a câmera traseira e observe os pontos na prévia. A câmera **continua ligada em segundo plano até você tocar em Parar câmera**, no aplicativo ou na notificação. Depois, tente **Abrir jogo instalado**.

A leitura do giroscópio aparece na tela quando o sensor existe. O runtime integrado contém suporte aos sensores Android, mas **não há validação de giroscópio ou de gestos dentro de jogos em celular físico**. As mãos detectadas são convertidas em posições e comandos aproximados de controle; isto **não implementa rastreamento nativo de dedos em todos os jogos**. Um controle externo pode ser necessário.

## Estado e segurança

- No [teste em emulador Android 15](https://github.com/stumbledayoh-max/apk/actions/runs/45102870985), a seleção e instalação de um APK de teste, a cópia de um OBB de teste, o início e a parada da câmera e a abertura/fechamento de três instâncias OpenXR passaram. Uma sessão sem renderização também recebeu comandos de mão sintéticos e registrou mudança de orientação com o giroscópio virtual. O MediaPipe detectou mãos e um gesto em uma foto de referência. Isso **não é gameplay**, câmera em tempo real com uma pessoa, nem teste em celular físico.
- A compilação e a assinatura não demonstram que um jogo funcione. Ainda são necessários testes reais de imagem, interação, desempenho, orientação, gestos e cópia de OBB em diferentes versões do Android.
- As bibliotecas Monado incorporadas usam alinhamento ELF de 4 KB; não há garantia de execução em aparelhos que exigem páginas de memória de 16 KB.
- O serviço OpenXR e o broker são expostos a outros aplicativos para permitir a conexão; a ponte de mãos envia estados aproximados apenas por UDP local (`127.0.0.1:42424`). A prévia mostra pontos, não envia quadros da câmera ao launcher.
- O APK tem assinatura **de depuração**, não é de produção e usa uma chave gerada em cada execução da CI. Atualizar entre builds pode exigir desinstalação; **desinstalar pode apagar dados**.
- O APK incorpora um runtime binário fixado do PhoneXR/Monado e MediaPipe. As permissões de redistribuição dos componentes PhoneXR não foram estabelecidas; confirme os direitos com os titulares antes de redistribuir o binário. Não inclua APKs ou OBBs de terceiros sem autorização.
- [`xr_mobile/README.md`](xr_mobile/README.md) reúne detalhes de compilação e limitações. O antigo experimento específico do QuestCraft permanece em [`questcraft_mobile/`](questcraft_mobile/README.md); a página HTML antiga não é o novo launcher.
