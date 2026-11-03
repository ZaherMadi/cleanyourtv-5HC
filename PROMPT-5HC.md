# Prompt : adapter CleanYourTV × 5HC à une autre box

Colle tout ce qui suit dans un assistant IA qui a un shell sur un PC relié à la box en ADB (Claude Code, Codex,
Gemini CLI…), en étant à la racine de ce dépôt. Il compare ta box à la box de référence, te montre l'écart, puis
adapte le launcher et l'installe avec toi, étape par étape.

---

Tu m'aides à installer le fork **CleanYourTV × 5HC** (ce dépôt) sur **ma** box ou TV Android, qui n'est pas forcément
celle sur laquelle il a été construit. Réponds-moi en français, simplement : je ne suis pas forcément développeur.

## Ce que tu dois lire d'abord

1. `PROMPT.md` : la procédure de nettoyage du dépôt d'origine. **Ses règles de sécurité s'appliquent telles quelles**
   et passent avant tout ce qui suit.
2. `README.md` : ce que le fork ajoute (deux accueils, volets YouTube / films et séries / sport, météo, intro,
   outro, veille vidéo) et comment on donne les clés API.
3. `boxes/x88-pro-13/README.md` et `boxes/x88-pro-13/RAPPORT-SECURITE-X88-PRO-13.md` : la **configuration de
   référence**, avec tout ce qui a été changé sur la box d'origine et pourquoi.

## Règles qui ne se négocient pas

- Tout ce qui est dans `PROMPT.md` : désactiver, jamais désinstaller ; sauvegarder avant le premier changement ;
  ne jamais lancer une app d'usine ou de test ; garder un accueil qui marche avant de désactiver l'ancien ;
  refermer ADB à la fin.
- **Phase 1 en lecture seule.** Tu ne modifies rien tant que je n'ai pas validé le rapport d'écart.
- **Rien de root sans mon accord explicite**, étape par étape. Les modifications système de la box de référence
  (vidéo de démarrage dans `/product`, touche power dans `/vendor`, démon neutralisé dans `/data`) ne s'appliquent
  qu'à une box `userdebug` où `su 0` marche, et seulement après une copie vérifiée (md5) du fichier d'origine. Sur
  une box `user` ou une TV de marque : tu les sautes, sans chercher à rooter.
- **Mes clés API ne vont jamais dans un fichier** du dépôt, un commit ou un message public. Elles vont sur la box
  avec la commande `am start … --es …` de `README.md`, et nulle part ailleurs.
- **Aucune donnée qui m'identifie** dans ce que tu écris ou montres : pas d'IP, de nom de Wi-Fi, de numéro de série,
  d'adresse MAC, d'e-mail. Floute le nom du Wi-Fi sur les captures.
- Si la box refuse quelque chose (désactivation refusée, installation bloquée, Android plus ancien que le `minSdk` 26
  du launcher) : tu t'arrêtes et tu me le dis, sans boucler.

## Phase 1 : inventaire (lecture seule)

Connecte-toi (`adb connect <ip>:5555` ou USB) et relève, en une commande `adb shell` à la fois :

- Identité : `getprop ro.product.model`, `ro.product.device`, `ro.board.platform`, `ro.build.version.release`,
  `ro.build.version.sdk`, `ro.build.type`, `ro.debuggable`, `ro.boot.verifiedbootstate`, `ro.build.fingerprint`.
- Écran : `wm size`, `wm density`. Mémoire : `cat /proc/meminfo | head -3`.
- Accueil : `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`,
  et `dumpsys activity activities | grep mFocusedApp`.
- Paquets : `pm list packages -s`, `pm list packages -3`, `pm list packages -d` (désactivés).
- Apps des volets installées ou non : `com.google.android.youtube.tv`, `com.netflix.ninja` /
  `com.netflix.mediaclient`, `com.amazon.amazonvideo.livingroom`, `com.disney.disneyplus`.
- Veille : `settings get secure screensaver_components`, `settings get system screen_off_timeout`.
- Télécommande : `dumpsys input | grep -A3 "Device [0-9]*:"` (nom de la télécommande, fichier `.kl` utilisé), et
  `ls /vendor/usr/keylayout /system/usr/keylayout`.
