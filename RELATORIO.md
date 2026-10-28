# Relatório — enxugamento do núcleo, onda 2

Pacote: `pushinbr/pam-native-media`. Branch: `feat/port4-core-slim-20261028`. Versão pública: `0.7.0`.

## O que foi feito

- A capacidade opcional passou a pertencer ao plugin; SDK mínimo 1.46.0.
- O núcleo conserva aliases PHP depreciados por uma versão, até 1.47.
- Contratos nativos e dados tipados foram preservados; testes acompanham a implementação.
- Release: [0.7.0](https://github.com/push-in/pam-native-media/releases/tag/v0.7.0). Atualização do Packagist solicitada e aceita pela API.

## Validação

- PHP: 20/20.
- Compile Kotlin conjunto do núcleo e cinco plugins: aprovado.
- Testes JVM conjuntos: 360/360, zero falhas.
- Compilação dos testes instrumentados conjuntos: aprovada; execução em dispositivo pendente.
- SDK PHP com contratos de compatibilidade dos plugins: aprovado.
- Swift: pendente; Mac proibido.
- QA em dispositivo e medição de CPU/PSS: pendentes; o emulador compartilhado estava ocupado. S10 não utilizado.

## Commits de implementação

```text
cc3a537 test: move gallery and capture instrumented coverage out of the core [skip ci]
3b69220 feat: own gallery, system capture and view capture outside the core [skip ci]
cb984fa Merge pull request #10 from push-in/fix/port4-camera-enabled-20261027
```

O commit deste relatório é posterior aos commits acima. Nenhuma release do núcleo 1.46.0 foi publicada.
