# MirKey

Tastiera Android per scrivere veloce senza sbagliare lettera.
È basata su [HeliBoard](https://github.com/HeliBorg/HeliBoard) (a sua volta derivata da AOSP e OpenBoard): completamente offline, nessun permesso di accesso a internet.

## Cosa aggiunge a HeliBoard

**Zone di tocco dinamiche.** Ogni tasto ha un centro protetto che risponde sempre a quel tasto. Il bordo esterno invece viene conteso dai tasti vicini: vince quello che combina meglio la distanza dal dito e la probabilità della lettera successiva, data la parola che stai scrivendo. Dopo "grazi" la E si allarga e la W si restringe; dopo una parola completa si allarga lo spazio, così un tocco storto sulla M o sul punto diventa uno spazio. Funziona in italiano; nei campi password è disattivata.

Il codice è in `app/src/main/java/helium314/keyboard/mirkey/`.

## In arrivo

- Punto al posto dello spazio corretto in automatico ("della.zona" → "della zona")
- Calibrazione sul punto in cui cade il tuo pollice
- Modello delle sequenze di parole, per i casi come "senza provate" → "senza provare"

## Installare

Ogni modifica su `main` produce un APK nella pagina [Releases](../../releases/latest).

Per avere una firma stabile (aggiornamenti installabili sopra la versione precedente) serve il segreto `MIRKEY_KEYSTORE_B64` nelle impostazioni del repository; senza, ogni build ha una firma provvisoria.

## Licenza e crediti

GPL-3.0, come HeliBoard (vedi `LICENSE`, `LICENSE-Apache-2.0`, `LICENSE-CC-BY-SA-4.0` e `HELIBOARD.md`).
Le frequenze delle parole italiane in `app/src/main/assets/mirkey/` derivano da [wordfreq](https://github.com/rspeer/wordfreq) di Robyn Speer, con dati distribuiti sotto licenza CC-BY-SA 4.0.
