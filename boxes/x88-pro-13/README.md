# Box de référence : X88 Pro 13 (RK3528, Android 13)

Ce dossier contient la configuration exacte de la box sur laquelle le fork 5HC a été construit et testé, et les
scripts utilisés pour y arriver. Pour le launcher et les clés API, voir [LISEZMOI-5HC.md](../../LISEZMOI-5HC.md).

> ⚠️ **Lis d'abord le [rapport de sécurité](RAPPORT-SECURITE-X88-PRO-13.md).** Cette box est vendue avec un démon
> root caché, un ADB réseau ouvert sans authentification et plusieurs installateurs silencieux. Même nettoyée, ne t'y
> connecte à aucun compte important et garde-la hors de ton réseau principal.

| | |
|---|---|
| Modèle | X88 Pro 13 (`rk3528_box`), revendue aussi sous d'autres noms |
| Android | 13 (SDK 33), build `userdebug` (`su 0` disponible), Widevine L3 |
| Écran | 1920×1080 |
| Télécommande | IR (`ffa90030_pwm`) |

## Contenu du dossier

| Fichier | Rôle |
|---|---|
| `start.ps1` | Le « runner » : installe adb si besoin, trouve la box sur le réseau, puis exécute les commandes déposées dans `queue\` |
| `build-launcher.ps1` | Compile `launcher/` en `tvhome.apk` (télécharge le SDK Android officiel la première fois) |
| `RAPPORT-SECURITE-X88-PRO-13.md` | L'audit complet de la box et le journal de tout ce qui a été changé |
| `media/` | Les vidéos 5HC : intro, outro, veille, version longue, logo |
| `restore/` | Les retours arrière, un fichier par modification |
| `stub/gsdnode_stub.sh` | Le script inoffensif qui remplace le démon root (voir plus bas) |
| `tools/` | Petits scripts pour la box (focus à l'écran, attente d'une installation Play Store) |

## Le runner (`start.ps1`)

Il permet à un assistant IA sans accès direct à ton PC de piloter la box en toute transparence : l'assistant
dépose un fichier `queue\<nom>.job`, le runner l'exécute ligne par ligne et écrit le résultat dans `out\<nom>.out`.
Il ne lance **que des commandes adb**, et `pull`/`push` restent dans ce dossier.

```powershell
powershell -ExecutionPolicy Bypass -File .\start.ps1            # cherche la box sur le réseau
powershell -ExecutionPolicy Bypass -File .\start.ps1 -TvIp <ip>  # ou directement
```

Format d'un `.job` (une commande par ligne, `#` pour un commentaire) :

```text
shell getprop ro.build.version.release
@t300 install -r tvhome.apk             # @tNN : délai max en secondes (90 par défaut)
push /sdcard/x.mp4 media/5HC_outro.mp4  # push <chemin sur la box> <fichier du dossier>
pull /sdcard/ecran.png shots/ecran.png  # pull <chemin sur la box> <fichier du dossier>
@sleep 5
```

`queue\` et `out\` ne doivent **jamais** être publiés : ils contiennent l'IP de ta box et, quand tu renseignes tes
clés, les clés elles-mêmes. Le `.gitignore` de ce dossier les exclut.

## Ce qui a été fait sur cette box

Dans l'ordre, avec à chaque fois son retour arrière. Le détail, les empreintes et les vérifications sont dans le
rapport (§10).

1. **Sauvegarde** de la liste des paquets, des réglages et des fichiers d'origine avant toute modification.
2. **Apps désactivées** (`pm disable-user`, jamais désinstallées), en trois lots : apps de test d'usine,
   écran de veille publicitaire et streaming inutilisé, réinstalleur + store + mises à jour du fabricant.
   Retour : `restore/restore-all.job`.
3. **Launcher CleanYourTV** installé et choisi comme accueil, puis l'ancien launcher désactivé.
   Retour : `restore/restore-launcher.job`.
4. **Médias de l'accueil** copiés dans le dossier du launcher, sans recompiler (tableau ci-dessous).

Les trois modifications suivantes sortent des règles du dépôt d'origine (pas de root, rien de modifié dans le
système). Elles ne marchent que sur une box `userdebug` comme celle-ci, et **chacune exige ta propre sauvegarde du
fichier d'origine avant de commencer** : les fichiers du fabricant ne sont pas dans ce dépôt.

5. **Démon root neutralisé** : le binaire copié dans `/data/gsdnode/` est remplacé par `stub/gsdnode_stub.sh`, qui ne
   fait que dormir. Le lanceur du fabricant croit le démon actif et ne le recopie plus. `/system` n'est pas touché ;
   un reset d'usine le ferait revenir.
6. **Vidéo de démarrage** remplacée par la vidéo 5HC dans `/product/media/bootanimation.ts`.
   Retour : `restore/restore-bootvideo.job` (avec ta copie de l'original dans `bootvideo/`).
7. **Bouton power de la télécommande** remappé (`/vendor/usr/keylayout/ffa90030_pwm.kl`, scancode 116 →
   `STB_POWER WAKE`) pour que le launcher joue l'outro avant d'éteindre.
   Retour : `restore/restore-keylayout.job` (avec ta copie de l'original dans `restore/`).

## Les médias

À copier dans `/sdcard/Android/data/com.gathin.tvhome/files/media/` (le launcher doit avoir été lancé une fois) :

| Fichier du dépôt | Nom sur la box | Rôle |
|---|---|---|
| `media/5HC_intro_20s_a_partager.mp4` | `intro.mp4` | Intro après chaque démarrage et au réveil, avec le son |
| `media/5HC_outro.mp4` | `outro.mp4` | Outro quand on éteint avec la télécommande |
| `media/5HC_veille.mp4` | `idle.mp4` | Écran de veille, sans son, en boucle |
| `media/5HC_version_longue.mp4` | `library/5HC_long.mp4` | Version longue (57 s) |
| `media/5HC.mp4`, `media/5HC_logo_fond_noir.png` | `mine/` | Fonds d'écran (ordre alphabétique des noms) |

`5HC.mp4` n'a plus de son (le launcher coupe de toute façon le son des fonds et de la veille). L'intro, l'outro et la
version longue gardent leur musique : c'est elle qui fait l'effet.

```sh
adb push media/5HC_intro_20s_a_partager.mp4 /sdcard/Android/data/com.gathin.tvhome/files/media/intro.mp4
```

Pour couper l'intro sans rien supprimer : renomme `intro.mp4` en `intro.mp4.off` sur la box.

Les vidéos et le logo 5HC sont fournis pour cette configuration ; garde-les pour un usage personnel et ne les
présente pas comme les tiens.