- Signes du profil « box préinfectée » de la référence (rapport §3) : processus root qui ne viennent pas d'Android
  (`ps -A -o USER,PID,NAME | grep ^root`), scripts de démarrage du fabricant (`ls /system/bin/*.sh`), ADB réseau
  ouvert sans autorisation (`getprop service.adb.tcp.port`, `getprop ro.adb.secure`), installateurs et stores du
  fabricant, écran de veille publicitaire.
- Partitions : `mount | grep -E " /(product|vendor|system) "` et `getprop ro.boot.veritymode`.

## Phase 2 : rapport d'écart

Donne-moi un tableau, une ligne par point, en comparant à la box de référence (X88 Pro 13, Android 13, 1920×1080,
`userdebug`, 4 Go, télécommande IR `ffa90030_pwm`) :

| Point | Box de référence | Ta box | Écart | Ce que je propose | Réversible ? |
|---|---|---|---|---|---|

Points à couvrir au minimum : version d'Android et SDK ; type de build et accès root ; résolution et densité ;
mémoire ; accueil d'origine et façon de le remplacer ; apps publicitaires, de test d'usine et installateurs
silencieux (compare avec `boxes/x88-pro-13/restore/restore-all.job` et le rapport §8.3, sans supposer que les mêmes
noms veulent dire la même chose) ; ADB réseau ouvert ; télécommande et bouton power (l'outro a besoin que la touche
power arrive au launcher : remappage possible ou non) ; vidéo de démarrage ; écran de veille ; apps de streaming
installées (elles décident quels volets films et séries s'affichent) ; pays et langue (voir plus bas).

Puis, en trois lignes : ce qui marchera tel quel, ce qui demande une adaptation, ce qui ne marchera pas sur cette box.

## Phase 3 : mes choix

Pose-moi les questions dans un seul message, avec un choix par défaut pour chacune :

- Quel accueil par défaut : *Mode simple* ou *Mode personnalisé* (on peut changer après) ?
- Quels volets : YouTube, Films & séries, Sport, météo ? Ai-je déjà les clés (sinon, donne-moi le tutoriel de
  `README.md` pour celles qui manquent) ?
- Quelles apps désactiver, en une seule liste à cocher pour celles que la famille utilise (streaming, assistant
  vocal, Chromecast, mises à jour), comme le demande `PROMPT.md` ?
- Intro, outro, veille vidéo : avec les vidéos 5HC de `boxes/x88-pro-13/media/`, les miennes, ou aucune ?
- Les modifications root (seulement si la phase 1 a montré qu'elles sont possibles), une par une.

## Phase 4 : adapter et installer

- Le seul fichier à changer pour une autre box est en principe `launcher/app/src/main/res/values/config.xml`
  (langue `locale`, rangées, dock…). Ne touche au Java que si un écart l'exige, et dis-moi pourquoi avant.
- Le pays est codé en dur pour la France : `regionCode=FR` dans `YouTubeFeed.java`, `country=fr` dans
  `StreamingFeed.java`, `Europe/Paris` dans `SportFeed.java`. Si je suis ailleurs, propose de les changer.
- Si la box a moins de 2 Go de mémoire, recommande le *Mode simple* et dis-moi que le *Mode personnalisé* risque
  d'être lent.
- Compile avec `launcher/build-and-install.sh` (Linux/macOS) ou `boxes/x88-pro-13/build-launcher.ps1` (Windows),
  installe, confirme que c'est bien l'accueil, puis seulement ensuite désactive l'ancien.
- Désactive par petits lots, avec une capture d'écran entre chaque (`tools/tv.py --screenshot shots/01.png`).
- Donne-moi la commande des clés à lancer moi-même, avec des valeurs à remplacer : ne me demande pas de te les
  écrire dans le chat si je peux la taper.
- Après un redémarrage, vérifie : accueil, volets, météo, intro et veille, et que rien de désactivé n'est revenu.

## Phase 5 : fin

- Un résumé de ce qui a changé, avec pour chaque point la commande de retour arrière.
- Referme ADB comme le décrit `PROMPT.md` (débogage réseau coupé, `tools/adb-closed.py` doit dire « closed »).
- Si je veux partager la configuration de ma box, propose de créer `boxes/<modèle>/` sur le modèle de
  `boxes/x88-pro-13/`, sans aucune donnée qui m'identifie.
