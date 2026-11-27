# LegendaAI

Aplicativo Android gratuito para selecionar um vídeo, transcrever fala em português no próprio aparelho usando Vosk e exportar legendas em arquivo SRT.

## Recursos
- Seleção de vídeo pelo seletor seguro do Android.
- Reconhecimento de fala em português brasileiro com modelo Vosk gratuito.
- Modelo de aproximadamente 31 MB baixado uma vez na primeira utilização.
- Transcrição local/offline após o download do modelo: o áudio não é enviado para uma API.
- Prévia dos trechos com marcação de tempo.
- Exportação de legenda `.srt`.

## Custos e internet
Não é necessária chave da OpenAI, assinatura, crédito ou pagamento por minuto. O modelo é baixado gratuitamente na primeira utilização; depois disso, o reconhecimento é feito localmente e funciona sem internet. O download inicial pode consumir dados móveis se não estiver em Wi-Fi.

## Limitações
Esta versão gera um arquivo SRT separado. Ainda não renderiza/queima as legendas dentro do vídeo MP4 nem inclui estilos animados de palavras. O modelo pequeno pode ser menos preciso do que serviços de transcrição maiores.

## Compilar
O workflow em `.github/workflows/android.yml` compila automaticamente um APK de depuração a cada push na branch `main`. O arquivo fica nos artefatos da execução do GitHub Actions por 30 dias.
